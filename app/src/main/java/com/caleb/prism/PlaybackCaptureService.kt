package com.caleb.prism

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import kotlinx.coroutines.*

/** Android's user-approved playback capture; no screen frames are acquired. */
class PlaybackCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var shuttingDown = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("audio", "System audio", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP" || intent == null) { shutdown(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        val consent = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("consent", Intent::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra("consent") as? Intent)
        if (consent == null) { shutdown(); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "audio").setSmallIcon(R.drawable.ic_prism)
            .setContentTitle("Prism is listening to system audio")
            .setContentText("Audio is analyzed on this device. Tap Stop to disconnect.")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        try {
            startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val manager = getSystemService(MediaProjectionManager::class.java)
            val capture = requireNotNull(manager.getMediaProjection(Activity.RESULT_OK, consent))
            projection = capture
            callback = object : MediaProjection.Callback() {
                override fun onStop() { shutdown() }
            }.also { capture.registerCallback(it, Handler(Looper.getMainLooper())) }
            AudioEngine.startSystem(this, capture)
            if (!AudioEngine.status.value.running) {
                val message = AudioEngine.status.value.message
                shutdown()
                AudioEngine.report(message ?: "Couldn't start system audio. Try connecting again.")
            } else scope.launch {
                AudioEngine.status.collect { if (!it.running && !shuttingDown) shutdown() }
            }
        } catch (_: Exception) {
            shutdown()
            AudioEngine.report("System audio access ended. Tap Connect audio to authorize a new session.")
        }
        return START_NOT_STICKY
    }

    private fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        scope.cancel()
        if (AudioEngine.status.value.source == AudioSource.SYSTEM) AudioEngine.stop()
        callback?.let { projection?.unregisterCallback(it) }
        callback = null
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { shutdown(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { shutdown(); super.onDestroy() }
}
