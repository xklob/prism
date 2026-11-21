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
    private fun launch(scene: Scene=Scene.KALEIDO, audioEnabled: Boolean=false) {
        Configurator.getInstance().waitForIdleTimeout = 300
        AudioEngine.stop()
        context.getSharedPreferences("prism",0).edit().clear().commit()
        SettingsStore(context).save(VisualSettings.defaults(scene).copy(audioEnabled=audioEnabled))
        device.wakeUp(); device.setOrientationNatural()
        activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        assertTrue(device.wait(Until.hasObject(By.text("prism")),10000))
        SystemClock.sleep(900); refresh()
    }
    @After fun cleanup() {
        device.setOrientationNatural()
        instrumentation.runOnMainSync { activity?.finish() }
        AudioEngine.stop(); device.unfreezeRotation()
    }
    @Test fun tabletPanelAndMonitorRemainReachableAcrossRotationAndFullscreen() {
        Assume.assumeTrue(context.resources.configuration.smallestScreenWidthDp>=600)
        launch(audioEnabled = true)
        val monitor = "Tablet song and timing monitor"
        assertFalse(device.hasObject(By.desc(monitor)))
        assertTrue(device.hasObject(By.desc("Expand timing")))
        for (scene in Scene.entries) assertTrue(device.hasObject(By.desc("Pattern ${scene.title}")))
        assertTrue(device.takeScreenshot(File(evidence, "tablet-home-compact.png")))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        click("Connect audio")
        assertTrue(device.wait(Until.hasObject(By.desc(monitor)), 10000))
        click("Tune")
        val panel=device.findObject(By.desc("Tuning panel")).visibleBounds
        val hud=device.findObject(By.desc(monitor)).visibleBounds
        assertFalse("Settings and status must not overlap",android.graphics.Rect.intersects(panel,hud))
        assertTrue("Landscape panel has useful slider width",panel.width()>device.displayWidth*.3)
        for (tab in listOf("Mirrors","Color","Motion","Beat")) {
            click(tab)
            assertTrue("Done stays visible in $tab",device.hasObject(By.text("Done")))
        }
        click("App settings"); click("Display")
        assertTrue(device.takeScreenshot(File(evidence,"tablet-display.png")))
        device.findObject(By.desc("Tablet monitor")).click(); SystemClock.sleep(250); refresh()
        assertFalse(device.hasObject(By.desc(monitor)))
        assertFalse(SettingsStore(context).load().tabletMonitor)
        click("Done"); click("Immerse")
        device.wait(Until.findObject(By.text("Got it")),1000)?.click()
        SystemClock.sleep(1000); refresh()
        assertFalse(device.hasObject(By.desc(monitor)))
        device.pressBack(); SystemClock.sleep(400); click("App settings"); click("Display")
        device.findObject(By.desc("Tablet monitor")).click(); SystemClock.sleep(300)
        click("Kaleido settings")
        assertTrue(device.takeScreenshot(File(evidence,"tablet-settings-landscape.png")))
        device.setOrientationLeft(); SystemClock.sleep(900); refresh()
        assertTrue(device.hasObject(By.text("Done")))
        assertFalse("Portrait tablet panels must also remain separate",android.graphics.Rect.intersects(
            device.findObject(By.desc("Tuning panel")).visibleBounds,device.findObject(By.desc(monitor)).visibleBounds))
        assertTrue(device.takeScreenshot(File(evidence,"tablet-settings-portrait.png")))
        device.setOrientationNatural(); SystemClock.sleep(500); device.findObject(By.desc("Minimize settings")).click(); SystemClock.sleep(300); refresh()
        assertTrue(device.hasObject(By.desc(monitor)))
        device.findObject(By.desc("Expand settings")).click(); SystemClock.sleep(300); refresh(); click("Done"); click("Immerse")
        refresh()
        assertTrue(device.wait(Until.gone(By.text("Immerse")), 3000))
        for (label in listOf(monitor, "Record session", "Manual timing panel", "Expand timing", "System playback controls", "Track waveform panel"))
            assertFalse("Hidden in Immerse: $label", device.hasObject(By.desc(label)))
        assertFalse(device.hasObject(By.descStartsWith("Current frame rate")))
        assertFalse(device.hasObject(By.text("Tap anywhere to return")))
        assertTrue(device.hasObject(By.descContains(" visualizer.").clickable(true)))
        assertTrue(AudioEngine.status.value.running)
        assertTrue(device.takeScreenshot(File(evidence, "tablet-immerse.png")))
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        assertTrue(device.wait(Until.hasObject(By.text("Immerse")), 4000))
        assertTrue(device.hasObject(By.desc(monitor)))
    }
    @Test fun kaleidoscopeControlsSaveRecallAndNewSceneIdsPersist() {
        launch(Scene.KALEIDOSCOPE, audioEnabled=true)
        assertEquals(Scene.KALEIDOSCOPE,SettingsStore(context).load().scene)
        click("Tune")
        assertTrue(device.hasObject(By.desc("Fractal detail")))
        if (context.resources.configuration.smallestScreenWidthDp>=600) {
            device.findObject(By.desc("Expand timing")).click(); SystemClock.sleep(300); refresh()
            val sidebar=device.findObject(By.desc("Manual timing panel")).visibleBounds
            assertFalse(android.graphics.Rect.intersects(sidebar,device.findObject(By.desc("Tuning panel")).visibleBounds))
            assertFalse(device.hasObject(By.desc("Tablet song and timing monitor")))
            assertTrue(device.hasObject(By.text("Beat starts here")))
            assertTrue(device.hasObject(By.text("Phrase starts here")))
            assertTrue(device.takeScreenshot(File(evidence,"tablet-equalizer-timing.png")))
        }
        // Exercise persisted settings through the same store used by the running activity.
        val store=SettingsStore(context)
        val look=VisualSettings.defaults(Scene.KALEIDOSCOPE).copy(kaleidoscopeShape=2,kaleidoscopeSpace=1.4f)
        store.saveLook(look)
        assertEquals(look.copy(audioEnabled = true), store.loadLook(Scene.KALEIDOSCOPE, look.source))
        store.save(VisualSettings.defaults(Scene.MANDELBROT))
        assertEquals(Scene.MANDELBROT,store.load().scene)
    }
}
