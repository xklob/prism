package com.caleb.prism

import android.app.*
import android.content.*
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder

/** Playback foreground service, independent of microphone/projection permissions. */
class LocalAudioService : Service() {
    private lateinit var session: MediaSession
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { LocalAudio.pause() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); LocalAudio.init(this)
        session = MediaSession(this, "prism local audio").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { LocalAudio.play() }
                override fun onPause() { LocalAudio.pause() }
                override fun onStop() { LocalAudio.pause() }
                override fun onSeekTo(pos: Long) { LocalAudio.seek(pos / 1000.0) }
                override fun onSkipToPrevious() { LocalAudio.seek(0.0) }
            })
            isActive = true
        }
        androidx.core.content.ContextCompat.registerReceiver(this, noisy,
            IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("local-playback", "Local audio playback", NotificationManager.IMPORTANCE_LOW))
        LocalAudio.transportChanged = ::update
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(43, notification())
        if (intent?.action == "stop") { LocalAudio.pause(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == "play") LocalAudio.startPlayback() else LocalAudio.pause()
        update()
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val playing = LocalAudio.state.value.playing
        val toggle = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(if (playing) "pause" else "play"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "local-playback").setSmallIcon(com.caleb.prism.R.drawable.ic_prism)
            .setContentTitle(LocalAudio.state.value.title).setContentText("prism // local audio")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(LocalAudio.state.value.playing)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .addAction(Notification.Action.Builder(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pause" else "Play", toggle).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build()).build()
    }
    private fun update() {
        val state = LocalAudio.state.value
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, (state.duration * 1000).toLong()).build())
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                (state.position * 1000).toLong(), if (state.playing) 1f else 0f).build())
        getSystemService(NotificationManager::class.java).notify(43, notification())
    }
    override fun onTaskRemoved(rootIntent: Intent?) { LocalAudio.pause(); stopSelf() }
    override fun onDestroy() {
        LocalAudio.transportChanged = null
        LocalAudio.pause(); session.release(); unregisterReceiver(noisy)
        super.onDestroy()
    }
}
