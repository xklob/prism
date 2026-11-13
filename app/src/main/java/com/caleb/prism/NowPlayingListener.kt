package com.caleb.prism

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackCommand { PREVIOUS, TOGGLE, NEXT }
data class PlayerControls(
    val title: String, val packageName: String, val playing: Boolean,
    val previous: Boolean, val toggle: Boolean, val next: Boolean
)

internal fun playbackIsAdvancing(state: Int?) = state in listOf(PlaybackState.STATE_PLAYING,
    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_FAST_FORWARDING,
    PlaybackState.STATE_REWINDING)

object NowPlaying {
    internal val current = MutableStateFlow<SongPlayback?>(null)
    val playback = current.asStateFlow()
    private val controlState = MutableStateFlow<PlayerControls?>(null)
    val controls = controlState.asStateFlow()
    private var target: MediaController? = null
    fun component(context: Context) = ComponentName(context, NowPlayingListener::class.java)
    fun permitted(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(component(context))

    /** Called on the main thread, like the session callbacks and UI commands. */
    internal fun select(controller: MediaController?) {
        target = controller
        val state = controller?.playbackState
        controlState.value = if (controller == null || state == null) null else {
            val playing = playbackIsAdvancing(state.state)
            val actions = state.actions
            PlayerControls((controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: controller.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.takeIf { it.isNotBlank() }?.take(300) ?: "Current player",
                controller.packageName, playing,
                actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                actions and (PlaybackState.ACTION_PLAY_PAUSE or if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY) != 0L,
                actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        }
    }

    fun command(context: Context, command: PlaybackCommand): Boolean {
        if (!permitted(context)) { select(null); return false }
        val controller = target ?: return false
        // Re-read capabilities at dispatch time; the player may have changed its queue or state.
        select(controller)
        val state = controlState.value ?: return false
        val supported = when (command) {
            PlaybackCommand.PREVIOUS -> state.previous
            PlaybackCommand.TOGGLE -> state.toggle
            PlaybackCommand.NEXT -> state.next
        }
        if (!supported) return false
        return runCatching {
            when (command) {
                PlaybackCommand.PREVIOUS -> controller.transportControls.skipToPrevious()
                PlaybackCommand.NEXT -> controller.transportControls.skipToNext()
                PlaybackCommand.TOGGLE -> if (state.playing) controller.transportControls.pause() else {
                    val playback = controller.playbackState
                    val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                    // Some players leave a finished queue at its end position. Play alone
                    // then does nothing; rewind only a seekable, fully finished item.
                    if (playback?.state == PlaybackState.STATE_STOPPED && duration > 0L &&
                        playback.position >= duration && playback.actions and PlaybackState.ACTION_SEEK_TO != 0L)
                        controller.transportControls.seekTo(0L)
                    controller.transportControls.play()
                }
            }
        }.onFailure { select(null) }.isSuccess
    }
}

/** Android requires notification access for media-session discovery. Notification contents are never read. */
class NowPlayingListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val controllers = linkedMapOf<MediaController, MediaController.Callback>()
    private val playerVisibility by lazy { PlayerVisibility(this) }
    private val manager by lazy { getSystemService(MediaSessionManager::class.java) }
    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { attach(it.orEmpty()) }
    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            manager.addOnActiveSessionsChangedListener(changed, NowPlaying.component(this), handler)
            attach(manager.getActiveSessions(NowPlaying.component(this)))
        }.onFailure { detach(); NowPlaying.current.value = null; NowPlaying.select(null) }
    }
    private fun attach(sessions: List<MediaController>) {
        detach()
        sessions.filter { it.packageName != packageName }.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onSessionDestroyed() { controller.unregisterCallback(this); controllers.remove(controller); publish() }
            }
            controllers[controller] = callback
            controller.registerCallback(callback, handler)
        }
        publish()
    }
    private fun publish() {
        // Android supplies sessions in priority order. Prefer an actively playing session,
        // retaining the paused player so its Play action still works.
        val controller = controllers.keys.firstOrNull { playbackIsAdvancing(it.playbackState?.state) }
            ?: controllers.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: controllers.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_STOPPED &&
                (it.playbackState?.actions ?: 0L) and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L }
        playerVisibility.select(controller?.packageName)
        NowPlaying.select(controller)
        val metadata = controller?.metadata
        val state = controller?.playbackState
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (controller == null || metadata == null || state == null || title.isNullOrBlank()) {
            NowPlaying.current.value = null; return
        }
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
        val playing = state.state == PlaybackState.STATE_PLAYING
        val speed = state.playbackSpeed.toDouble().takeIf { it.isFinite() && it > 0 && it <= 4 } ?: 1.0
        val elapsed = if (playing && state.lastPositionUpdateTime > 0) (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) else 0L
        val position = state.position.takeIf { it >= 0 }?.let { (it + elapsed * speed).toLong().coerceAtMost(duration ?: Long.MAX_VALUE) }
        NowPlaying.current.value = SongPlayback(
            SongIdentity(title.take(300), metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().take(300), duration,
                SongMatching.spotifyId(mediaId) ?: SongMatching.spotifyId(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI))),
            controller.packageName, mediaId.take(500), position, speed, playing, System.nanoTime() / 1e9
        )
    }
    private fun detach() { controllers.forEach { (c, cb) -> c.unregisterCallback(cb) }; controllers.clear(); playerVisibility.clear() }
    override fun onListenerDisconnected() { detach(); manager.removeOnActiveSessionsChangedListener(changed); NowPlaying.current.value = null; NowPlaying.select(null); super.onListenerDisconnected() }
    override fun onDestroy() { detach(); manager.removeOnActiveSessionsChangedListener(changed); NowPlaying.current.value = null; NowPlaying.select(null); super.onDestroy() }
}
