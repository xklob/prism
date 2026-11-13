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
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class LookDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null
    private val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
    @Before fun setup() {
        Configurator.getInstance().waitForIdleTimeout = 300
        AudioEngine.stop(); AudioEngine.diagnostics.clear()
        context.getSharedPreferences("prism", 0).edit().clear().commit()
        device.wakeUp(); device.setOrientationNatural()
    }
    @After fun cleanup() { instrumentation.runOnMainSync { activity?.finish() }; device.unfreezeRotation() }
    private fun launch() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    }
    private fun tap(label: String, description: Boolean = false) {
        val selector = if (description) By.desc(label) else By.text(label)
        repeat(5) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val node = device.wait(Until.findObject(selector), 1500)
            if (node != null) { node.click(); SystemClock.sleep(300); return }
            device.swipe((device.displayWidth*.85).toInt(), (device.displayHeight*.78).toInt(),
                (device.displayWidth*.85).toInt(), (device.displayHeight*.5).toInt(), 15)
        }
        device.takeScreenshot(File(evidence, "look-failure.png")); fail("Missing $label")
    }
    @Test fun transitionsBlendCompleteImagesAndRetargetWithoutJumping() {
        ShaderProbe(context, evidence, 320, 200).use { it.verifyLookTransitions(); it.verifyPostProcessing() }
    }
    @Test fun manualTimingActionsInvalidatePendingCueEvidence() {
        val commands = listOf(AudioEngine::tapTempo, AudioEngine::alignBeat, AudioEngine::alignBar,
            AudioEngine::alignPhrase, AudioEngine::halfTempo, AudioEngine::doubleTempo,
            AudioEngine::automaticTiming, AudioEngine::automaticPhrase)
        commands.forEach { command ->
            val before = AudioEngine.manualTimingRevision
            command()
            assertTrue(AudioEngine.manualTimingRevision > before)
        }
        AudioEngine.stop()
        assertFalse(AudioEngine.status.value.running)
    }
    @Test fun namedLibraryKeepsCaptureAndSurvivesRestart() {
        val store = SettingsStore(context)
        store.save(VisualSettings.defaults(Scene.WORMHOLE).copy(source = AudioSource.SYSTEM,
            audioEnabled = false, beatsPerBar = 3, syncOffsetMs = 80f, targetFps = 30))
        store.savePreset("Ocean", VisualSettings.defaults(Scene.AURORA))
        store.savePreset("Neon", VisualSettings.defaults(Scene.KALEIDOSCOPE).copy(effects = PostEffects(enabled = true, bloom = .5f)))
        launch(); tap("Tune"); tap("Looks"); tap("SAVE NEW")
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3000)
        assertNotNull(field); field.text = "Custom"
        tap("SAVE"); assertEquals(3, store.presets().size)
        tap("Favorite Neon", true); assertTrue(store.presets().first { it.name == "Neon" }.favorite)
        tap("Apply Neon", true); SystemClock.sleep(1800)
        val applied = store.load()
        assertEquals(Scene.KALEIDOSCOPE, applied.scene); assertTrue(applied.effects.enabled)
        assertEquals(AudioSource.SYSTEM, applied.source); assertFalse(applied.audioEnabled)
        assertEquals(3, applied.beatsPerBar); assertEquals(80f, applied.syncOffsetMs, 0f); assertEquals(30, applied.targetFps)
        assertFalse(AudioEngine.status.value.running)
        tap("Queue Ocean on next phrase", true)
        assertTrue(device.wait(Until.hasObject(By.textContains("Connect audio")), 3000))
        tap("CANCEL")
        tap("Options for Custom", true); tap("Rename")
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3000).text = "My tunnel"
        tap("SAVE"); assertTrue(store.presets().any { it.name == "My tunnel" })
        device.takeScreenshot(File(evidence, "look-library-${device.displayWidth}.png"))
        tap("Options for My tunnel", true); tap("Delete"); tap("DELETE")
        assertEquals(2, store.presets().size)
        instrumentation.runOnMainSync { activity?.finish() }; launch()
        assertEquals(2, SettingsStore(context).presets().size)
        assertTrue(SettingsStore(context).presets().first { it.name == "Neon" }.favorite)
        assertFalse(AudioEngine.status.value.running)
    }
    @Test fun diagnosticHistoryCanBeInspectedFrozenAndExported() {
        launch()
        for (i in 0..800) {
            val t = 100.0 + i * .02
            val position = i * .04
            val phase = position - floor(position)
            AudioEngine.diagnostics.add(TimingSample(RhythmState(timestamp = t, position = position, bpm = 120f,
                confidence = .9f, barConfidence = .82f, barLocked = true, signalPresent = true,
                beatProbability = exp(-phase*phase*150).toFloat(),
                downbeatProbability = if (floor(position).toInt() % 4 == 0) exp(-phase*phase*150).toFloat() else .02f,
                lastMatchedBeatTime = t-phase*.5), PhraseState(8, floor(position/4).toInt()%8+1, .72f)))
        }
        tap("Tune"); tap("App settings"); tap("Diagnostics")
        assertTrue(device.wait(Until.hasObject(By.desc("Beat predictions and tracked grid")), 3000))
        tap("FREEZE")
        device.takeScreenshot(File(evidence, "timing-diagnostics-${device.displayWidth}.png"))
        tap("EXPORT CSV")
        val filename = "prism-timing-fixture-${SystemClock.elapsedRealtime()}.csv"
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)
        assertNotNull("Android file picker", field); field.text = filename
        val save = device.wait(Until.findObject(By.res("com.google.android.documentsui", "button1")), 1000)
            ?: device.wait(Until.findObject(By.text("SAVE")), 1000)
            ?: device.wait(Until.findObject(By.text("Save")), 1000)
        assertNotNull("Save document", save); save.click()
        assertTrue(device.wait(Until.hasObject(By.text("Timing CSV saved")), 8000))
        val result = instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$filename").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
        }
        assertTrue(result.startsWith("seconds,bpm,beat_position,")); assertEquals(802, result.trimEnd().lines().size)
        assertTrue(result.lines()[1].startsWith("0.00000,120.00000"))
        instrumentation.uiAutomation.executeShellCommand("rm /sdcard/Download/$filename").close()
        tap("CLEAR")
        assertTrue(AudioEngine.diagnostics.snapshot().isEmpty())
        assertFalse(AudioEngine.status.value.running)
    }
    @Test fun signedReleaseLookSmoke() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("releaseLooks") == "true")
        context.startActivity(Intent().setClassName("com.caleb.prism", "com.caleb.prism.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            assertTrue(device.wait(Until.hasObject(By.pkg("com.caleb.prism").depth(0)), 8000))
            tap("Tune"); tap("Looks"); tap("SAVE NEW")
            device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3000).text = "Release smoke"
            tap("SAVE"); tap("Favorite Release smoke", true); tap("Apply Release smoke", true)
            SystemClock.sleep(1800)
            tap("Queue Release smoke on next phrase", true)
            assertTrue(device.wait(Until.hasObject(By.textContains("Connect audio")), 3000))
            tap("CANCEL"); tap("Options for Release smoke", true); tap("Delete"); tap("DELETE")
            tap("App settings"); tap("Diagnostics")
            assertTrue(device.wait(Until.hasObject(By.textContains("Connect audio to collect")), 3000))
        } finally { instrumentation.uiAutomation.executeShellCommand("am force-stop com.caleb.prism").close() }
    }
}
