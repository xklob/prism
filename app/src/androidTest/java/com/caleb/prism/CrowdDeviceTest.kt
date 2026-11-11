package com.caleb.prism

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CrowdDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null
    private val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
    private fun click(label: String) {
        instrumentation.uiAutomation.clearCache()
        val control = device.wait(Until.findObject(By.text(label)), 6000)
        assertNotNull("Expected visible action: " + label, control)
        control.click()
        SystemClock.sleep(350)
    }
    private fun waitForText(text: String, timeout: Long): Boolean {
        val until = SystemClock.elapsedRealtime() + timeout
        do {
            // Android 16 can retain a Compose accessibility snapshot through
            // asynchronous state updates. Refresh it for each observation.
            instrumentation.uiAutomation.clearCache()
            if (device.hasObject(By.text(text))) return true
            SystemClock.sleep(150)
        } while (SystemClock.elapsedRealtime() < until)
        device.dumpWindowHierarchy(File(evidence, "crowd-wait-failed.xml"))
        device.takeScreenshot(File(evidence, "crowd-wait-failed.png"))
        return false
    }
    @After fun finish() {
        instrumentation.runOnMainSync { activity?.finish() }
    }
    @Test fun signedShowJoinsRendersResumesAndLeavesWithoutMicrophoneCapture() {
        val encoded = InstrumentationRegistry.getArguments().getString("crowdInvite")
        Assume.assumeTrue("Start controller/test/android-harness.ts and supply crowdInvite", encoded != null)
        val invitation = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP))
        context.getSharedPreferences("prism", 0).edit().clear().commit()
        device.wakeUp()
        device.setOrientationNatural()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(invitation), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent) as MainActivity
        click("Join show")
        assertTrue("Signed clock and state should become ready",
            waitForText("Ready · Following the VJ", 20000))
        assertFalse("Participants do not capture microphone audio", AudioEngine.status.value.running)
        assertTrue(device.takeScreenshot(File(evidence, "crowd-connected.png")))
        click("Done")
        click("Immerse")
        SystemClock.sleep(2600)
        var white = 0; var black = 0
        val until = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < until) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            if (Color.red(pixel) > 230 && Color.green(pixel) > 230 && Color.blue(pixel) > 230) white++
            if (Color.red(pixel) < 15 && Color.green(pixel) < 15 && Color.blue(pixel) < 15) black++
            bitmap.recycle()
            SystemClock.sleep(13)
        }
        assertTrue("Shared timing produced visible white flash frames", white > 0)
        assertTrue("Diagnostic flashes contain dark intervals", black > 0)
        device.pressHome()
        SystemClock.sleep(2200)
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        SystemClock.sleep(1800)
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        click("Crowd")
        assertTrue(waitForText("Ready · Following the VJ", 20000))
        click("Blank my screen")
        click("Done")
        click("Immerse")
        SystemClock.sleep(700)
        val blank = instrumentation.uiAutomation.takeScreenshot()
        assertEquals(Color.BLACK, blank.getPixel(blank.width / 2, blank.height / 2))
        blank.recycle()
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        click("Crowd")
        click("Leave show")
        assertTrue(device.wait(Until.hasObject(By.text("Join show")), 5000))
        click("Done")
        assertTrue(device.wait(Until.hasObject(By.text("AMBIENT")), 5000))
    }
    @Test fun invitationsRejectRemoteTargetsAndMalformedKeys() {
        for (input in listOf("https://example.com", "prism://crowd?host=8.8.8.8",
            "prism://crowd?host=192.168.1.2&port=5000&sid=bad&token=bad&key=bad")) {
            assertTrue(runCatching { CrowdInvitation.parse(input) }.isFailure)
        }
    }

    @Test fun sourceBridgeConvertsAndroidTimestampsIntoTheControllerClock() {
        val encoded = InstrumentationRegistry.getArguments().getString("crowdSourceInvite")
        Assume.assumeTrue("Select audio timing on the controller and supply crowdSourceInvite", encoded != null)
        val invitation = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP))
        val origin = System.nanoTime() / 1e9
        val client = CrowdClient(context) {
            val now = System.nanoTime() / 1e9
            RhythmState(timestamp=now,bpm=120f,position=(now-origin)*2,confidence=.9f,
                barConfidence=.8f,barLocked=true,signalPresent=true) to PhraseState(bars=16,bar=3,confidence=.7f)
        }
        try {
            client.join(invitation, false)
            val until = SystemClock.elapsedRealtime() + 20000
            while (SystemClock.elapsedRealtime() < until &&
                !(client.status.value.phase == "Ready" && kotlin.math.abs(client.status.value.beatConfidence-.9f) < .01f))
                SystemClock.sleep(100)
            assertEquals("Ready",client.status.value.phase)
            assertEquals(.9f,client.status.value.beatConfidence,.01f)
            assertEquals(.8f,client.status.value.barConfidence,.01f)
            assertEquals(.7f,client.status.value.phraseConfidence,.01f)
        } finally { client.close() }
    }
}
