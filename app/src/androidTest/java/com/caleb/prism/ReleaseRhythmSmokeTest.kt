package com.caleb.prism

import android.Manifest
import android.content.Intent
import android.media.*
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.regex.Pattern

/** Opt-in test of the installed, signed, R8-minified release in a separate process. */
@RunWith(AndroidJUnit4::class)
class ReleaseRhythmSmokeTest {
    @Test fun signedReleaseTracksCapturedMusicAndAcceptsTimingCorrections() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("releaseSmoke") == "true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        val evidence=File(context.getExternalFilesDir(null),"review").apply { mkdirs() }
        val app="com.caleb.prism"
        fun click(text: String) {
            instrumentation.uiAutomation.clearCache()
            var target=device.findObject(By.text(text))
            for (attempt in 0 until 7) {
                if (target != null && target.visibleBounds.height() >= 20) break
                device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,.45f)
                SystemClock.sleep(200)
                instrumentation.uiAutomation.clearCache()
                target=device.findObject(By.text(text))
            }
            assertNotNull("Visible release action: $text",target)
            assertTrue("Enabled release action: $text",requireNotNull(target).isEnabled)
            target.click()
            SystemClock.sleep(300)
        }
        fun launch() {
            context.startActivity(Intent().setClassName(app,"$app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("PRISM")),10000))
            SystemClock.sleep(500)
        }
        fun audioEnabled(): Boolean {
            instrumentation.uiAutomation.clearCache()
            var control=device.findObject(By.desc("React to audio"))
            while (control != null && !control.isCheckable) control=control.parent
            return requireNotNull(control).isChecked
        }
        fun waitText(pattern: String): UiObject2 {
            device.findObject(By.scrollable(true))?.fling(Direction.UP)
            SystemClock.sleep(300)
            val end=SystemClock.elapsedRealtime()+30000
            while (SystemClock.elapsedRealtime()<end) {
                instrumentation.uiAutomation.clearCache()
                val match=device.findObject(By.text(Pattern.compile(pattern)))
                if (match != null) return match
                SystemClock.sleep(250)
            }
            throw AssertionError("Expected release text matching $pattern")
        }
        instrumentation.uiAutomation.grantRuntimePermission(app,Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(app,Manifest.permission.POST_NOTIFICATIONS)
        device.wakeUp()
        device.setOrientationNatural()
        device.executeShellCommand("am force-stop $app")
        launch()
        click("Aurora")
        if (!audioEnabled()) device.findObject(By.desc("React to audio")).click()
        click("System audio")
        click("Connect audio")
        val consent=device.wait(Until.findObject(By.res("android:id/button1")),5000)
        assertNotNull(consent)
        consent.click()
        assertTrue(device.wait(Until.hasObject(By.text("Stop")),10000))

        val bytes=instrumentation.context.assets.open("rhythm/drums128.pcm").use { it.readBytes() }
        val pcm=ShortArray(bytes.size/2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val track=AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size*2).build()
        try {
            assertEquals(pcm.size,track.write(pcm,0,pcm.size))
            track.setLoopPoints(0,pcm.size,-1)
            track.play()
            click("Tune")
            click("Audio")
            waitText("12[6789][.,][0-9] BPM")
            waitText("Beat locked · Bar locked · 4 beats")
            device.takeScreenshot(File(evidence,"release-beat-bar-lock.png"))
            click("½ BPM")
            waitText("6[34][.,][0-9] BPM")
            click("2× BPM")
            waitText("12[6789][.,][0-9] BPM")
            click("Bar starts here")
            waitText("Manual tempo · Bar aligned")
            device.takeScreenshot(File(evidence,"release-manual-alignment.png"))
            click("Auto timing")
            waitText("Beat locked · Bar locked · 4 beats")
            track.pause()
            waitText("Waiting for sound.*")
            click("Stop")
            click("Done")
            device.executeShellCommand("am force-stop $app")
            launch()
            assertTrue("Audio preference survives release restart",audioEnabled())
            assertTrue(device.hasObject(By.text("Connect audio")))
            assertFalse(device.hasObject(By.text("Stop")))
            File(evidence,"release-rhythm-smoke.txt").writeText("Signed release: captured music locked to 128 BPM and 4-beat bars; half/double tempo, manual bar alignment, auto recovery, silence, stop and cold restart passed.\n")
        } finally {
            track.release()
            device.executeShellCommand("am force-stop $app")
        }
    }
}
