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

object NowPlaying {
    internal val current = MutableStateFlow<SongPlayback?>(null)
    val playback = current.asStateFlow()
    fun component(context: Context) = ComponentName(context, NowPlayingListener::class.java)
    fun permitted(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(component(context))
}

/** Android requires notification access for media-session discovery. Notification contents are never read. */
class NowPlayingListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val controllers = linkedMapOf<MediaController, MediaController.Callback>()
    private val manager by lazy { getSystemService(MediaSessionManager::class.java) }
    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { attach(it.orEmpty()) }
    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            manager.addOnActiveSessionsChangedListener(changed, NowPlaying.component(this), handler)
            attach(manager.getActiveSessions(NowPlaying.component(this)))
        }.onFailure { NowPlaying.current.value = null }
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
        val controller = controllers.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
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
    private fun detach() { controllers.forEach { (c, cb) -> c.unregisterCallback(cb) }; controllers.clear() }
    override fun onListenerDisconnected() { detach(); manager.removeOnActiveSessionsChangedListener(changed); NowPlaying.current.value = null; super.onListenerDisconnected() }
    override fun onDestroy() { detach(); manager.removeOnActiveSessionsChangedListener(changed); NowPlaying.current.value = null; super.onDestroy() }
}
