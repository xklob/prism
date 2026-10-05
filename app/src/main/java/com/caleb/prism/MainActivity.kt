package com.caleb.prism

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.pm.ActivityInfo
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var store: SettingsStore
    private var settings by mutableStateOf(VisualSettings())
    private var renderError by mutableStateOf<String?>(null)
    private var connecting by mutableStateOf(false)
    private var surface: PrismSurface? = null
    private var resumeMicrophone = false
    private lateinit var recording: SessionRecorder
    private lateinit var songAssistant: SongAssistant
    private lateinit var crowd: CrowdClient
    private var crowdInvitation by mutableStateOf("")
    private var pendingCrowdJoin: Pair<String, Boolean>? = null
    private var foreground = false
    private var unlockedOrientation: Int? = null

    private fun wantsAudio() = (!crowd.connected || crowd.source) && ((settings.audioEnabled && !settings.paused) || recording.state.value.busy)

    private val wifiPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = results[if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION] == true
        val pending = pendingCrowdJoin
        pendingCrowdJoin = null
        if (granted && pending != null) joinCrowd(pending.first, pending.second)
        else crowd.message("Wi-Fi permission was declined. Join the hotspot in Android Settings and turn off automatic Wi-Fi joining.")
    }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        connecting = false
        if (granted && wantsAudio()) connectAudio()
        else if (!granted) {
            recording.stop("Recording needs audio permission. You can allow it in Android Settings.")
            AudioEngine.report("Audio permission was declined. You can allow it in Android Settings, or enjoy an ambient scene.")
        }
    }
    private val playbackPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        connecting = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null && wantsAudio() && settings.source == AudioSource.SYSTEM) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, PlaybackCaptureService::class.java).putExtra("consent", result.data))
            } catch (_: Exception) {
                AudioEngine.report("Couldn't connect system audio. Reopen Prism and try again.")
                recording.stop("Couldn't connect system audio. Try recording again.")
            }
        } else if (result.resultCode != Activity.RESULT_OK) {
            recording.stop("System audio recording was canceled.")
            AudioEngine.report("System audio wasn't connected. Tap Connect audio whenever you're ready.")
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connecting = false
        if (wantsAudio() && settings.source == AudioSource.SYSTEM) requestSystemProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        store = SettingsStore(this)
        crowd = CrowdClient(this)
        if (intent.data?.scheme == "prism" && intent.data?.host == "crowd") crowdInvitation = intent.dataString.orEmpty()
        songAssistant = SongAssistant(this)
        recording = SessionRecorder(this) {
            if (!isDestroyed) {
                unlockedOrientation?.let { requestedOrientation = it }
                unlockedOrientation = null
                if (!settings.audioEnabled || settings.paused || (!foreground && settings.source == AudioSource.MICROPHONE)) {
                    disconnectAudio()
                    resumeMicrophone = !foreground && settings.audioEnabled && !settings.paused && settings.source == AudioSource.MICROPHONE
                }
                updateWakeLock()
            }
        }
        settings = store.load()
        if (AudioEngine.status.value.running && AudioEngine.status.value.source == AudioSource.SYSTEM) {
            settings = settings.copy(source = AudioSource.SYSTEM, audioEnabled = true)
        }
        updateWakeLock()
        lifecycleScope.launch {
            AudioEngine.status.collect { status ->
                if (status.running) prepareRecording()
                else if (recording.state.value.phase in listOf(RecordingPhase.PREPARING, RecordingPhase.RECORDING)) {
                    recording.stop("Audio disconnected. The recording was stopped.")
                } else if (recording.state.value.phase == RecordingPhase.CONNECTING && status.message != null) recording.stop(status.message)
            }
        }
        setContent {
            PrismApp(settings, renderError, connecting,
                createSurface = {
                    PrismSurface(this) { error -> runOnUiThread { renderError = error } }.also {
                        surface = it; it.update(settings); it.updateCrowd(crowd)
                        it.post { prepareRecording() }
                    }
                },
                onChange = ::changeSettings,
                onConnect = ::connectAudio,
                onDisconnect = ::disconnectAudio,
                onImmersive = ::updateImmersive,
                recording = recording,
                onRecord = ::startRecording,
                onOpenRecording = { openRecording(it, false) },
                onShareRecording = { openRecording(it, true) }, songAssistant = songAssistant,
                crowd = crowd, crowdInvitation = crowdInvitation, onCrowdJoin = ::joinCrowd,
                onCrowdLeave = ::leaveCrowd, onCrowdBrightness = ::crowdBrightness
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data?.scheme == "prism" && intent.data?.host == "crowd") crowdInvitation = intent.dataString.orEmpty()
    }

    private fun joinCrowd(text: String, automaticWifi: Boolean) {
        if (recording.state.value.busy) { crowd.message("Finish recording before joining a show."); return }
        try {
            val invite = CrowdInvitation.parse(text)
            if (automaticWifi && invite.ssid != null) {
                val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    pendingCrowdJoin = text to automaticWifi
                    wifiPermission.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(permission)
                        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    return
                }
            }
            crowd.join(text, automaticWifi)
            if (invite.source) changeSettings(settings.copy(audioEnabled = true, paused = false))
            else disconnectAudio()
            surface?.updateCrowd(crowd)
            crowdBrightness(crowd.brightness)
            updateWakeLock()
        } catch (error: Exception) { crowd.message(error.message ?: "Couldn't join the show.") }
    }

    private fun leaveCrowd() {
        crowd.leave(); surface?.updateCrowd(crowd)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        updateWakeLock()
    }

    private fun crowdBrightness(value: Float) {
        crowd.brightness = value.coerceIn(.1f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = crowd.brightness }
    }

    private fun changeSettings(next: VisualSettings) {
        if (recording.state.value.busy && next.source != settings.source) return
        val resolved = if (next.scene != settings.scene) {
            store.save(settings)
            store.loadScene(next.scene, next.source).copy(paused = next.paused)
        } else next
        // Resolve the destination's saved audio toggle before deciding whether to retain capture.
        if (!recording.state.value.busy && (!resolved.audioEnabled || resolved.source != settings.source || resolved.paused)) disconnectAudio()
        settings = resolved
        AudioEngine.sensitivity = resolved.sensitivity
        AudioEngine.beatsPerBar = resolved.beatsPerBar
        store.save(resolved)
        surface?.update(resolved)
        updateWakeLock()
    }

    private fun connectAudio() {
        if (crowd.connected && !crowd.source) return
        if (connecting || (!settings.audioEnabled && !recording.state.value.busy)) return
        if (settings.paused && !recording.state.value.busy) changeSettings(settings.copy(paused = false))
        if (AudioEngine.status.value.running && AudioEngine.status.value.source == settings.source) {
            prepareRecording()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            connecting = true
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        AudioEngine.sensitivity = settings.sensitivity
        AudioEngine.beatsPerBar = settings.beatsPerBar
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

    private fun startRecording(source: AudioSource) {
        if (crowd.connected) { crowd.message("Leave the show before starting a local recording."); return }
        if (recording.state.value.busy) return
        if (settings.source != source) changeSettings(settings.copy(source = source))
        recording.request(source)
        connectAudio()
        updateWakeLock()
    }

    private fun prepareRecording() {
        if (!foreground || recording.state.value.phase != RecordingPhase.CONNECTING) return
        val view = surface ?: return
        if (view.width == 0 || view.height == 0) return
        if (!AudioEngine.status.value.running) return
        if (unlockedOrientation == null) {
            unlockedOrientation = requestedOrientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
        recording.prepare(view)
    }

    private fun openRecording(saved: SavedRecording, share: Boolean) {
        try {
            val intent = if (share) Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, saved.uri)
                clipData = ClipData.newRawUri(saved.name, saved.uri)
            } else Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri, "video/mp4")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(if (share) Intent.createChooser(intent, "Share Prism recording") else intent)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Find your recording in Movies/Prism using Photos or Files.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun updateImmersive(fullscreen: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun updateWakeLock() {
        if (settings.paused && !recording.state.value.busy && !crowd.connected) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        crowd.setForeground(true)
        surface?.onResume()
        if (resumeMicrophone && settings.audioEnabled && settings.source == AudioSource.MICROPHONE && !settings.paused) {
            resumeMicrophone = false
            AudioEngine.startMicrophone(this)
        }
        prepareRecording()
    }

    override fun onPause() {
        foreground = false
        crowd.setForeground(false)
        if (recording.state.value.phase in listOf(RecordingPhase.PREPARING, RecordingPhase.RECORDING)) recording.stop()
        surface?.onPause()
        if (AudioEngine.status.value.source == AudioSource.MICROPHONE) {
            resumeMicrophone = true
            AudioEngine.stop()
        }
        super.onPause()
    }

    override fun onDestroy() {
        crowd.close()
        songAssistant.close()
        recording.stop()
        if (isFinishing) disconnectAudio()
        surface = null
        super.onDestroy()
    }
}
