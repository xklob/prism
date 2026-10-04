package com.caleb.prism

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SongDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null
    private val fixture = ComponentName(instrumentation.context.packageName, MediaFixtureService::class.java.name)
    private val listener get() = "${context.packageName}/${NowPlayingListener::class.java.name}"

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
        java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }
    private fun await(message: String, seconds: Int = 15, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + seconds * 1000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue(message, condition())
    }
    private fun click(text: String) {
        instrumentation.uiAutomation.clearCache()
        var found = device.wait(Until.findObject(By.text(text)), 3000)
        if (found == null) {
            device.findObject(By.scrollable(true))?.scroll(Direction.UP, 1f)
            found = device.wait(Until.findObject(By.text(text)), 2000)
        }
        assertNotNull("Expected $text", found); found!!.click(); SystemClock.sleep(350)
    }
    private fun launch(system: Boolean = false, assist: Boolean = false) {
        context.getSharedPreferences("prism", 0).edit().clear().putInt("source", if (system) 1 else 0)
            .putBoolean("scene.AURORA.audioEnabled", system).commit()
        context.getSharedPreferences("song_assist", 0).edit().clear().putBoolean("enabled", assist).commit()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        device.wakeUp(); device.setOrientationNatural()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        assertTrue(device.wait(Until.hasObject(By.text("PRISM")), 10000))
        SystemClock.sleep(800)
    }

    @After fun cleanup() {
        context.stopService(Intent().setComponent(fixture))
        shell("cmd notification disallow_listener $listener")
        instrumentation.runOnMainSync { activity?.finish() }
        SystemClock.sleep(500)
        context.getSharedPreferences("song_assist", 0).edit().clear().commit()
        AudioEngine.stop(); device.setOrientationNatural(); device.unfreezeRotation()
    }

    @Test fun playbackMetadataAndRealLookupHandleSeekPauseSpeedAndRevocation() {
        launch(system = true, assist = true)
        shell("cmd notification allow_listener $listener")
        context.startForegroundService(Intent().setComponent(fixture))
        await("Media-session access must discover the other app") { NowPlaying.playback.value?.song?.title == "Wake Me Up" }
        assertEquals("0nrRP2bk19rLc0orkWPQk2", NowPlaying.playback.value!!.song.spotifyId)
        click("Connect audio")
        device.wait(Until.findObject(By.res("android:id/button1")), 6000)!!.click()
        await("System capture starts") { AudioEngine.status.value.running }
        await("Real ReccoBeats lookup should supply BPM", 35) { AudioEngine.tempoHint != null }
        assertEquals(124.08, AudioEngine.tempoHint!!.bpm, .01)
        assertFalse("Metadata cannot establish beat confidence on silence", AudioEngine.rhythm.locked)
        val revision = AudioEngine.songTimingRevision
        context.startService(Intent().setComponent(fixture).putExtra("position", 120000L))
        await("Seek clears phrase alignment") { AudioEngine.songTimingRevision > revision }
        assertTrue(NowPlaying.playback.value!!.positionMs!! >= 120000)
        context.startService(Intent().setComponent(fixture).putExtra("position", 120000L).putExtra("paused", true))
        await("Pause suspends the tempo prior") { NowPlaying.playback.value?.playing == false && AudioEngine.tempoHint == null }
        context.startService(Intent().setComponent(fixture).putExtra("position", 120000L).putExtra("speed", 1.25f))
        await("Playback speed changes the prior") { kotlin.math.abs((AudioEngine.tempoHint?.bpm ?: 0.0) - 155.1) < .1 }
        val folder = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
        device.takeScreenshot(File(folder, "song-metadata.png"))
        shell("cmd notification disallow_listener $listener")
        await("Revocation clears stale metadata and catalog assistance") { AudioEngine.tempoHint == null && NowPlaying.playback.value == null }
    }

    @Test fun recognitionResponseAndEncryptedKeyRoundTrip() {
        val parsed = SongServices.parseRecognition(JSONObject("""{"status":"success","result":{"title":"Wake Me Up","artist":"Avicii","timecode":"01:02","spotify":{"id":"0nrRP2bk19rLc0orkWPQk2","duration_ms":247426,"external_ids":{"isrc":"SEUM71301326"}}}}"""))!!
        assertEquals(62.0, parsed.offsetSeconds!!, 0.0)
        assertEquals(247426L, parsed.song.durationMs)
        assertEquals("SEUM71301326", parsed.song.isrc)
        assertNull(SongServices.parseRecognition(JSONObject("""{"status":"success","result":null}""")))
        val store = SongPreferences(context)
        val fakeKey = "PrismSyntheticTestKeyNeverSent"
        store.setToken(fakeKey)
        assertEquals(fakeKey, store.token())
        assertTrue(store.options().hasKey)
        assertFalse(context.getSharedPreferences("song_assist", 0).all.values.any { it.toString().contains(fakeKey) })
        store.setToken(""); assertNull(store.token()); assertFalse(store.options().hasKey)
    }

    @Test fun auddTransportRejectsAnInvalidKeyWithoutUsingPersonalAudio() {
        // Explicit opt-in: only a synthetic tone and an intentionally invalid credential leave the device.
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("songNetwork") == "true")
        val pcm = ShortArray(16000 * 8) { (kotlin.math.sin(it * 2 * Math.PI * 440 / 16000) * 4000).toInt().toShort() }
        try {
            SongServices().recognize(RecognitionClip(pcm, 0, 8_000_000_000), "PrismInvalidTestToken")
            fail("Invalid token must not be accepted")
        } catch (error: SongServiceException) { assertTrue("Authentication failure must stop automatic retries", error.needsKey) }
    }

    @Test fun songOverlaySettingsPersistAndMissingKeyCannotUpload() {
        launch()
        click("Tune"); click("Song")
        val toggle = device.wait(Until.findObject(By.desc("Song assist")), 4000)!!
        toggle.click(); SystemClock.sleep(400)
        assertTrue(SongPreferences(context).options().enabled)
        assertFalse(SongPreferences(context).options().recognition)
        val scroll = device.findObject(By.scrollable(true))!!
        repeat(3) { scroll.scroll(Direction.DOWN, .85f); SystemClock.sleep(150) }
        click("8 bars")
        assertEquals(8, SongPreferences(context).options().phraseBars)
        val folder = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
        device.takeScreenshot(File(folder, "song-phrase-overlay.png"))
        assertFalse(AudioEngine.status.value.running)
        click("Done"); click("Tune"); click("Song")
        assertTrue(SongPreferences(context).options().enabled)
        assertEquals(8, AudioEngine.phraseBars)
    }
}
