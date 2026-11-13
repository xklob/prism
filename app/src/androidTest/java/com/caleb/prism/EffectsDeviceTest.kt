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
class EffectsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null
    @After fun cleanup() { instrumentation.runOnMainSync { activity?.finish() }; device.unfreezeRotation() }
    @Test fun effectsRenderAllPatternsAndBypassIsExact() {
        val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
        ShaderProbe(context, evidence, 320, 200).use { it.verifyPostProcessing() }
    }
    @Test fun globalControlsPersistAcrossPatternsAndRestart() {
        Configurator.getInstance().waitForIdleTimeout = 300
        context.getSharedPreferences("prism", 0).edit().clear().commit()
        val store = SettingsStore(context)
        store.save(VisualSettings.defaults(Scene.KALEIDOSCOPE).copy(effects = PostEffects(bloom = .7f, blur = .15f)))
        device.wakeUp(); device.setOrientationNatural()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun tap(text: String, description: Boolean = false) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val node = device.wait(Until.findObject(if (description) By.desc(text) else By.text(text)), 6000)
            assertNotNull(text, node); node.click(); SystemClock.sleep(250)
        }
        tap("App settings"); tap("Effects"); tap("Global effects", true)
        assertTrue(store.load().effects.enabled)
        assertEquals(.7f, store.loadScene(Scene.WORMHOLE, AudioSource.MICROPHONE).effects.bloom, 0f)
        tap("About Bloom", true)
        assertTrue(device.wait(Until.hasObject(By.textContains("Spreads light")), 3000))
        device.pressBack(); SystemClock.sleep(250)
        val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
        device.takeScreenshot(File(evidence, "global-effects-${device.displayWidth}.png"))
        tap("Display"); tap("30")
        assertEquals(30, store.load().targetFps)
        assertTrue(store.load().adaptiveQuality)
        assertTrue(device.wait(Until.hasObject(By.desc("Render quality status")), 4000))
        instrumentation.runOnMainSync { activity?.finish() }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        SystemClock.sleep(1000)
        assertTrue(store.load().effects.enabled)
        assertEquals(30, store.load().targetFps)
        assertFalse(AudioEngine.status.value.running)
    }
}
