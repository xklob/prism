package com.caleb.prism

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.media.*
import android.os.SystemClock
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.regex.Pattern
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PrismDeviceTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null
    private val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }

    private fun launch(scene: Scene = Scene.AURORA, source: AudioSource = AudioSource.MICROPHONE) {
        context.getSharedPreferences("prism", 0).edit().clear().putInt("scene", scene.ordinal).putInt("source", source.ordinal).commit()
        device.wakeUp()
        device.setOrientationNatural()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        assertTrue(device.wait(Until.hasObject(By.text("PRISM")), 10000))
        SystemClock.sleep(1500)
    }

    private fun click(text: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val target = device.wait(Until.findObject(By.text(text)), 6000)
        assertNotNull("Expected visible action: $text", target)
        target.click()
        SystemClock.sleep(500)
        if (text == "Immerse") {
            device.wait(Until.findObject(By.text("Got it")), 1200)?.click()
            SystemClock.sleep(300)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
    }

    private fun screenshot(name: String) {
        assertTrue(device.takeScreenshot(File(evidence, "$name.png")))
    }

    @After fun cleanup() {
        device.setOrientationNatural()
        instrumentation.runOnMainSync { activity?.finish() }
        SystemClock.sleep(400)
    }

    @Test fun a_ambientControlsAndPersistence() {
        launch()
        assertFalse(AudioEngine.status.value.running)
        screenshot("01-aurora")
        click("Kaleido")
        SystemClock.sleep(1000)
        screenshot("02-kaleido")
        click("Wormhole")
        SystemClock.sleep(1000)
        screenshot("03-wormhole")
        click("Tune")
        click("Color")
        click("Tidal")
        screenshot("04-tuning")
        click("Done")
        assertEquals(Palette.TIDAL, SettingsStore(context).load().palette)
        assertEquals(Scene.WORMHOLE, SettingsStore(context).load().scene)
        click("Immerse")
        SystemClock.sleep(2800)
        assertFalse(device.hasObject(By.text("PRISM")))
        screenshot("05-immersive")
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        assertTrue(device.wait(Until.hasObject(By.text("PRISM")), 5000))
        // Let transient system bars and the entrance animation settle before reading bounds.
        SystemClock.sleep(700)
        device.findObject(By.desc("Pause")).click()
        SystemClock.sleep(300)
        // Android 16 can retain the pre-immersive accessibility snapshot after bars return.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        assertTrue(device.wait(Until.hasObject(By.text("PAUSED")), 5000))
        assertFalse(AudioEngine.status.value.running)
        device.findObject(By.desc("Play")).click()
        SystemClock.sleep(300)
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        assertTrue(device.wait(Until.hasObject(By.text("AMBIENT")), 5000))
    }

    @Test fun b_systemAudioCapturesRealPlaybackAndStops() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        launch(Scene.PULSE, AudioSource.SYSTEM)
        click("Connect audio")
        val consent = device.wait(Until.findObject(By.res("android:id/button1")), 5000)
            ?: device.wait(Until.findObject(By.text(Pattern.compile("Start (now|recording|sharing)|Share screen"))), 5000)
        screenshot("06-system-consent")
        assertNotNull("Android must display capture consent", consent)
        consent.click()
        val timeout = SystemClock.elapsedRealtime() + 10000
        while (!AudioEngine.status.value.running && SystemClock.elapsedRealtime() < timeout) SystemClock.sleep(100)
        assertTrue("System capture must be running: ${AudioEngine.status.value}", AudioEngine.status.value.running)
        assertEquals(AudioSource.SYSTEM, AudioEngine.status.value.source)

        val sampleRate = 48000
        val pcm = ShortArray(sampleRate) { i ->
            (Short.MAX_VALUE * (0.4 * sin(2*PI*96*i/sampleRate) + 0.12*sin(2*PI*1000*i/sampleRate) + 0.08*sin(2*PI*6000*i/sampleRate))).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        try {
            assertEquals(pcm.size, track.write(pcm, 0, pcm.size))
            assertEquals(AudioTrack.SUCCESS, track.setLoopPoints(0, pcm.size, -1))
            track.play()
            SystemClock.sleep(2500)
            val levels = AudioEngine.levels
            File(evidence, "system-audio-levels.txt").writeText("source=${AudioEngine.status.value.source}\nbass=${levels.bass}\nmid=${levels.mid}\nhigh=${levels.high}\nenergy=${levels.energy}\n")
            assertTrue("Playback PCM must drive bass, got ${levels.bass}", levels.bass > 0.2f)
            assertTrue("Playback PCM must drive midrange, got ${levels.mid}", levels.mid > 0.05f)
            assertTrue("Playback PCM must drive treble, got ${levels.high}", levels.high > 0.02f)
            screenshot("07-pulse-system-live")
            click("Strings")
            SystemClock.sleep(1200)
            screenshot("08-strings-system-live")
            click("Nova")
            SystemClock.sleep(1200)
            screenshot("09-nova-system-live")
            track.pause()
            SystemClock.sleep(3000)
            assertTrue("Silence must decay", AudioEngine.levels.energy < 0.02f)
            click("Stop")
            assertFalse(AudioEngine.status.value.running)
            assertEquals(0f, AudioEngine.levels.energy, 0f)
            assertFalse("Foreground service must release the projection", context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100).any { it.service.className.endsWith("PlaybackCaptureService") })
        } finally { track.release() }
    }

    @Test fun c_microphoneLifecycleAndAmbientIsolation() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        launch(Scene.PULSE)
        click("Connect audio")
        assertTrue(AudioEngine.status.value.running)
        assertEquals(AudioSource.MICROPHONE, AudioEngine.status.value.source)
        device.pressHome()
        SystemClock.sleep(1000)
        assertFalse("Microphone must stop in background", AudioEngine.status.value.running)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        assertTrue(device.wait(Until.hasObject(By.text("PRISM")), 5000))
        SystemClock.sleep(800)
        assertTrue("Microphone should resume the authorized session", AudioEngine.status.value.running)
        click("Ambient")
        click("Aurora")
        assertFalse("Ambient scenes must not capture audio", AudioEngine.status.value.running)
        screenshot("10-ambient-no-audio")
        device.setOrientationLeft()
        SystemClock.sleep(1200)
        screenshot("11-landscape")
        device.setOrientationNatural()
    }

    @Test fun d_cancelledCaptureDoesNotStartListening() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        launch(Scene.PULSE, AudioSource.SYSTEM)
        click("Connect audio")
        assertTrue(device.wait(Until.hasObject(By.res("android:id/button1")), 5000))
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text("Connect audio")), 5000))
        assertFalse("Declining consent must not capture", AudioEngine.status.value.running)
        screenshot("12-capture-cancelled")
    }

    private fun refreshTree() {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
    }

    private fun adjust(label: String, fraction: Float) {
        refreshTree()
        val slider = device.wait(Until.findObject(By.desc(label)), 5000)
        assertNotNull("Missing slider: $label", slider)
        val bounds = slider.visibleBounds
        val y = bounds.centerY()
        device.swipe(bounds.centerX(), y, bounds.left + (bounds.width() * fraction).toInt(), y, 20)
        SystemClock.sleep(450)
        refreshTree()
    }

    private fun difference(a: Bitmap, b: Bitmap): Double {
        var total = 0.0
        var samples = 0
        // This region is above the tuning controls and below both headers.
        for (y in (a.height * 0.29).toInt() until (a.height * 0.40).toInt() step 3) {
            for (x in (a.width * 0.15).toInt() until (a.width * 0.85).toInt() step 3) {
                val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                for (shift in listOf(0, 8, 16)) total += abs(((p shr shift) and 255) - ((q shr shift) and 255))
                samples += 3
            }
        }
        return total / samples / 255.0
    }

    @Test fun e_transparentOverlayAndLiveGeometry() {
        launch(Scene.KALEIDO)
        device.findObject(By.desc("Pause")).click()
        SystemClock.sleep(600)
        refreshTree()
        assertTrue(device.hasObject(By.text("PAUSED")))
        val original = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        click("Tune")
        SystemClock.sleep(500)
        val overlay = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val overlayDifference = difference(original, overlay)
        assertTrue("Transparent controls must not dim, resize, or reframe the visual: $overlayDifference", overlayDifference < 0.005)
        screenshot("13-transparent-overlay")
        adjust("Recursion depth", 0.14f)
        val changed = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val changeDifference = difference(overlay, changed)
        assertTrue("Geometry changes must reach the visible shader while tuning: $changeDifference", changeDifference > 0.015)
        val configured = SettingsStore(context).load()
        assertTrue(configured.complexity < 0.3f)
        click("Save")
        adjust("Recursion depth", 0.94f)
        assertTrue(SettingsStore(context).load().complexity > 0.8f)
        click("Recall")
        assertEquals(configured.complexity, SettingsStore(context).load().complexity, 0.01f)
        click("Hide")
        assertTrue(device.wait(Until.hasObject(By.text("Show controls")), 5000))
        screenshot("14-overlay-hidden")
        click("Show controls")
        click("Color")
        click("Tidal")
        adjust("Hue shift", 0.7f)
        screenshot("15-color-overlay")
        val configuredHue = SettingsStore(context).load().hue
        click("Done")
        click("Aurora")
        click("Kaleido")
        assertEquals("Every scene keeps its own tuning", configured.complexity, SettingsStore(context).load().complexity, 0.01f)
        assertEquals(configuredHue, SettingsStore(context).load().hue, 0.01f)
        File(evidence, "overlay-validation.txt").writeText("overlay_pixel_difference=$overlayDifference\nlive_geometry_pixel_difference=$changeDifference\nper_scene_tuning=true\nsave_recall=true\n")
        original.recycle(); overlay.recycle(); changed.recycle()
    }

    @Test fun f_juliaFractalAndLandscapeOverlay() {
        launch(Scene.JULIA)
        click("Immerse")
        SystemClock.sleep(3000)
        screenshot("16-julia-fractal")
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        SystemClock.sleep(800)
        refreshTree()
        click("Tune")
        assertTrue(device.hasObject(By.desc("Fractal iterations")))
        device.setOrientationLeft()
        SystemClock.sleep(1200)
        screenshot("17-landscape-overlay")
        refreshTree()
        assertTrue(device.hasObject(By.text("Done")))
        device.setOrientationNatural()
    }
}
