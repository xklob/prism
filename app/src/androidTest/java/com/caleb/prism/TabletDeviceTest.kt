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
class TabletDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private var activity: MainActivity?=null
    private val evidence=File(context.getExternalFilesDir(null),"review").apply { mkdirs() }
    private fun refresh() { if (android.os.Build.VERSION.SDK_INT>=33) instrumentation.uiAutomation.clearCache() }
    private fun click(text: String) {
        refresh()
        val node=device.wait(Until.findObject(By.text(text)),5000)
        assertNotNull("Visible action: $text",node)
        node.click(); SystemClock.sleep(400); refresh()
    }
    private fun launch(scene: Scene=Scene.AURORA) {
        context.getSharedPreferences("prism",0).edit().clear().putInt("scene",scene.id).commit()
        device.wakeUp(); device.setOrientationNatural()
        activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        assertTrue(device.wait(Until.hasObject(By.text("PRISM")),10000))
        SystemClock.sleep(900); refresh()
    }
    @After fun cleanup() {
        device.setOrientationNatural()
        instrumentation.runOnMainSync { activity?.finish() }
    }
    @Test fun tabletPanelAndMonitorRemainReachableAcrossRotationAndFullscreen() {
        Assume.assumeTrue(context.resources.configuration.smallestScreenWidthDp>=600)
        launch()
        val monitor="Tablet song and timing monitor"
        assertTrue(device.hasObject(By.desc(monitor)))
        click("Tune")
        val panel=device.findObject(By.desc("Tuning panel")).visibleBounds
        val hud=device.findObject(By.desc(monitor)).visibleBounds
        assertFalse("Settings and status must not overlap",android.graphics.Rect.intersects(panel,hud))
        assertTrue("Landscape panel has useful slider width",panel.width()>device.displayWidth*.3)
        for (tab in listOf("Geometry","Color","Motion","Audio","Song","Display")) {
            click(tab)
            assertTrue("Done stays visible in $tab",device.hasObject(By.text("Done")))
        }
        assertTrue(device.takeScreenshot(File(evidence,"tablet-display.png")))
        device.findObject(By.desc("Tablet monitor")).click(); SystemClock.sleep(250); refresh()
        assertFalse(device.hasObject(By.desc(monitor)))
        assertFalse(SettingsStore(context).load().tabletMonitor)
        click("Done"); click("Immerse")
        device.wait(Until.findObject(By.text("Got it")),1000)?.click()
        SystemClock.sleep(1000); refresh()
        assertFalse(device.hasObject(By.desc(monitor)))
        device.pressBack(); SystemClock.sleep(400); click("Tune"); click("Display")
        device.findObject(By.desc("Tablet monitor")).click(); SystemClock.sleep(300)
        click("Geometry")
        assertTrue(device.takeScreenshot(File(evidence,"tablet-settings-landscape.png")))
        device.setOrientationLeft(); SystemClock.sleep(900); refresh()
        assertTrue(device.hasObject(By.text("Done")))
        assertFalse("Portrait tablet panels must also remain separate",android.graphics.Rect.intersects(
            device.findObject(By.desc("Tuning panel")).visibleBounds,device.findObject(By.desc(monitor)).visibleBounds))
        assertTrue(device.takeScreenshot(File(evidence,"tablet-settings-portrait.png")))
        device.setOrientationNatural(); SystemClock.sleep(500); click("Hide")
        assertTrue(device.hasObject(By.desc(monitor)))
        click("Show controls"); click("Done"); click("Immerse")
        SystemClock.sleep(2800); refresh()
        assertTrue(device.hasObject(By.desc(monitor)))
        assertTrue(device.hasObject(By.descStartsWith("Beat confidence")))
        assertTrue(device.hasObject(By.descStartsWith("Bar confidence")))
        assertTrue(device.hasObject(By.descStartsWith("Phrase confidence")))
        assertTrue(device.takeScreenshot(File(evidence,"tablet-fullscreen-monitor.png")))
    }
    @Test fun equalizerControlsSaveRecallAndNewSceneIdsPersist() {
        launch(Scene.EQUALIZER)
        assertEquals(Scene.EQUALIZER,SettingsStore(context).load().scene)
        click("Tune")
        assertTrue(device.hasObject(By.desc("Frequency bands")))
        if (context.resources.configuration.smallestScreenWidthDp>=600) {
            val sidebar=device.findObject(By.desc("Manual timing panel")).visibleBounds
            assertFalse(android.graphics.Rect.intersects(sidebar,device.findObject(By.desc("Tuning panel")).visibleBounds))
            assertFalse(android.graphics.Rect.intersects(sidebar,device.findObject(By.desc("Tablet song and timing monitor")).visibleBounds))
            assertTrue(device.hasObject(By.text("Beat starts here")))
            assertTrue(device.hasObject(By.text("Phrase starts here")))
            assertTrue(device.takeScreenshot(File(evidence,"tablet-equalizer-timing.png")))
        }
        // Exercise persisted settings through the same store used by the running activity.
        val store=SettingsStore(context)
        val look=VisualSettings.defaults(Scene.EQUALIZER).copy(eqBuckets=48,eqMirror=true,eqRadial=true,
            eqSegments=false,eqPeakHold=1.4f,eqFalloff=.8f,eqSpacing=.4f,eqGain=2f)
        store.saveLook(look)
        assertEquals(look,store.loadLook(Scene.EQUALIZER,look.source))
        store.save(VisualSettings.defaults(Scene.MANDELBROT))
        assertEquals(Scene.MANDELBROT,store.load().scene)
    }
}
