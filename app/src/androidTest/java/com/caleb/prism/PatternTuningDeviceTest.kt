package com.caleb.prism

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PatternTuningDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null
    private fun refresh() { if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache() }
    private fun click(label: String) {
        refresh()
        val node = device.wait(Until.findObject(By.text(label)), 5000)
        assertNotNull(label, node); node.click(); SystemClock.sleep(250); refresh()
    }
    private fun launch(scene: Scene) {
        context.getSharedPreferences("prism", 0).edit().clear().commit()
        SettingsStore(context).save(VisualSettings.defaults(scene))
        device.wakeUp(); device.setOrientationNatural()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        SystemClock.sleep(800); refresh(); click("Tune")
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { activity?.finish() }
        device.unfreezeRotation()
    }
    @Test fun eachPatternShowsItsOwnControlsAndSharedSettingsStaySeparate() {
        Configurator.getInstance().waitForIdleTimeout = 300
        for ((scene, firstControl) in listOf(
            Scene.AURORA to "Contour density", Scene.KALEIDO to "Recursion depth", Scene.WORMHOLE to "Ring density",
            Scene.JULIA to "Fractal iterations", Scene.MANDELBROT to "Fractal iterations",
            Scene.EQUALIZER to "Frequency bands", Scene.KALEIDOSCOPE to "Fractal detail"
        )) {
            launch(scene)
            assertTrue("$scene geometry", device.hasObject(By.desc(firstControl)))
            val beforeHelp = SettingsStore(context).load()
            device.findObject(By.desc("About $firstControl")).click(); SystemClock.sleep(350); refresh()
            val help = device.findObject(By.desc("$firstControl help"))
            assertNotNull("Each pattern's controls explain their effect", help)
            assertTrue(help.visibleBounds.left >= 0 && help.visibleBounds.right <= device.displayWidth)
            val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
            device.takeScreenshot(File(evidence, "settings-help-${scene.name}-${device.displayWidth}.png"))
            click("Close")
            assertEquals("Opening help must not change a setting", beforeHelp, SettingsStore(context).load())
            assertFalse("Song detection belongs in app settings", device.hasObject(By.text("Song")))
            click(scene.motionTab)
            if (scene == Scene.JULIA) {
                assertTrue(device.hasObject(By.desc("Seed evolution")))
                assertFalse("Julia does not use the travel clock", device.hasObject(By.desc("Travel speed")))
            }
            if (scene == Scene.EQUALIZER) {
                assertTrue(device.hasObject(By.desc("Falloff")))
                assertTrue(device.hasObject(By.desc("Peak hold")))
                assertFalse(device.hasObject(By.desc("Rotation")))
            }
            assertFalse("Battery saver belongs in app settings", device.hasObject(By.desc("Battery saver")))
            click("App settings"); click("Display")
            assertTrue(device.hasObject(By.desc("Battery saver")))
            assertTrue(device.hasObject(By.desc("About Battery saver")))
            assertFalse("Shared settings cannot reset a look", device.hasObject(By.text("Reset")))
            click("${scene.title} settings")
            assertTrue(device.hasObject(By.text(scene.shapeTab)))
            instrumentation.runOnMainSync { activity?.finish() }
            activity = null; SystemClock.sleep(300)
        }
    }
    @Test fun tabletPanelsCollapseIndependentlyAndRestoreTheirControls() {
        Assume.assumeTrue(context.resources.configuration.smallestScreenWidthDp >= 600)
        launch(Scene.EQUALIZER)
        click("Response")
        device.findObject(By.desc("Minimize settings")).click(); SystemClock.sleep(250); refresh()
        assertFalse(device.hasObject(By.desc("Tuning panel")))
        assertTrue(device.hasObject(By.desc("Manual timing panel")))
        device.findObject(By.desc("Minimize timing")).click(); SystemClock.sleep(250); refresh()
        assertFalse(device.hasObject(By.desc("Manual timing panel")))
        assertTrue(device.hasObject(By.desc("Expand settings")))
        assertTrue(device.hasObject(By.desc("Expand timing")))
        assertTrue(device.hasObject(By.desc("Record session")))
        assertTrue(device.hasObject(By.desc("Tablet song and timing monitor")))
        val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
        device.takeScreenshot(File(evidence, "tablet-sidebars-minimized.png"))
        device.findObject(By.desc("Expand settings")).click(); SystemClock.sleep(250); refresh()
        assertTrue("Selected page survives minimize", device.hasObject(By.desc("Falloff")))
        assertFalse(device.hasObject(By.desc("Manual timing panel")))
        device.findObject(By.desc("Expand timing")).click(); SystemClock.sleep(250); refresh()
        assertTrue(device.hasObject(By.desc("Manual timing panel")))
        device.takeScreenshot(File(evidence, "tablet-equalizer-response.png"))
    }
}
