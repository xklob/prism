package com.caleb.prism

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock

/** Test APK only: a separate app UID publishing the same standard fields as a music player. */
class MediaFixtureService : Service() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("fixture", "Test media", NotificationManager.IMPORTANCE_LOW))
        startForeground(941, Notification.Builder(this, "fixture").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Prism media fixture").build())
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val media = session ?: MediaSession(this, "Prism media test").also { session = it; it.isActive = true }
        media.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, intent?.getStringExtra("title") ?: "Wake Me Up")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Avicii")
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "spotify:track:0nrRP2bk19rLc0orkWPQk2")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 247426).build())
        media.setPlaybackState(PlaybackState.Builder()
            .setState(if (intent?.getBooleanExtra("paused", false) == true) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                intent?.getLongExtra("position", 30000) ?: 30000, intent?.getFloatExtra("speed", 1f) ?: 1f, SystemClock.elapsedRealtime())
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO).build())
        return START_NOT_STICKY
    }
    override fun onDestroy() { session?.release(); super.onDestroy() }
}
