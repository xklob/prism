package com.caleb.prism

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordingPhase { IDLE, CONNECTING, PREPARING, RECORDING, SAVING }
data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val source: AudioSource = AudioSource.MICROPHONE,
    val startedNs: Long = 0,
    val saved: SavedRecording? = null,
    val message: String? = null
) {
    val busy get() = phase != RecordingPhase.IDLE
}

/** Main-thread coordinator. Capture permissions remain owned by the activity. */
class SessionRecorder(context: Context, private val onFinished: () -> Unit) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("recordings", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(RecordingState(saved = prefs.getString("uri", null)?.let {
        SavedRecording(Uri.parse(it), prefs.getString("name", "Prism recording")!!)
    }))
    val state = mutableState.asStateFlow()
    private var encoder: SessionEncoder? = null
    private var surface: PrismSurface? = null
    private var stopTime = 0L

    fun request(source: AudioSource) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(phase = RecordingPhase.CONNECTING, source = source, message = null)
    }

    fun prepare(view: PrismSurface) {
        if (state.value.phase != RecordingPhase.CONNECTING || view.width == 0 || view.height == 0) return
        val capture = AudioEngine.status.value
        if (!capture.running || capture.source != state.value.source) return
        surface = view
        stopTime = 0
        mutableState.value = state.value.copy(phase = RecordingPhase.PREPARING)
        encoder = SessionEncoder(context, view.width, view.height, capture.channels,
            ready = { ready -> main.post {
                if (state.value.phase == RecordingPhase.SAVING) ready.finish(stopTime)
                else if (state.value.phase == RecordingPhase.PREPARING) {
                    AudioEngine.addPcmSink(ready)
                    view.beginRecording(ready) { main.post {
                        if (encoder === ready && state.value.phase == RecordingPhase.PREPARING) {
                            mutableState.value = state.value.copy(phase = RecordingPhase.RECORDING, startedNs = ready.originNs)
                        }
                    } }
                }
            } },
            failed = { message -> main.post { stop(message) } },
            completed = { saved, error -> main.post {
                encoder?.let(AudioEngine::removePcmSink)
                encoder = null; surface = null
                if (saved != null) prefs.edit().putString("uri", saved.uri.toString()).putString("name", saved.name).apply()
                mutableState.value = state.value.copy(phase = RecordingPhase.IDLE,
                    saved = saved ?: state.value.saved,
                    message = error ?: if (saved != null) "Saved to Movies/Prism" else "Recording canceled")
                onFinished()
            } }
        )
    }

    fun stop(message: String? = null) {
        val phase = state.value.phase
        if (phase == RecordingPhase.IDLE || phase == RecordingPhase.SAVING) return
        if (phase == RecordingPhase.CONNECTING) {
            mutableState.value = state.value.copy(phase = RecordingPhase.IDLE, message = message ?: "Recording canceled")
            onFinished()
            return
        }
        stopTime = System.nanoTime()
        mutableState.value = state.value.copy(phase = RecordingPhase.SAVING, message = message)
        encoder?.let(AudioEngine::removePcmSink)
        // queueEvent is processed before GLSurfaceView acknowledges onPause.
        val active = encoder
        val time = stopTime
        surface?.endRecording { active?.finish(time) }
    }

    fun dismissMessage() { mutableState.value = state.value.copy(message = null) }
}
