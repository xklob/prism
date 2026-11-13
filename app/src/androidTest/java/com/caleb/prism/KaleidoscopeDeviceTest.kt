package com.caleb.prism

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KaleidoscopeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
    private var activity: MainActivity? = null

    @After fun cleanup() {
        instrumentation.runOnMainSync { activity?.finish() }
        device.unfreezeRotation()
    }

    @Test fun fractalRendersAndRespondsToEachFrequencyRange() {
        ShaderProbe(context, evidence, 320, 200).use {
            it.verifyKaleidoscope()
            it.verifyResponse(Scene.KALEIDOSCOPE, MusicalMotion(beat = .8f), "beat")
            it.verifyDownbeatColors(Scene.KALEIDOSCOPE)
        }
    }

    @Test fun tuningPersistsAndRecordControlStaysReachable() {
        Configurator.getInstance().waitForIdleTimeout = 300
        val store = SettingsStore(context)
        store.save(VisualSettings.defaults(Scene.KALEIDOSCOPE))
        device.wakeUp(); device.setOrientationNatural()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        assertTrue(device.wait(Until.hasObject(By.desc("Record session")), 10000))
        SystemClock.sleep(1200)
        fun refresh() { if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache() }
        fun click(label: String) {
            refresh()
            val node = device.wait(Until.findObject(By.text(label)), 6000)
            assertNotNull(label, node); node.click(); SystemClock.sleep(300); refresh()
        }
        click("Tune")
        val record = device.findObject(By.desc("Record session")).visibleBounds
        val panel = device.findObject(By.desc("Tuning panel")).visibleBounds
        val fps = device.findObject(By.descStartsWith("Current frame rate")).visibleBounds
        assertFalse("Record must not cover settings", Rect.intersects(record, panel))
        assertFalse("FPS must not cover settings", Rect.intersects(fps, panel))
        assertFalse("FPS and recording stay separate", Rect.intersects(fps, record))
        assertTrue("Record belongs at top right", record.centerX() > device.displayWidth * .65)
        assertTrue("Record stays near the top", record.bottom < device.displayHeight * .25)
        assertTrue(device.hasObject(By.text("Silent video")))
        click("Tunnel")
        assertEquals(2, store.load().kaleidoscopeShape)
        val tuned = store.load().copy(kaleidoscopeSpace = 1.7f, lineWidth = 1.8f)
        store.saveLook(tuned)
        assertEquals(tuned, store.loadLook(Scene.KALEIDOSCOPE, tuned.source))
        assertEquals(9, store.load().scene.id)
        device.takeScreenshot(File(evidence, "kaleidoscope-tuning-${device.displayWidth}.png"))
        click("Done"); click("Immerse")
        device.wait(Until.findObject(By.text("Got it")), 1000)?.click()
        SystemClock.sleep(2800); refresh()
        assertTrue("Recording remains accessible in fullscreen", device.hasObject(By.desc("Record session")))
        assertTrue("Measured FPS remains visible in fullscreen", device.hasObject(By.descStartsWith("Current frame rate")))
        device.takeScreenshot(File(evidence, "kaleidoscope-fullscreen-${device.displayWidth}.png"))
    }
}
