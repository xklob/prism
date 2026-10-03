package com.caleb.prism

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import android.util.Log
import kotlin.concurrent.thread

data class CaptureStatus(val running: Boolean = false, val source: AudioSource? = null, val message: String? = null)

object AudioEngine {
    private val mutableStatus = MutableStateFlow(CaptureStatus())
    val status = mutableStatus.asStateFlow()
    @Volatile var levels = AudioLevels(); private set
    @Volatile var sensitivity = 1.4f
    @Volatile var beatsPerBar = 0
    @Volatile var rhythm = RhythmState(); private set
    @Volatile var inferenceMs = 0f; private set
    private data class TimingCommand(val action: Int, val time: Double)
    private val commands=ConcurrentLinkedQueue<TimingCommand>()
    fun tapTempo() { commands.add(TimingCommand(0,System.nanoTime()/1e9)) }
    fun alignBar() { commands.add(TimingCommand(1,System.nanoTime()/1e9)) }
    fun automaticTiming() { commands.add(TimingCommand(2,System.nanoTime()/1e9)) }
    fun halfTempo() { commands.add(TimingCommand(3,System.nanoTime()/1e9)) }
    fun doubleTempo() { commands.add(TimingCommand(4,System.nanoTime()/1e9)) }
    @Volatile private var generation = 0
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private const val SAMPLE_RATE = 48000
    private const val BLOCK = 960

    fun report(message: String) { mutableStatus.value = CaptureStatus(message = message) }

    fun startMicrophone(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report("Allow microphone access to connect audio."); return
        }
        start(context, AudioSource.MICROPHONE) {
            AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format()).setBufferSizeInBytes(bufferSize()).build()
        }
    }

    fun startSystem(context: Context, projection: MediaProjection) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report("Audio permission was revoked. Allow audio access and reconnect."); return
        }
        start(context, AudioSource.SYSTEM) {
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

    @Synchronized private fun start(context: Context, source: AudioSource, create: () -> AudioRecord) {
        stop()
        var input: AudioRecord? = null
        try {
            val active = create().also { input = it }
            check(active.state == AudioRecord.STATE_INITIALIZED) { "Audio input could not initialize" }
            recorder = active
            val session = ++generation
            mutableStatus.value = CaptureStatus(true, source)
            worker = thread(name = "Prism audio", isDaemon = true) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val analyzer = SpectrumAnalyzer(SAMPLE_RATE, 2048, BLOCK)
                val spectrumWindow=ShortArray(2048)
                val samples = ShortArray(BLOCK)
                var offset = 0
                var rhythmAnalyzer: RhythmAnalyzer? = null
                var audioOrigin=Double.NaN
                var samplesRead=0L
                val captureTimestamp=AudioTimestamp()
                try {
                    val timing=RhythmAnalyzer(context.applicationContext).also { rhythmAnalyzer=it }
                    if (generation != session) return@thread
                    active.startRecording()
                    check(active.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Audio input did not start" }
                    while (generation == session) {
                        val read = active.read(samples, offset, BLOCK - offset, AudioRecord.READ_BLOCKING)
                        if (read < 0) error("Audio input disconnected")
                        if (read == 0) { Thread.sleep(8); continue }
                        offset += read
                        if (offset == BLOCK) {
                            if (audioOrigin.isNaN()) audioOrigin=System.nanoTime()/1e9-BLOCK.toDouble()/SAMPLE_RATE
                            // Use the capture clock when available, including buffered samples and clock drift.
                            if (active.getTimestamp(captureTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                audioOrigin=captureTimestamp.nanoTime/1e9-captureTimestamp.framePosition.toDouble()/SAMPLE_RATE
                            }
                            while (true) {
                                val command=commands.poll() ?: break
                                when (command.action) {
                                    0 -> timing.tracker.tap(command.time)
                                    1 -> timing.tracker.alignBar(command.time)
                                    2 -> timing.automaticTiming()
                                    3 -> timing.tracker.scaleTempo(0.5,command.time)
                                    4 -> timing.tracker.scaleTempo(2.0,command.time)
                                }
                            }
                            timing.process(samples,audioOrigin+samplesRead.toDouble()/SAMPLE_RATE,sensitivity,beatsPerBar) {
                                if (generation == session) rhythm=it
                            }
                            samplesRead+=BLOCK
                            spectrumWindow.copyInto(spectrumWindow,0,BLOCK,spectrumWindow.size)
                            samples.copyInto(spectrumWindow,spectrumWindow.size-BLOCK)
                            val next = analyzer.analyze(spectrumWindow, sensitivity)
                            if (generation == session) levels = next
                            if (generation == session) inferenceMs=timing.inferenceMs
                            offset = 0
                        }
                    }
                } catch (error: Exception) {
                    Log.e("PrismRhythm","Audio or rhythm detector failed",error)
                    if (generation == session) mutableStatus.value = CaptureStatus(message = "Audio timing disconnected. Tap Connect audio to try again.")
                } finally {
                    rhythmAnalyzer?.close()
                    runCatching { active.stop() }
                    active.release()
                    if (generation == session) {
                        levels = AudioLevels()
                        rhythm = RhythmState()
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
        rhythm = RhythmState()
        inferenceMs = 0f
        commands.clear()
        mutableStatus.value = CaptureStatus()
    }
}
