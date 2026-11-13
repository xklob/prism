package com.caleb.prism

import android.content.*
import android.media.*
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LocalAudioDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null
    private val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
    private val fixtures = ArrayList<File>()
    private val exports = ArrayList<Uri>()
    private fun refresh() { instrumentation.uiAutomation.clearCache() }
    private fun waitFor(label: String, timeout: Long = 120000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("$label // ${LocalAudio.state.value}", condition())
    }
    private fun tap(description: String) {
        refresh(); val node = requireNotNull(device.wait(Until.findObject(By.desc(description)), 6000)) { description }
        node.click(); SystemClock.sleep(300)
    }
    @Before fun launch() {
        Configurator.getInstance().waitForIdleTimeout = 300
        LocalAudio.init(context); LocalAudio.recordingLocked = false; LocalAudio.unload()
        context.getSharedPreferences("recordings", 0).edit().clear().commit()
        SettingsStore(context).save(VisualSettings.defaults(Scene.KALEIDO).copy(source = AudioSource.LOCAL, audioEnabled = true))
        device.executeShellCommand("cmd appops set ${context.packageName} RECORD_AUDIO ignore")
        device.wakeUp(); device.setOrientationNatural()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        SystemClock.sleep(800)
    }
    @After fun cleanup() {
        LocalAudio.recordingLocked = false; LocalAudio.unload()
        context.stopService(Intent(context, LocalAudioService::class.java))
        instrumentation.runOnMainSync { activity?.finish() }
        fixtures.forEach { it.delete() }; exports.forEach { context.contentResolver.delete(it, null, null) }
        device.executeShellCommand("cmd appops set ${context.packageName} RECORD_AUDIO default")
        device.unfreezeRotation()
    }
    private fun wav(rate: Int = 48000, music: Boolean = true): File {
        val pcm = if (music) instrumentation.context.assets.open("rhythm/drums128.pcm").use { input ->
            val bytes = input.readBytes(); val mono = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(mono)
            ShortArray(mono.size * 2) { mono[it / 2] }
        } else ShortArray(rate * 8 * 2) { i -> (sin(2 * PI * (if (i % 2 == 0) 440 else 880) * (i / 2) / rate) * 10000).roundToInt().toShort() }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16); put("data".toByteArray()); putInt(pcm.size * 2)
        }.array()
        return File(context.cacheDir, "prism-synthetic-$rate-${fixtures.size}.wav").also { file ->
            fixtures.add(file); file.outputStream().use { out -> out.write(header); val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN); bytes.asShortBuffer().put(pcm); out.write(bytes.array()) }
        }
    }
    private fun analyze(file: File) {
        instrumentation.runOnMainSync { LocalAudio.load(Uri.fromFile(file)) }
        assertFalse(AudioEngine.status.value.running)
        waitFor("Full-track analysis finishes before playback") { !LocalAudio.state.value.analyzing }
        assertTrue(LocalAudio.state.value.toString(), LocalAudio.state.value.ready)
    }
    @Test fun a_filePickerPlaybackSeekBackgroundAndRecording() {
        val file = wav()
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "prism-synthetic-128.wav"); put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        exports.add(uri)
        context.contentResolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        refresh(); requireNotNull(device.wait(Until.findObject(By.text("CHOOSE FILE")), 5000)).click()
        var item = device.wait(Until.findObject(By.text("prism-synthetic-128.wav")), 6000)
        if (item == null) {
            device.findObject(By.desc("Show roots"))?.click(); SystemClock.sleep(300)
            device.findObject(By.text("Downloads"))?.click(); SystemClock.sleep(300)
            item = device.wait(Until.findObject(By.text("prism-synthetic-128.wav")), 5000)
        }
        requireNotNull(item) { "The Android file picker exposes the local fixture" }.click()
        waitFor("Picker starts analysis") { LocalAudio.state.value.analyzing || LocalAudio.state.value.ready }
        waitFor("Track analyzed") { !LocalAudio.state.value.analyzing }
        assertTrue(LocalAudio.state.value.toString(), LocalAudio.state.value.ready)
        assertEquals(128f, LocalAudio.state.value.bpm, 2f)
        assertTrue(LocalAudio.track!!.map.sample(0.0).rhythm.locked)
        assertFalse(AudioEngine.status.value.running)
        assertNull(AudioEngine.audioSessionId)
        val tablet = context.resources.configuration.smallestScreenWidthDp >= 600
        refresh()
        if (LocalAudio.state.value.waveform == null) assertEquals(tablet, device.hasObject(By.desc("Track waveform panel")))
        LocalAudio.waveform(true); SystemClock.sleep(500); refresh()
        val wave = requireNotNull(device.findObject(By.desc("Track waveform panel"))).visibleBounds
        for (name in listOf("Pattern controls panel", "Local playback controls", "Record session") + if (tablet) listOf("Tablet song and timing monitor") else emptyList()) {
            val box = requireNotNull(device.findObject(By.desc(name))).visibleBounds
            assertFalse("Waveform overlaps $name", android.graphics.Rect.intersects(wave, box))
        }
        tap("Play local playback")
        waitFor("File audio plays without microphone access") { AudioEngine.status.value.running && LocalAudio.state.value.position > 1.5 }
        assertEquals(AudioSource.LOCAL, AudioEngine.status.value.source)
        assertNull(AudioEngine.audioSessionId)
        assertTrue(AudioEngine.rhythm.locked)
        device.takeScreenshot(File(evidence, "local-file-${device.displayWidth}.png"))
        refresh()
        val seek = requireNotNull(device.findObject(By.desc("Track waveform seek"))).visibleBounds
        device.click(seek.left + (seek.width() * .15).toInt(), seek.centerY())
        waitFor("Waveform tap seeks to file time") { LocalAudio.state.value.position in 4.7..7.0 }
        device.swipe(seek.left + (seek.width() * .2).toInt(), seek.centerY(), seek.left + (seek.width() * .3).toInt(), seek.centerY(), 25)
        waitFor("Waveform drag commits a seek") { LocalAudio.state.value.position in 9.0..12.0 }
        tap("Restart local track")
        waitFor("Restart resets the file clock") { LocalAudio.state.value.position in .2..2.0 && AudioEngine.rhythm.locked }
        tap("Forward ten seconds")
        waitFor("Seek uses the precomputed grid immediately") { LocalAudio.state.value.position > 11.0 && AudioEngine.rhythm.locked }
        val expected = LocalAudio.track!!.map.sample(LocalAudio.state.value.position).rhythm
        assertEquals(expected.bpm, AudioEngine.rhythm.bpm, .1f)
        tap("Pause local playback")
        val paused = LocalAudio.state.value.position
        SystemClock.sleep(600); assertEquals(paused, LocalAudio.state.value.position, .001)
        assertFalse(AudioEngine.status.value.running)
        tap("Play local playback")
        waitFor("Resume after seek") { LocalAudio.state.value.playing && LocalAudio.state.value.position > paused + .3 }
        device.pressHome(); SystemClock.sleep(1200)
        assertTrue(LocalAudio.state.value.playing)
        device.executeShellCommand("am start -W -n ${context.packageName}/com.caleb.prism.MainActivity")
        SystemClock.sleep(500); tap("Record session")
        waitFor("Local recording starts", 20000) { refresh(); device.hasObject(By.desc("Stop recording")) }
        assertTrue(LocalAudio.recordingLocked)
        val before = LocalAudio.state.value.position
        LocalAudio.seek(0.0); SystemClock.sleep(300)
        assertTrue("Seek remains locked while recording", LocalAudio.state.value.position >= before)
        SystemClock.sleep(2200); tap("Stop recording")
        waitFor("Local soundtrack video saved", 25000) { context.getSharedPreferences("recordings", 0).getString("uri", null) != null }
        val video = Uri.parse(context.getSharedPreferences("recordings", 0).getString("uri", null)); exports.add(video)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, video, null)
            val audio = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            assertEquals(2, audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT)); assertEquals(48000, audio.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            val copied = File(evidence, "local-recording.mp4")
            context.contentResolver.openInputStream(video)!!.use { input -> copied.outputStream().use { input.copyTo(it) } }
            File(evidence, "local-recording-format.txt").writeText(audio.toString())
        } finally { extractor.release() }
        assertTrue("Recording completion keeps the local player running", LocalAudio.state.value.playing)
        tap("Pause local playback")
    }
    @Test fun b_nonNativeRateAndCancellationAndBadFiles() {
        analyze(wav(44100, music = false))
        assertEquals(8.0, LocalAudio.state.value.duration, .01)
        assertEquals(2, LocalAudio.track!!.channels)
        assertEquals(0f, LocalAudio.state.value.bpm, 0f)
        instrumentation.runOnMainSync { LocalAudio.play() }
        waitFor("Unmetered file still supplies musical texture", 10000) { LocalAudio.state.value.position > .3 && AudioEngine.rhythm.signalPresent }
        assertFalse(AudioEngine.rhythm.locked)
        assertTrue(AudioEngine.levels.energy > 0f)
        instrumentation.runOnMainSync { LocalAudio.pause() }
        val bad = File(context.cacheDir, "prism-invalid-audio.wav").apply { writeText("not an audio file"); fixtures.add(this) }
        instrumentation.runOnMainSync { LocalAudio.load(Uri.fromFile(bad)) }
        waitFor("Bad files fail clearly") { !LocalAudio.state.value.analyzing }
        assertFalse(LocalAudio.state.value.ready); assertFalse(AudioEngine.status.value.running)
        val music = wav()
        instrumentation.runOnMainSync { LocalAudio.load(Uri.fromFile(music)); LocalAudio.cancelAnalysis() }
        SystemClock.sleep(500)
        assertFalse(LocalAudio.state.value.analyzing); assertFalse(LocalAudio.state.value.ready)
        analyze(music)
        val data = LocalAudio.track!!
        File(evidence, "local-analysis.txt").writeText("Synthetic original musical fixture; not a real song.\nduration=${data.duration}\nbpm=${data.map.bpm}\nframes=${data.map.frames.size}\nmarkers=${data.map.markers.size}\n")
    }
    @Test fun c_compressedMonoAndShortClipReachTheEnd() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("localCodecFixtures") == "true")
        val aac = File(context.getExternalFilesDir(null), "review/synthetic-128-aac.m4a")
        assertTrue("Explicitly supplied synthetic AAC fixture", aac.exists())
        analyze(aac)
        assertEquals(1, LocalAudio.track!!.channels)
        assertEquals(32.0, LocalAudio.state.value.duration, .1)
        assertEquals(128f, LocalAudio.state.value.bpm, 2f)
        instrumentation.runOnMainSync { LocalAudio.seek(LocalAudio.state.value.duration - .25); LocalAudio.play() }
        waitFor("Compressed track starts", 10000) { LocalAudio.state.value.playing }
        waitFor("Compressed track reaches its end", 10000) { !LocalAudio.state.value.playing }
        assertEquals(LocalAudio.state.value.duration, LocalAudio.state.value.position, .03)
        val short = File(context.getExternalFilesDir(null), "review/short-tone.wav")
        analyze(short)
        instrumentation.runOnMainSync { LocalAudio.play() }
        waitFor("Short clip exits playback", 10000) { !LocalAudio.state.value.playing && LocalAudio.state.value.position > .05 }
        assertFalse(AudioEngine.status.value.running)
    }
    @Test fun d_signedReleaseFileSmoke() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("localRelease") == "true")
        val source = wav()
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "prism-signed-local-fixture.wav"); put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
        }))
        exports.add(uri)
        context.contentResolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out) } }
        device.executeShellCommand("am start -W -n com.caleb.prism/com.caleb.prism.MainActivity")
        fun text(label: String) { refresh(); requireNotNull(device.wait(Until.findObject(By.text(label)), 6000)) { label }.click(); SystemClock.sleep(250) }
        try {
            text("App settings"); text("Audio"); text("Local file"); text("CHOOSE FILE")
            var item = device.wait(Until.findObject(By.text("prism-signed-local-fixture.wav")), 5000)
            if (item == null) {
                device.findObject(By.desc("Show roots"))?.click(); SystemClock.sleep(250)
                device.findObject(By.text("Downloads"))?.click()
                item = device.wait(Until.findObject(By.text("prism-signed-local-fixture.wav")), 5000)
            }
            requireNotNull(item).click()
            waitFor("Signed analysis completes", 120000) { refresh(); device.hasObject(By.desc("Play local playback")) }
            tap("Play local playback")
            waitFor("Signed build uses analyzed timing", 12000) { refresh(); device.hasObject(By.text("Sound received")) }
            device.takeScreenshot(File(evidence, "signed-local-file.png"))
            tap("Pause local playback")
        } finally { device.executeShellCommand("am force-stop com.caleb.prism") }
    }

}
