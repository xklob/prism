package com.caleb.prism

import android.Manifest
import android.content.Intent
import android.media.*
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/** Requires the explicitly labelled local synthetic com.spotify.music fixture and Shizuku. */
@RunWith(AndroidJUnit4::class)
class SpotifyRelayDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null
    private var fixtureVerified = false
    private val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
    private fun refresh() { instrumentation.uiAutomation.clearCache() }
    private fun tap(label: String) { refresh(); requireNotNull(device.wait(Until.findObject(By.text(label)), 6000)) { label }.click(); SystemClock.sleep(300) }
    private fun waitFor(label: String, test: () -> Boolean) {
        val deadline=SystemClock.elapsedRealtime()+15000
        while (!test() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
        if (!test()) {
            File(evidence,"relay-failure-producer.txt").writeText(device.executeShellCommand("run-as com.spotify.music cat files/fixture-status.txt"))
            File(evidence,"relay-failure-policy.txt").writeText(device.executeShellCommand("dumpsys media.audio_policy"))
            File(evidence,"relay-failure-flinger.txt").writeText(device.executeShellCommand("dumpsys media.audio_flinger"))
        }
        assertTrue("$label // ${AudioEngine.status.value.message} // ${SpotifyRelay.description}",test())
    }
    private fun fixture(policy: Int, rate: Int = 48000) {
        device.executeShellCommand("am force-stop com.spotify.music")
        device.executeShellCommand("run-as com.spotify.music rm -f files/fixture-status.txt")
        device.executeShellCommand("am start -W -n com.spotify.music/.FixtureActivity --ei policy $policy --ei rate $rate")
        waitFor("Synthetic producer is playing at $rate Hz with policy $policy") {
            val status = device.executeShellCommand("run-as com.spotify.music cat files/fixture-status.txt")
            status.contains("policy=$policy rate=$rate playState=3") &&
                Regex("head=([1-9][0-9]*)").containsMatchIn(status)
        }
        device.executeShellCommand("am start -n ${context.packageName}/com.caleb.prism.MainActivity")
        SystemClock.sleep(500)
    }
    @Before fun launch() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("relayFixture") == "true")
        Configurator.getInstance().waitForIdleTimeout=300
        device.pressBack(); device.pressBack()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        assertEquals("prism synthetic audio fixture", context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo("com.spotify.music",0)))
        fixtureVerified = true
        context.getSharedPreferences("recordings",0).edit().clear().commit()
        context.getSharedPreferences("prism",0).edit().clear().commit()
        SettingsStore(context).save(VisualSettings.defaults(Scene.KALEIDO).copy(audioEnabled=true,source=AudioSource.SYSTEM))
        SpotifyRelay.init(context); SpotifyRelay.stop(); SpotifyRelay.updateEnabled(true)
        device.wakeUp();device.setOrientationNatural()
        activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        SystemClock.sleep(1000)
    }
    @After fun cleanup() {
        if (!fixtureVerified) return
        AudioEngine.stop(); context.stopService(Intent(context,PlaybackCaptureService::class.java)); SpotifyRelay.stop()
        SpotifyRelay.updateEnabled(false)
        device.executeShellCommand("am force-stop com.spotify.music")
        instrumentation.runOnMainSync { activity?.finish() }; device.unfreezeRotation()
    }
    private fun connect(compatibility: Boolean = true) {
        device.takeScreenshot(File(evidence,"relay-before-connect.png"))
        tap("Connect audio");tap("CONTINUE")
        val deadline=SystemClock.elapsedRealtime()+18000
        while (!AudioEngine.status.value.running && SystemClock.elapsedRealtime()<deadline) {
            refresh()
            device.findObject(By.text(java.util.regex.Pattern.compile("(?i)(allow all the time|allow|start now|continue|share screen|share audio)")))?.click()
            SystemClock.sleep(250)
        }
        waitFor("Compatibility capture connects") { AudioEngine.status.value.running && (!compatibility || SpotifyRelay.active) }
        waitFor("Relay delivers captured PCM") { AudioEngine.inputRms>.01f }
    }
    @Test fun restrictedMediaRemainsStereoAndDoesNotDoubleWhenPolicyChanges() {
        val manager=context.getSystemService(AudioManager::class.java)
        val volume=manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC,manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),0)
        try {
            fixture(AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM); connect()
            assertEquals(2,AudioEngine.status.value.channels)
            val measurements=StringBuilder("Synthetic source package; not Spotify playback. Actual Shizuku UserService.\n")
            for (rate in listOf(44100,48000)) for (policy in listOf(1,2,3,1)) {
                fixture(policy,rate)
                waitFor("Source restarts at $rate Hz with policy $policy") { AudioEngine.inputRms > .02f || !SpotifyRelay.healthy() }
                assertTrue("Relay remains healthy across source restart: ${AudioEngine.status.value}",SpotifyRelay.healthy())
                SystemClock.sleep(400)
                // AudioTrack may be recreated while Android moves the producer between mixes.
                // Measure the settled level over many analysis blocks, rather than one transition block.
                val readings = FloatArray(20) { SystemClock.sleep(50); AudioEngine.inputRms }
                val rms = readings.sorted()[readings.size / 2]
                measurements.append("source_hz=$rate policy=$policy median_rms=$rms min=${readings.minOrNull()} max=${readings.maxOrNull()}\n")
                File(evidence,"shizuku-policy-measurements.txt").writeText(measurements.toString())
                // The input meter measures the mono analysis mix of two different tones.
                assertEquals("No duplicated source or missing audio",.1526f,rms,.02f)
                assertTrue(SpotifyRelay.healthy())
            }
            File(evidence,"shizuku-policy-measurements.txt").writeText(measurements.toString())
            refresh(); requireNotNull(device.findObject(By.desc("Record session"))).click()
            waitFor("Recording starts with relay audio") { refresh(); device.hasObject(By.desc("Stop recording")) }
            SystemClock.sleep(5000)
            device.takeScreenshot(File(evidence,"spotify-relay-recording.png"))
            requireNotNull(device.findObject(By.desc("Stop recording"))).click()
            val prefs=context.getSharedPreferences("recordings",0)
            waitFor("Recording saved") { prefs.getString("uri",null)!=null }
            val uri=android.net.Uri.parse(prefs.getString("uri",null))
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(context,uri,null)
                val formats=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                val video=formats.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                val audio=formats.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                assertEquals(device.displayWidth,video.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(device.displayHeight,video.getInteger(MediaFormat.KEY_HEIGHT))
                assertEquals(2,audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                assertEquals(48000,audio.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                File(evidence,"native-recording-format.txt").writeText("video=$video\naudio=$audio\n")
            } finally { extractor.release(); context.contentResolver.delete(uri,null,null) }


        } finally { manager.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0) }
    }
    @Test fun compatibilityPreservesVolumeAgainstOrdinaryCapture() {
        val audio=context.getSystemService(AudioManager::class.java)
        val old=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/2,0)
            fixture(1);connect();SystemClock.sleep(1500)
            val relayed=AudioEngine.inputRms
            tap("Stop");SystemClock.sleep(600);SpotifyRelay.updateEnabled(false)
            connect(compatibility=false);SystemClock.sleep(1500)
            val direct=AudioEngine.inputRms
            assertEquals("The relay must preserve the selected playback volume",direct,relayed,maxOf(.002f,direct*.15f))
            File(evidence,"relay-volume.txt").writeText("relayed=$relayed direct=$direct\n")
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC,old,0) }
    }
    @Test fun canceledProjectionDoesNotStartOrRetainTheRelay() {
        fixture(2);tap("Connect audio");tap("CONTINUE")
        waitFor("Android capture consent is shown") { refresh(); device.hasObject(By.text("Share screen")) }
        assertFalse(SpotifyRelay.active)
        device.pressBack()
        waitFor("Canceled capture releases prepared helper") { SpotifyRelay.sourceUid == null }
        assertFalse(AudioEngine.status.value.running)
    }
    @Test fun signedReleaseRelaySmoke() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("releaseRelay") == "true")
        fixture(2)
        context.startActivity(Intent().setClassName("com.caleb.prism","com.caleb.prism.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            tap("App settings"); tap("Audio")
            val toggle=requireNotNull(device.findObject(By.desc("React to audio")))
            if (!generateSequence(toggle) { it.parent }.take(3).any { it.isChecked }) toggle.click()
            tap("System audio")
            refresh()
            val compatible=requireNotNull(device.findObject(By.desc("Spotify compatibility")))
            if (!generateSequence(compatible) { it.parent }.take(3).any { it.isChecked }) compatible.click()
            tap("Connect");tap("CONTINUE")
            val deadline=SystemClock.elapsedRealtime()+18000
            while (SystemClock.elapsedRealtime()<deadline) {
                refresh()
                device.findObject(By.text(java.util.regex.Pattern.compile("(?i)(allow all the time|allow|start now|continue|share screen|share audio)")))?.click()
                if (device.hasObject(By.text("Stop"))) break
                SystemClock.sleep(250)
            }
            waitFor("Signed build captures input") { refresh(); device.hasObject(By.text("Sound received")) }
            device.takeScreenshot(File(evidence,"signed-spotify-relay.png"))
            tap("Stop")
        } finally { device.executeShellCommand("am force-stop com.caleb.prism") }
    }
    @Test fun helperDeathStopsCapture() {
        fixture(2);connect()
        val pid=device.executeShellCommand("pidof ${context.packageName}:spotify_relay").trim()
        assertTrue("The helper is a separate process",pid.isNotEmpty())
        device.executeShellCommand("kill $pid")
        waitFor("Helper death stops capture") { !AudioEngine.status.value.running }
        assertNotNull(AudioEngine.status.value.message)
        SpotifyRelay.updateEnabled(false)
    }
}
