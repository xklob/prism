package com.caleb.prism

import android.content.*
import android.media.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.*

data class LocalAudioState(val title: String = "Choose an audio file", val analyzing: Boolean = false,
    val progress: Float = 0f, val message: String = "Analyze a track before playing it.",
    val ready: Boolean = false, val playing: Boolean = false, val position: Double = 0.0,
    val duration: Double = 0.0, val bpm: Float = 0f, val waveform: Boolean? = null,
    val meter: Int = 0, val phraseBars: Int = 16)

/** One local track. All music data stays in private cache and is removed on replacement/unload. */
object LocalAudio {
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(LocalAudioState())
    val state = mutableState.asStateFlow()
    @Volatile internal var track: AnalyzedTrack? = null; private set
    private var analysis: Job? = null
    private var analysisRevision = 0
    @Volatile private var generation = 0
    @Volatile private var output: AudioTrack? = null
    @Volatile private var playbackPosition = 0.0
    private var worker: Thread? = null
    private var focus: AudioFocusRequest? = null
    private var initialized = false
    private var playRequested = false
    var recordingLocked = false
    internal var transportChanged: (() -> Unit)? = null
    private val attributes get() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    fun init(value: Context) {
        if (initialized) return
        initialized = true; context = value.applicationContext
        File(context.cacheDir, "local-audio").listFiles()?.forEach { it.delete() }
        val prefs = context.getSharedPreferences("local-audio", 0)
        mutableState.value = state.value.copy(title = prefs.getString("title", null) ?: state.value.title,
            waveform = if (prefs.contains("waveform")) prefs.getBoolean("waveform", false) else null,
            meter = prefs.getInt("meter", 0).takeIf { it in listOf(0,3,4) } ?: 0,
            phraseBars = prefs.getInt("phraseBars", 16).takeIf { it in listOf(8,16,32) } ?: 16)
    }
    fun waveform(value: Boolean) {
        context.getSharedPreferences("local-audio", 0).edit().putBoolean("waveform", value).apply()
        mutableState.value = state.value.copy(waveform = value)
    }
    fun configure(meter: Int = state.value.meter, phraseBars: Int = state.value.phraseBars) {
        if (recordingLocked || state.value.analyzing) return
        require(meter in listOf(0,3,4) && phraseBars in listOf(8,16,32))
        context.getSharedPreferences("local-audio", 0).edit().putInt("meter", meter).putInt("phraseBars", phraseBars).apply()
        mutableState.update { it.copy(meter = meter, phraseBars = phraseBars) }
    }
    fun load(uri: Uri, meter: Int = state.value.meter, phraseBars: Int = state.value.phraseBars) {
        if (recordingLocked) return
        cancelAnalysis(); pause(); context.stopService(Intent(context, LocalAudioService::class.java)); track?.pcm?.delete(); track = null
        val title = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull() ?: "Local track"
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.getSharedPreferences("local-audio", 0).edit().putString("uri", uri.toString()).putString("title", title).apply()
        mutableState.value = LocalAudioState(title, analyzing = true, message = "Decoding track", waveform = state.value.waveform, meter = meter, phraseBars = phraseBars)
        val revision = analysisRevision
        analysis = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    var result: AnalyzedTrack? = null
                    try {
                        LocalFileAnalyzer.analyze(context, uri, title, meter, phraseBars) { message, progress ->
                            if (analysisRevision == revision) mutableState.update { it.copy(message = message, progress = progress) }
                        }.also { result = it }
                    } finally { if (!isActive) result?.pcm?.delete() }
                }
                track = result
                mutableState.value = state.value.copy(analyzing = false, ready = true, progress = 1f,
                    duration = result.duration, bpm = result.map.bpm,
                    message = if (result.map.bpm > 0) "Ready // timing estimated on device" else "Ready // no confident beat grid")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableState.value = state.value.copy(analyzing = false, ready = false,
                    message = error.message ?: "Couldn't analyze this audio file. Try another file.")
            }
        }
    }
    val hasPreviousFile get() = initialized && context.getSharedPreferences("local-audio", 0).getString("uri", null) != null
    fun reanalyze() {
        val uri = context.getSharedPreferences("local-audio", 0).getString("uri", null) ?: return
        load(Uri.parse(uri))
    }
    fun cancelAnalysis() {
        analysisRevision++
        analysis?.cancel(); analysis = null
        if (state.value.analyzing) mutableState.value = state.value.copy(analyzing = false, message = "Analysis canceled")
    }
    fun play() {
        if (track == null || state.value.playing || recordingLocked) return
        playRequested = true
        runCatching { ContextCompat.startForegroundService(context, Intent(context, LocalAudioService::class.java).setAction("play")) }
            .onFailure { mutableState.value = state.value.copy(message = "Open prism and press Play to start the track.") }
    }
    internal fun startPlayback() {
        val file = track ?: return
        if (!playRequested || state.value.playing) return
        val manager = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change -> if (change < 0) pause() }, main).build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mutableState.value = state.value.copy(message = "Audio is in use. Try Play again."); transportChanged?.invoke(); return
        }
        focus = request
        val start = if (state.value.position >= file.duration - .02) 0.0 else state.value.position
        playbackPosition = start
        val session = ++generation
        val engineEpoch = AudioEngine.startLocal(file.channels)
        mutableState.value = state.value.copy(playing = true, position = start, message = "Playing // analyzed timing")
        transportChanged?.invoke()
        worker = thread(name = "prism local audio", isDaemon = true) {
            var audio: AudioTrack? = null
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val mask = if (file.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                audio = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(7680 * file.channels, AudioTrack.getMinBufferSize(48000, mask, AudioFormat.ENCODING_PCM_16BIT)))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                val active = audio
                if (android.os.Build.VERSION.SDK_INT >= 31) active.setStartThresholdInFrames(960)
                check(active.state == AudioTrack.STATE_INITIALIZED) { "Couldn't open audio output." }
                if (generation != session) return@thread
                output = active
                val startFrame = (start * 48000).toLong()
                var written = 0L
                var cursor = start
                val timestamp = AudioTimestamp()
                var clockNs = System.nanoTime()
                var clockFrame = 0L
                var timestampValid = false
                var nextTimestamp = 0L
                var lastUi = 0L
                val routeChanged = java.util.concurrent.atomic.AtomicBoolean(false)
                active.addOnRoutingChangedListener({ routeChanged.set(true) }, main)
                active.play()
                RandomAccessFile(file.pcm, "r").use { input ->
                    input.seek(startFrame * file.channels * 2)
                    val bytes = ByteArray(960 * file.channels * 2)
                    var eof = false
                    while (generation == session) {
                        val now = System.nanoTime()
                        if (routeChanged.getAndSet(false)) { timestampValid = false; nextTimestamp = 0 }
                        if (now >= nextTimestamp) {
                            if (active.getTimestamp(timestamp) && timestamp.framePosition > 0) {
                                timestampValid = true; clockFrame = timestamp.framePosition; clockNs = timestamp.nanoTime
                            }
                            nextTimestamp = now + if (timestampValid) 10_000_000_000 else 100_000_000
                        }
                        val head = if (timestampValid) (clockFrame + (now - clockNs) * 48000 / 1_000_000_000).coerceIn(0, written)
                            else (active.playbackHeadPosition.toLong() and 0xffffffffL).coerceAtMost(written)
                        cursor = ((startFrame + head) / 48000.0).coerceAtMost(file.duration)
                        playbackPosition = cursor
                        val measured = file.map.frame(cursor)
                        if (generation == session && measured != null) AudioEngine.publishLocal(file.map.sample(cursor), measured, now / 1e9, engineEpoch)
                        if (now - lastUi >= 50_000_000) {
                            val position = cursor
                            main.post { if (generation == session) mutableState.value = state.value.copy(position = position) }
                            lastUi = now
                        }
                        if (eof) {
                            if (head >= written - 48) break
                            Thread.sleep(5); continue
                        }
                        // Bound queued audio. Reading the file never runs ahead of the playback clock indefinitely.
                        if (written - head >= if (head == 0L) maxOf(3840, active.bufferSizeInFrames) else 3840) { Thread.sleep(5); continue }
                        val read = input.read(bytes)
                        if (read < 0) {
                            // Older Android versions require a full buffer to start very short clips.
                            if (head == 0L && written < active.bufferSizeInFrames && android.os.Build.VERSION.SDK_INT < 31) {
                                val silence = ShortArray((active.bufferSizeInFrames - written.toInt()) * file.channels)
                                active.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                                written += silence.size / file.channels
                            }
                            eof = true; continue
                        }
                        val samples = ShortArray(read / 2)
                        ByteBuffer.wrap(bytes, 0, read).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                        var offset = 0
                        while (offset < samples.size && generation == session) {
                            val count = active.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                            check(count > 0) { "Audio output disconnected." }; offset += count
                        }
                        if (generation != session) break
                        val firstNs = if (timestampValid) clockNs + (written - clockFrame) * 1_000_000_000 / 48000
                            else now + (written - head) * 1_000_000_000 / 48000
                        AudioEngine.publishLocalPcm(samples, firstNs, engineEpoch)
                        written += samples.size / file.channels
                    }
                }
                main.post { if (generation == session) { mutableState.value = state.value.copy(position = file.duration); pause() } }
            } catch (error: Exception) {
                main.post { if (generation == session) { pause(); mutableState.value = state.value.copy(message = error.message ?: "Audio output stopped.") } }
            } finally {
                runCatching { audio?.pause() }; runCatching { audio?.flush() }; audio?.release()
                if (output === audio) output = null
            }
        }
    }
    fun pause() {
        playRequested = false
        generation++
        runCatching { output?.pause() }; runCatching { output?.flush() }
        worker?.join(400); worker = null
        focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
        if (AudioEngine.status.value.source == AudioSource.LOCAL) AudioEngine.stop()
        mutableState.value = state.value.copy(playing = false, position = if (state.value.playing) playbackPosition else state.value.position)
        transportChanged?.invoke()
    }
    fun seek(seconds: Double) {
        if (recordingLocked || !state.value.ready) return
        val playing = state.value.playing
        pause()
        mutableState.value = state.value.copy(position = seconds.coerceIn(0.0, state.value.duration))
        if (playing) play()
    }
    fun unload() {
        if (recordingLocked) return
        cancelAnalysis(); pause(); context.stopService(Intent(context, LocalAudioService::class.java)); track?.pcm?.delete(); track = null
        mutableState.value = LocalAudioState(waveform = state.value.waveform, meter = state.value.meter, phraseBars = state.value.phraseBars)
    }
}
