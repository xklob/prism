package com.caleb.prism

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null
    @Before fun launch() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        context.getSharedPreferences("prism", 0).edit().clear().commit()
        SettingsStore(context).save(VisualSettings(audioEnabled = true))
        Configurator.getInstance().waitForIdleTimeout = 300
        device.wakeUp(); device.setOrientationNatural()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        SystemClock.sleep(1000)
    }
    @After fun cleanup() {
        device.executeShellCommand("cmd sensor_privacy disable 0 microphone")
        instrumentation.runOnMainSync { activity?.finish() }
        AudioEngine.stop(); device.unfreezeRotation()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message, condition())
    }
    @Test fun androidMicrophonePrivacyStopIsDetected() {
        AudioEngine.startMicrophone(context)
        await("Microphone opens") { AudioEngine.status.value.running }
        SystemClock.sleep(1000)
        device.executeShellCommand("cmd sensor_privacy enable 0 microphone")
        await("Android privacy stop must disconnect capture") { !AudioEngine.status.value.running }
        assertNotNull("The user must see why audio ended", AudioEngine.status.value.message)
        assertEquals(0f, AudioEngine.levels.energy, 0f)
    }
    @Test fun cancelingTheProjectionWarningNeverOpensAnAudioInput() {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        device.findObject(By.text("System audio")).click(); SystemClock.sleep(200)
        device.findObject(By.text("Connect audio")).click()
        val cancel = device.wait(Until.findObject(By.text("CANCEL")), 5000)
        assertNotNull(cancel)
        assertFalse(AudioEngine.status.value.running)
        cancel.click(); SystemClock.sleep(300)
        assertFalse(AudioEngine.status.value.running)
    }
}
