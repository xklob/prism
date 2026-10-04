package com.caleb.prism

import android.Manifest
import android.content.Intent
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class RecordingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null
    private val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
    private val created = mutableListOf<Uri>()

    @Before fun launch() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        context.getSharedPreferences("prism", 0).edit().clear().commit()
        context.getSharedPreferences("recordings", 0).edit().clear().commit()
        device.wakeUp(); device.setOrientationNatural()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        assertTrue(device.wait(Until.hasObject(By.text("Record session")), 10000))
        SystemClock.sleep(1000)
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { activity?.finish() }
        SystemClock.sleep(1200)
        AudioEngine.stop()
        created.forEach { context.contentResolver.delete(it, null, null) }
        device.setOrientationNatural(); device.unfreezeRotation()
    }

    private fun click(text: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val control = device.wait(Until.findObject(By.text(text)), 8000)
        assertNotNull("Expected action: $text", control)
        control.click()
        SystemClock.sleep(350)
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
    }

    private fun start(source: AudioSource) {
        click("Record session")
        click(source.label)
        click("Start recording")
        if (source == AudioSource.SYSTEM) {
            val consent = device.wait(Until.findObject(By.res("android:id/button1")), 6000)
            assertNotNull("Playback capture must ask for Android consent", consent)
            consent.click()
        }
        assertTrue("Recorder must start", device.wait(Until.hasObject(By.text("Stop recording")), 12000))
    }

    private fun awaitSaved(previous: String? = null): Uri {
        val prefs = context.getSharedPreferences("recordings", 0)
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (prefs.getString("uri", null) == previous && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        val saved = prefs.getString("uri", null)
        assertNotNull("A finished recording must be saved", saved)
        assertNotEquals(previous, saved)
        return Uri.parse(saved).also { created.add(it) }
    }

    @Test fun systemAudioRecordsStereoWithAmbientVisualsAndLiveChanges() {
        val manager = context.getSystemService(AudioManager::class.java)
        val oldVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        val samples = ShortArray(48000 * 2 * 2)
        for (i in 0 until samples.size / 2) {
            samples[2 * i] = (sin(2 * PI * 440 * i / 48000) * 8000).toInt().toShort()
            samples[2 * i + 1] = (sin(2 * PI * 880 * i / 48000) * 8000).toInt().toShort()
        }
        val playback = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
        try {
            assertEquals(samples.size, playback.write(samples, 0, samples.size))
            assertEquals(AudioTrack.SUCCESS, playback.setLoopPoints(0, samples.size / 2, -1))
            playback.play()
            start(AudioSource.SYSTEM)
            assertFalse("Ambient visuals can record audio", SettingsStore(context).load().audioEnabled)
            assertEquals(2, AudioEngine.status.value.channels)
            SystemClock.sleep(2500)
            val label = device.findObject(By.text("Immerse"))!!.visibleBounds
            val screen = instrumentation.uiAutomation.takeScreenshot()
            try {
                var readable = 0
                for (y in label.top until label.bottom) for (x in label.left until label.right) {
                    val pixel = screen.getPixel(x, y)
                    if ((pixel shr 16 and 255) > 220 && (pixel shr 8 and 255) > 220 && (pixel and 255) > 220) readable++
                }
                assertTrue("The fullscreen label must remain visibly readable while encoding", readable > 80)
            } finally { screen.recycle() }
            device.takeScreenshot(File(evidence, "recording-controls.png"))
            click("Kaleido")
            assertTrue("Changing to another ambient pattern keeps recording audio", AudioEngine.status.value.running)
            click("Tune")
            assertTrue(device.hasObject(By.text("Stop recording")))
            click("Color")
            val slider = device.findObject(By.desc("Hue shift"))!!
            val r = slider.visibleBounds
            device.swipe(r.centerX(), r.centerY(), r.right - 8, r.centerY(), 12)
            SystemClock.sleep(1500)
            device.takeScreenshot(File(evidence, "recording-overlay.png"))
            click("Done")
            click("Immerse")
            device.wait(Until.findObject(By.text("Got it")), 700)?.click()
            assertTrue("Stop remains available in immersive mode", device.hasObject(By.text("Stop recording")))
            SystemClock.sleep(1500)
            click("Stop recording")
            val uri = awaitSaved()
            verify(uri, 2, "system-session.mp4")
            verifyStereoTones(File(evidence, "system-session.mp4"))
            assertFalse("Recording-only capture is released", AudioEngine.status.value.running)
            assertNull(AudioEngine.pcmSink)
        } finally {
            playback.release()
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0)
        }
    }

    @Test fun microphoneRecordingSavesOnBackgroundAndCanRecordAgain() {
        start(AudioSource.MICROPHONE)
        assertEquals(AudioSource.MICROPHONE, AudioEngine.status.value.source)
        SystemClock.sleep(2500)
        device.pressHome()
        val first = awaitSaved()
        verify(first, 1, "microphone-session.mp4")
        assertFalse(AudioEngine.status.value.running)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        assertTrue(device.wait(Until.hasObject(By.text("Record session")), 5000))
        start(AudioSource.MICROPHONE)
        SystemClock.sleep(2000)
        click("Stop recording")
        val second = awaitSaved(first.toString())
        verify(second, 1, "microphone-second-session.mp4")
        assertTrue(device.wait(Until.hasObject(By.text("Last video")), 4000))
        click("Share")
        assertTrue("Android share sheet must open", device.wait(Until.hasObject(By.pkg("com.android.intentresolver")), 5000))
        device.pressBack()
    }

    @Test fun canceledPlaybackConsentDoesNotLeaveARecorderOrAudioRunning() {
        click("Record session"); click(AudioSource.SYSTEM.label); click("Start recording")
        assertNotNull(device.wait(Until.findObject(By.res("android:id/button1")), 5000))
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text("Record session")), 5000))
        SystemClock.sleep(500)
        assertFalse(AudioEngine.status.value.running)
        assertNull(AudioEngine.pcmSink)
        assertNull(context.getSharedPreferences("recordings", 0).getString("uri", null))
        start(AudioSource.MICROPHONE)
        SystemClock.sleep(1000)
        click("Stop recording")
        verify(awaitSaved(), 1, "after-cancel-session.mp4")
    }

    @Test fun audioReactivityAndPauseCanChangeWithoutInterruptingRecording() {
        device.findObject(By.desc("React to audio"))!!.click()
        start(AudioSource.MICROPHONE)
        SystemClock.sleep(1200)
        device.findObject(By.desc("Pause"))!!.click()
        SystemClock.sleep(500)
        assertTrue("Pause freezes visuals but keeps recorded audio", AudioEngine.status.value.running)
        device.findObject(By.desc("Play"))!!.click()
        device.findObject(By.desc("React to audio"))!!.click()
        SystemClock.sleep(400)
        assertFalse(SettingsStore(context).load().audioEnabled)
        assertTrue("Turning reactivity off keeps recorded audio", AudioEngine.status.value.running)
        device.findObject(By.desc("React to audio"))!!.click()
        SystemClock.sleep(400)
        click("Stop recording")
        verify(awaitSaved(), 1, "reactive-session.mp4")
        assertTrue("Reactivity keeps its shared capture after saving", AudioEngine.status.value.running)
        assertNull(AudioEngine.pcmSink)
        click("Stop")
        assertFalse(AudioEngine.status.value.running)
        device.findObject(By.desc("Pause"))!!.click()
        click("Connect audio")
        assertTrue("Normal Connect still resumes a paused reactive scene", AudioEngine.status.value.running)
        assertTrue("Connecting audio resumes visual playback", device.wait(Until.hasObject(By.desc("Pause")), 5000))
        click("Stop")
    }

    @Test fun losingPlaybackCaptureFinalizesTheVideo() {
        start(AudioSource.SYSTEM)
        SystemClock.sleep(1500)
        context.startService(Intent(context, PlaybackCaptureService::class.java).setAction("STOP"))
        verify(awaitSaved(), 2, "interrupted-session.mp4")
        assertFalse(AudioEngine.status.value.running)
        assertNull(AudioEngine.pcmSink)
        assertTrue(device.wait(Until.hasObject(By.text("Record session")), 5000))
    }

    private fun verify(uri: Uri, channels: Int, filename: String) {
        context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.RELATIVE_PATH), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
            assertEquals("Movies/Prism/", it.getString(1))
        }
        val file = File(evidence, filename)
        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals(2, extractor.trackCount)
            var audioFound = false
            var videoFound = false
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                if (mime.startsWith("audio")) {
                    assertEquals("audio/mp4a-latm", mime)
                    assertEquals(channels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    assertEquals(48000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    audioFound = true
                } else {
                    assertEquals("video/avc", mime)
                    assertTrue(format.getInteger(MediaFormat.KEY_WIDTH) > 0)
                    assertTrue(format.getInteger(MediaFormat.KEY_HEIGHT) > format.getInteger(MediaFormat.KEY_WIDTH))
                    videoFound = true
                }
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var previous = -1L
                var count = 0
                val buffer = ByteBuffer.allocate(4_000_000)
                while (extractor.readSampleData(buffer, 0) >= 0) {
                    assertTrue("Track timestamps must increase", extractor.sampleTime > previous)
                    previous = extractor.sampleTime
                    count++
                    if (!extractor.advance()) break
                }
                assertTrue("Both tracks need real samples", count > 10)
                assertTrue("Both tracks need real duration", previous > 500_000)
                extractor.unselectTrack(track)
            }
            assertTrue(audioFound && videoFound)
        } finally { extractor.release() }
        MediaMetadataRetriever().use { media ->
            media.setDataSource(file.absolutePath)
            val first = media.getFrameAtTime(200_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val later = media.getFrameAtTime(800_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            var bright = 0
            var different = 0
            for (y in 0 until first.height step 16) for (x in 0 until first.width step 16) {
                val pixel = first.getPixel(x, y)
                if (pixel and 0xffffff != 0) bright++
                if (pixel != later.getPixel(x, y)) different++
            }
            assertTrue("Encoded video must contain the visualizer", bright > 100)
            assertTrue("Encoded video must animate", different > 100)
            first.recycle(); later.recycle()
        }
    }

    /** Decode the exported AAC, not the pre-encoder PCM, and check both actual signals. */
    private fun verifyStereoTones(file: File) {
        val extractor = MediaExtractor()
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val sums = Array(2) { DoubleArray(5) } // energy, sin/cos 440 Hz, sin/cos 880 Hz
        var frames = 0
        var measured = 0
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            extractor.selectTrack(track)
            decoder.configure(extractor.getTrackFormat(track), null, null, 0)
            decoder.start()
            var inputEnded = false
            val deadline = SystemClock.elapsedRealtime() + 10000
            val info = MediaCodec.BufferInfo()
            while (measured < 48000 && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val input = decoder.dequeueInputBuffer(0)
                    if (input >= 0) {
                        val bytes = extractor.readSampleData(decoder.getInputBuffer(input)!!, 0)
                        if (bytes < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(input, 0, bytes, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10000)
                if (output >= 0) {
                    try {
                        val bytes = decoder.getOutputBuffer(output)!!.order(ByteOrder.LITTLE_ENDIAN)
                        bytes.position(info.offset); bytes.limit(info.offset + info.size)
                        val pcm = bytes.asShortBuffer()
                        while (pcm.remaining() >= 2) {
                            val left = pcm.get() / 32768.0
                            val right = pcm.get() / 32768.0
                            if (frames >= 48000 && measured < 48000) {
                                val a = 2 * PI * 440 * measured / 48000
                                val b = 2 * PI * 880 * measured / 48000
                                for (channel in 0..1) {
                                    val value = if (channel == 0) left else right
                                    sums[channel][0] += value * value
                                    sums[channel][1] += value * sin(a); sums[channel][2] += value * cos(a)
                                    sums[channel][3] += value * sin(b); sums[channel][4] += value * cos(b)
                                }
                                measured++
                            }
                            frames++
                        }
                    } finally { decoder.releaseOutputBuffer(output, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = decoder.outputFormat
                    assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) assertEquals(AudioFormat.ENCODING_PCM_16BIT, format.getInteger(MediaFormat.KEY_PCM_ENCODING))
                }
            }
            assertEquals(48000, measured)
            for (channel in 0..1) {
                val values = sums[channel]
                assertTrue("Recorded channel must contain real sound", sqrt(values[0] / measured) > .05)
                val power440 = values[1].pow(2) + values[2].pow(2)
                val power880 = values[3].pow(2) + values[4].pow(2)
                val separation = if (channel == 0) power440 / power880 else power880 / power440
                assertTrue("Distinct left/right tones must survive encoding, with >20 dB separation", separation > 100)
            }
        } finally {
            runCatching { decoder.stop() }; decoder.release(); extractor.release()
        }
    }
}
