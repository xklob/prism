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
class HomeLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null
    private val compact get() = device.displayWidth > device.displayHeight &&
        device.displayHeight / context.resources.displayMetrics.density < 600
    private val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
    private fun refresh() { if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache() }
    private fun node(description: String): UiObject2 {
        refresh()
        return requireNotNull(device.wait(Until.findObject(By.desc(description)), 6000)) { description }
    }
    private fun text(label: String): UiObject2 {
        refresh()
        return requireNotNull(device.wait(Until.findObject(By.text(label)), 6000)) { label }
    }
    @Before fun launch() {
        Configurator.getInstance().waitForIdleTimeout = 300
        AudioEngine.stop()
        context.getSharedPreferences("prism", 0).edit().clear().commit()
        context.getSharedPreferences("recordings", 0).edit().clear().commit()
        device.executeShellCommand("cmd notification allow_listener ${context.packageName}/${NowPlayingListener::class.java.name}")
        SettingsStore(context).save(VisualSettings.defaults(Scene.KALEIDOSCOPE).copy(effects = PostEffects(enabled = true, bloom = .5f)))
        device.wakeUp(); device.setOrientationNatural()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        text("Tune"); SystemClock.sleep(1000)
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { activity?.finish() }; AudioEngine.stop(); device.unfreezeRotation()
        device.executeShellCommand("cmd notification disallow_listener ${context.packageName}/${NowPlayingListener::class.java.name}")
    }
    private fun hud(): List<Rect> {
        val fps = requireNotNull(device.findObject(By.descStartsWith("Current frame rate")))
        val bounds = listOf(node("System playback controls").visibleBounds, node("Record session").visibleBounds, fps.visibleBounds)
        bounds.forEachIndexed { i, rect ->
            assertTrue(rect.width() > 0 && rect.height() > 0)
            bounds.drop(i+1).forEach { assertFalse("HUD controls overlap: $rect and $it", Rect.intersects(rect, it)) }
        }
        return bounds
    }
    @Test fun homeAndTuningKeepControlsReadableAndSeparate() {
        val panel = node("Pattern controls panel").visibleBounds
        val selected = node("Pattern Kaleidoscope")
        assertTrue("The active pattern card is visible on launch", selected.visibleBounds.width() > 80)
        assertTrue("Selected card stays inside its panel", panel.contains(selected.visibleBounds))
        device.dumpWindowHierarchy(File(evidence, "home-hierarchy.xml"))
        device.takeScreenshot(File(evidence, "home-panel-${device.displayWidth}-${context.resources.configuration.fontScale}.png"))
        // Compose exposes the label as a child of the checkable selection node.
        assertTrue("Selection is exposed to accessibility",
            generateSequence(selected) { it.parent }.take(3).any { it.isSelected || it.isChecked })
        hud().forEach { assertFalse("Home panel overlaps a persistent control", Rect.intersects(panel, it)) }
        for (label in listOf("Kaleidoscope", "Tune", "Immerse") + if (compact) emptyList() else listOf("prism", "App settings", "Join crowd show"))
            assertTrue("$label is backed by the panel", panel.contains(text(label).visibleBounds))
        device.takeScreenshot(File(evidence, "home-panel-${device.displayWidth}-${context.resources.configuration.fontScale}.png"))
        if (compact) node("Pattern Equalizer").click() else node("Previous pattern").click()
        SystemClock.sleep(700)
        assertEquals(Scene.EQUALIZER, SettingsStore(context).load().scene)
        // The footer remains reachable when audio controls enlarge the scrolling content.
        text("Tune").click(); SystemClock.sleep(400)
        val tuning = node("Tuning panel").visibleBounds
        hud().forEach { assertFalse("Tuning overlaps a persistent control", Rect.intersects(tuning, it)) }
        text("Done").click(); text("Immerse").click()
        device.wait(Until.findObject(By.text("Got it")), 500)?.click()
        SystemClock.sleep(800); hud()
        assertFalse(device.hasObject(By.desc("Pattern controls panel")))
    }
    @Test fun audioInputAndFooterRemainReachable() {
        val toggle = node("React to audio")
        assertTrue("The audio switch is fully visible before scrolling",
            toggle.visibleBounds.height() >= 30 * context.resources.displayMetrics.density)
        toggle.click(); SystemClock.sleep(600)
        val area = node("Pattern controls content")
        repeat(4) {
            if (device.hasObject(By.text("Connect audio"))) return@repeat
            area.scroll(Direction.DOWN, .7f); SystemClock.sleep(200); refresh()
        }
        assertTrue(text("Connect audio").isEnabled)
        // A short landscape viewport scrolls source selection and connection separately.
        repeat(4) {
            if (device.hasObject(By.text("Microphone"))) return@repeat
            area.scroll(Direction.UP, .35f); SystemClock.sleep(200); refresh()
        }
        text("Microphone"); text("System audio")
        device.takeScreenshot(File(evidence, "home-audio-${device.displayWidth}-${context.resources.configuration.fontScale}.png"))
        text("Tune")
        if (compact) node("More app controls").click()
        text("App settings")
        assertFalse(AudioEngine.status.value.running)
    }
}
