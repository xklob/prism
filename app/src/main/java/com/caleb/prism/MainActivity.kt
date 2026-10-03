package com.caleb.prism

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private lateinit var store: SettingsStore
    private var settings by mutableStateOf(VisualSettings())
    private var renderError by mutableStateOf<String?>(null)
    private var connecting by mutableStateOf(false)
    private var surface: PrismSurface? = null
    private var resumeMicrophone = false

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        connecting = false
        if (granted && settings.scene.reactive && !settings.paused) connectAudio()
        else if (!granted) AudioEngine.report("Audio permission was declined. You can allow it in Android Settings, or enjoy an ambient scene.")
    }
    private val playbackPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        connecting = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null && settings.scene.reactive && settings.source == AudioSource.SYSTEM && !settings.paused) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, PlaybackCaptureService::class.java).putExtra("consent", result.data))
            } catch (_: Exception) {
                AudioEngine.report("Couldn't connect system audio. Reopen Prism and try again.")
            }
        } else if (result.resultCode != Activity.RESULT_OK) AudioEngine.report("System audio wasn't connected. Tap Connect audio whenever you're ready.")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connecting = false
        if (settings.scene.reactive && settings.source == AudioSource.SYSTEM && !settings.paused) requestSystemProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        store = SettingsStore(this)
        settings = store.load()
        if (AudioEngine.status.value.running && AudioEngine.status.value.source == AudioSource.SYSTEM) {
            settings = settings.copy(source = AudioSource.SYSTEM, scene = if (settings.scene.reactive) settings.scene else Scene.PULSE)
        }
        updateWakeLock()
        setContent {
            PrismApp(settings, renderError, connecting,
                createSurface = {
                    PrismSurface(this) { error -> runOnUiThread { renderError = error } }.also {
                        surface = it; it.update(settings)
                    }
                },
                onChange = ::changeSettings,
                onConnect = ::connectAudio,
                onDisconnect = ::disconnectAudio,
                onImmersive = ::updateImmersive
            )
        }
    }

    private fun changeSettings(next: VisualSettings) {
        if (!next.scene.reactive || next.source != settings.source || next.paused) disconnectAudio()
        val resolved = if (next.scene != settings.scene) {
            store.save(settings)
            store.loadScene(next.scene, next.source).copy(paused = next.paused)
        } else next
        settings = resolved
        AudioEngine.sensitivity = resolved.sensitivity
        store.save(resolved)
        surface?.update(resolved)
        updateWakeLock()
    }

    private fun connectAudio() {
        if (connecting || !settings.scene.reactive) return
        if (settings.paused) changeSettings(settings.copy(paused = false))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            connecting = true
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        AudioEngine.sensitivity = settings.sensitivity
        if (settings.source == AudioSource.MICROPHONE) {
            stopService(Intent(this, PlaybackCaptureService::class.java))
            AudioEngine.startMicrophone(this)
        } else {
            if (AudioEngine.status.value.running && AudioEngine.status.value.source == AudioSource.SYSTEM) return
            AudioEngine.stop()
            val capturePrefs = getSharedPreferences("capture", MODE_PRIVATE)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !capturePrefs.getBoolean("notificationsAsked", false)) {
                capturePrefs.edit().putBoolean("notificationsAsked", true).apply()
                connecting = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else requestSystemProjection()
        }
    }

    private fun requestSystemProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Device-wide consent is needed to follow music from another app.
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
        connecting = true
        playbackPermission.launch(intent)
    }

    private fun disconnectAudio() {
        resumeMicrophone = false
        AudioEngine.stop()
        stopService(Intent(this, PlaybackCaptureService::class.java))
    }

    private fun updateImmersive(fullscreen: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun updateWakeLock() {
        if (settings.paused) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        surface?.onResume()
        if (resumeMicrophone && settings.scene.reactive && settings.source == AudioSource.MICROPHONE && !settings.paused) {
            resumeMicrophone = false
            AudioEngine.startMicrophone(this)
        }
    }

    override fun onPause() {
        surface?.onPause()
        if (AudioEngine.status.value.source == AudioSource.MICROPHONE) {
            resumeMicrophone = true
            AudioEngine.stop()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) disconnectAudio()
        surface = null
        super.onDestroy()
    }
}
