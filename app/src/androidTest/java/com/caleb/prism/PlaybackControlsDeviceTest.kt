package com.caleb.prism

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackControlsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val fixture = ComponentName(instrumentation.context.packageName, MediaFixtureService::class.java.name)
    private val listener = "${context.packageName}/${NowPlayingListener::class.java.name}"
    private var activity: MainActivity? = null
    private fun refresh() { if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache() }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) { refresh(); if (condition()) return; SystemClock.sleep(100) }
        assertTrue(message, condition())
    }
    private fun fixture(actions: Long? = null, noMetadata: Boolean = false) {
        val intent = Intent().setComponent(fixture).putExtra("title", "Fixture track 1")
            .putExtra("artist", "Synthetic test session").putExtra("noMetadata", noMetadata)
        if (actions != null) intent.putExtra("actions", actions)
        context.startForegroundService(intent)
    }
    private fun tap(label: String, description: Boolean = true) {
        refresh()
        val node = device.wait(Until.findObject(if (description) By.desc(label) else By.text(label)), 5000)
        assertNotNull(label, node); assertTrue("$label enabled", node.isEnabled); node.click(); SystemClock.sleep(300)
    }
    @Before fun launch() {
        Configurator.getInstance().waitForIdleTimeout = 300
        context.getSharedPreferences("prism", 0).edit().clear().commit()
        context.getSharedPreferences("song_assist", 0).edit().clear().commit()
        SettingsStore(context).save(VisualSettings.defaults(Scene.KALEIDOSCOPE).copy(audioEnabled = true))
        device.executeShellCommand("cmd notification disallow_listener $listener")
        device.wakeUp(); device.setOrientationNatural()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        SystemClock.sleep(900)
    }
    @After fun cleanup() {
        context.stopService(Intent().setComponent(fixture))
        device.executeShellCommand("cmd notification disallow_listener $listener")
        instrumentation.runOnMainSync { activity?.finish() }
        AudioEngine.stop(); device.unfreezeRotation()
    }
    @Test fun controlsDispatchToRealSessionWithoutCaptureOrSongAssist() {
        await("Access entry is visible") { device.hasObject(By.text("ENABLE PLAYBACK")) }
        device.executeShellCommand("cmd notification allow_listener $listener")
        fixture()
        await("Player is discovered") { NowPlaying.controls.value?.title == "Fixture track 1" }
        tap("Pause playback")
        await("Pause reaches the external session") { NowPlaying.controls.value?.playing == false }
        tap("Play playback")
        await("Play reaches the same paused session") { NowPlaying.controls.value?.playing == true }
        tap("Next track")
        await("Next reaches the external queue") { NowPlaying.controls.value?.title == "Fixture track 2" }
        tap("Previous track")
        await("Previous reaches the external queue") { NowPlaying.controls.value?.title == "Fixture track 1" }
        assertFalse("Transport never opens capture", AudioEngine.status.value.running)
        assertFalse("Transport does not enable online song lookup", SongPreferences(context).options().enabled)
        val player = device.findObject(By.desc("System playback controls")).visibleBounds
        assertTrue(player.centerX() < device.displayWidth / 2)
        for (label in listOf("Record session", "Manual timing panel", "Tablet song and timing monitor"))
            device.findObject(By.desc(label))?.let { assertFalse("Player must not cover $label", Rect.intersects(player, it.visibleBounds)) }
        tap("Tune", false); refresh()
        for (label in listOf("Tuning panel", "Manual timing panel")) device.findObject(By.desc(label))?.let {
            assertFalse("Player must not cover $label while tuning", Rect.intersects(player, it.visibleBounds))
        }
        val evidence = File(context.getExternalFilesDir(null), "review").apply { mkdirs() }
        device.takeScreenshot(File(evidence, "playback-controls-${device.displayWidth}.png"))
        tap("Done", false); tap("Immerse", false)
        device.wait(Until.findObject(By.text("Got it")), 1000)?.click()
        SystemClock.sleep(2600); refresh()
        assertTrue(device.hasObject(By.desc("System playback controls")))
        tap("Pause playback")
        await("Controls work in fullscreen") { NowPlaying.controls.value?.playing == false }
        context.stopService(Intent().setComponent(fixture))
        await("Destroyed sessions clear the controls") { NowPlaying.controls.value == null }
        await("No stale enabled Play button") { device.findObject(By.desc("Play playback"))?.isEnabled == false }
    }
    @Test fun capabilitiesMissingMetadataAndRevokedAccessAreHandled() {
        device.executeShellCommand("cmd notification allow_listener $listener")
        fixture(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE, noMetadata = true)
        await("A player without song metadata remains controllable") { NowPlaying.controls.value != null }
        assertNull(NowPlaying.playback.value)
        await("Unsupported skips are disabled") {
            device.findObject(By.desc("Previous track"))?.isEnabled == false && device.findObject(By.desc("Next track"))?.isEnabled == false
        }
        tap("Pause playback")
        await("Metadata-free session pauses") { NowPlaying.controls.value?.playing == false }
        device.executeShellCommand("cmd notification disallow_listener $listener")
        await("Revocation clears transport access") { NowPlaying.controls.value == null && device.hasObject(By.text("ENABLE PLAYBACK")) }
        var sent = true
        instrumentation.runOnMainSync { sent = NowPlaying.command(context, PlaybackCommand.TOGGLE) }
        assertFalse("Revoked access cannot send commands", sent)
    }

    @Test fun playRestartsASeekableFinishedItem() {
        device.executeShellCommand("cmd notification allow_listener $listener")
        context.startForegroundService(Intent().setComponent(fixture).putExtra("title", "Finished fixture")
            .putExtra("artist", "Synthetic test session").putExtra("ended", true))
        await("Finished player remains available") { NowPlaying.controls.value?.title == "Finished fixture" }
        tap("Play playback")
        await("Play rewinds the finished queue before resuming") {
            NowPlaying.playback.value?.playing == true && (NowPlaying.playback.value?.positionMs ?: Long.MAX_VALUE) < 1000
        }
        context.startService(Intent().setComponent(fixture).putExtra("empty", true))
        await("An initialized player with an empty queue is not an active player") { NowPlaying.controls.value == null }
    }
}
