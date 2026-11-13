package com.caleb.prism

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper

/** Watches the capture lifecycle, never the loudness of its samples. */
internal class CaptureWatch(
    context: Context, private val input: AudioRecord, private val source: AudioSource,
    private val disconnected: (String) -> Unit
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessionId = input.audioSessionId
    @Volatile private var closed = false
    @Volatile private var started = false
    @Volatile private var lastReadNs = 0L
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (closed || !started) return
            if (configs.any { it.clientAudioSessionId == sessionId && it.isClientSilenced })
                end("${source.label} was blocked by Android. Check audio access, then reconnect.")
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed || !started) return
            val reason = runCatching { when {
                input.recordingState != AudioRecord.RECORDSTATE_RECORDING -> "${source.label} stopped. Tap Connect audio to reconnect."
                source == AudioSource.MICROPHONE && audio.isMicrophoneMute -> "Microphone access is off. Turn it on, then reconnect."
                input.activeRecordingConfiguration?.isClientSilenced == true -> "${source.label} was blocked by Android. Check audio access, then reconnect."
                System.nanoTime() - lastReadNs > 5_000_000_000L -> "${source.label} stopped delivering audio. Tap Connect audio to reconnect."
                else -> null
            } }.getOrElse { "${source.label} disconnected. Tap Connect audio to reconnect." }
            if (reason != null) end(reason) else main.postDelayed(this, 500)
        }
    }

    init { input.registerAudioRecordingCallback(context.mainExecutor, callback) }
    fun started() {
        if (closed) return
        lastReadNs = System.nanoTime()
        started = true
        main.post(poll)
    }
    fun receivedSamples() { lastReadNs = System.nanoTime() }
    private fun end(message: String) {
        if (closed) return
        close()
        disconnected(message)
    }
    override fun close() {
        closed = true
        main.removeCallbacks(poll)
        runCatching { input.unregisterAudioRecordingCallback(callback) }
    }
}
