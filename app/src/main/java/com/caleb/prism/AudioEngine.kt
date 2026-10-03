package com.caleb.prism

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

data class CaptureStatus(val running: Boolean = false, val source: AudioSource? = null, val message: String? = null)

object AudioEngine {
    private val mutableStatus = MutableStateFlow(CaptureStatus())
    val status = mutableStatus.asStateFlow()
    @Volatile var levels = AudioLevels(); private set
    @Volatile var sensitivity = 1.4f
    @Volatile private var generation = 0
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private const val SAMPLE_RATE = 48000
    private const val BLOCK = 2048

    fun report(message: String) { mutableStatus.value = CaptureStatus(message = message) }

    fun startMicrophone(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report("Allow microphone access to connect audio."); return
        }
        start(AudioSource.MICROPHONE) {
            AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format()).setBufferSizeInBytes(bufferSize()).build()
        }
    }

    fun startSystem(context: Context, projection: MediaProjection) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report("Audio permission was revoked. Allow audio access and reconnect."); return
        }
        start(AudioSource.SYSTEM) {
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            AudioRecord.Builder().setAudioPlaybackCaptureConfig(capture)
                .setAudioFormat(format()).setBufferSizeInBytes(bufferSize()).build()
        }
    }

    private fun format() = AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()

    private fun bufferSize() = maxOf(
        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), BLOCK * 8
    )

    @Synchronized private fun start(source: AudioSource, create: () -> AudioRecord) {
        stop()
        var input: AudioRecord? = null
        try {
            val active = create().also { input = it }
            check(active.state == AudioRecord.STATE_INITIALIZED) { "Audio input could not initialize" }
            active.startRecording()
            check(active.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Audio input did not start" }
            recorder = active
            val session = ++generation
            mutableStatus.value = CaptureStatus(true, source)
            worker = thread(name = "Prism audio", isDaemon = true) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val analyzer = SpectrumAnalyzer(SAMPLE_RATE, BLOCK)
                val samples = ShortArray(BLOCK)
                var offset = 0
                try {
                    while (generation == session) {
                        val read = active.read(samples, offset, BLOCK - offset, AudioRecord.READ_BLOCKING)
                        if (read < 0) error("Audio input disconnected")
                        if (read == 0) { Thread.sleep(8); continue }
                        offset += read
                        if (offset == BLOCK) {
                            val next = analyzer.analyze(samples, sensitivity)
                            if (generation == session) levels = next
                            offset = 0
                        }
                    }
                } catch (_: Exception) {
                    if (generation == session) mutableStatus.value = CaptureStatus(message = "Audio disconnected. Tap Connect audio to try again.")
                } finally {
                    runCatching { active.stop() }
                    active.release()
                    if (generation == session) {
                        levels = AudioLevels()
                        if (mutableStatus.value.running) mutableStatus.value = CaptureStatus()
                    }
                }
            }
        } catch (_: Exception) {
            input?.release()
            mutableStatus.value = CaptureStatus(message = "Couldn't open audio. Check audio permissions and try again.")
        }
    }

    @Synchronized fun stop() {
        generation++
        val old = recorder
        recorder = null
        runCatching { old?.stop() }
        worker?.join(300)
        worker = null
        levels = AudioLevels()
        mutableStatus.value = CaptureStatus()
    }
}
