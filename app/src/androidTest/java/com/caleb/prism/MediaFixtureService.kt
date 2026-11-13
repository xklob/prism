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
    private var title = "Wake Me Up"
    private var artist = "Avicii"
    private var index = 1
    private var paused = false
    private var ended = false
    private var empty = false
    private var position = 30000L
    private var speed = 1f
    private var noMetadata = false
    private var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("fixture", "Test media", NotificationManager.IMPORTANCE_LOW))
        startForeground(941, Notification.Builder(this, "fixture").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Prism media fixture").build())
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        session ?: MediaSession(this, "prism media test").also {
            session = it
            it.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (!ended) paused = false; publish() }
                override fun onPause() { paused = true; publish() }
                override fun onSeekTo(pos: Long) { position = pos; ended = false; paused = true; publish() }
                override fun onSkipToNext() { index++; title = "Fixture track $index"; position = 0; publish() }
                override fun onSkipToPrevious() { index--; title = "Fixture track $index"; position = 0; publish() }
            })
            it.isActive = true
        }
        title = intent?.getStringExtra("title") ?: "Wake Me Up"
        artist = intent?.getStringExtra("artist") ?: "Avicii"
        index = 1
        paused = intent?.getBooleanExtra("paused", false) == true
        ended = intent?.getBooleanExtra("ended", false) == true
        empty = intent?.getBooleanExtra("empty", false) == true
        position = intent?.getLongExtra("position", 30000) ?: 30000
        if (ended) position = 247426
        speed = intent?.getFloatExtra("speed", 1f) ?: 1f
        actions = intent?.getLongExtra("actions", actions) ?: actions
        noMetadata = intent?.getBooleanExtra("noMetadata", false) == true
        publish()
        return START_NOT_STICKY
    }
    private fun publish() {
        val media = session ?: return
        media.setMetadata(if (noMetadata) null else MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "spotify:track:0nrRP2bk19rLc0orkWPQk2")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 247426).build())
        media.setPlaybackState(PlaybackState.Builder()
            .setState(if (empty) PlaybackState.STATE_NONE else if (ended) PlaybackState.STATE_STOPPED else if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                position, speed, SystemClock.elapsedRealtime()).setActions(actions).build())
    }
    override fun onDestroy() { session?.release(); super.onDestroy() }
}
