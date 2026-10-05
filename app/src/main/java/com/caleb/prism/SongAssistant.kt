package com.caleb.prism

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SongAssistState(
    val options: SongOptions = SongOptions(), val notificationAccess: Boolean = false,
    val song: SongIdentity? = null, val catalog: CatalogSong? = null,
    val positionMs: Long? = null, val approximatePosition: Boolean = false,
    val matchLabel: String = "", val message: String = "Song assist is off",
    val busy: Boolean = false, val requestsRemaining: Int = 40
)

/** Main-thread coordinator. No network, JSON, or token operations run on the audio/render thread. */
class SongAssistant(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val preferences = SongPreferences(this.context)
    private val budget = sharedBudget ?: RecognitionBudget(previousAttempts = preferences.requestTimes()).also { sharedBudget = it }
    private val services = SongServices()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(SongAssistState(options = preferences.options()))
    val state = mutableState.asStateFlow()
    private val buffer = RecognitionBuffer()
    private val sink = PcmSink { samples, time ->
        if (state.value.options.enabled && state.value.options.recognition && state.value.options.hasKey)
            buffer.append(samples, time, AudioEngine.status.value.channels)
    }
    private var capture = CaptureStatus()
    private var playback: SongPlayback? = null
    private var generation = 0
    private var work: Job? = null
    private var nextRecognition = 0.0
    private var recognizedAt: Double? = null
    private var recognizedOffset: Double? = null
    private var recognizedUntil = 0.0
    private var keyRejected = false
    private var lookupRetryAt = Double.POSITIVE_INFINITY
    private var lookupQuery: SongIdentity? = null
    private data class Cached(val song: CatalogSong?, val expires: Double)
    private val cache = linkedMapOf<String, Cached>()

    init {
        AudioEngine.phraseBars = state.value.options.phraseBars
        AudioEngine.addPcmSink(sink)
        scope.launch {
            while (isActive) {
                tick()
                delay(500)
            }
        }
    }

    fun setOptions(options: SongOptions) {
        val old = state.value.options
        val next = options.copy(hasKey = old.hasKey)
        preferences.save(next)
        mutableState.value = state.value.copy(options = next)
        AudioEngine.phraseBars = next.phraseBars
        if (old.enabled != next.enabled || old.recognition != next.recognition) {
            invalidate(); playback = null; buffer.clear(); nextRecognition = 0.0
        }
        tick()
    }

    fun saveToken(token: String): String? {
        return try {
            preferences.setToken(token.trim())
            val options = state.value.options.copy(hasKey = token.isNotBlank(), recognition = token.isNotBlank() && state.value.options.recognition)
            preferences.save(options)
            mutableState.value = state.value.copy(options = options)
            keyRejected = false; nextRecognition = 0.0
            invalidate(); playback = null; buffer.clear(); tick(); null
        } catch (_: Exception) { "Couldn't save the key. Check the token and try again." }
    }

    fun identifyNow() { identify(System.nanoTime() / 1e9, manual = true) }

    private fun invalidate() {
        generation++; work?.cancel(); work = null
        AudioEngine.tempoHint = null; AudioEngine.songTimingRevision++
        recognizedAt = null; recognizedOffset = null; recognizedUntil = 0.0
        lookupQuery = null; lookupRetryAt = Double.POSITIVE_INFINITY
        mutableState.value = state.value.copy(song = null, catalog = null, positionMs = null,
            approximatePosition = false, matchLabel = "", busy = false, message = "Listening for a song")
    }

    private fun tick() {
        val now = System.nanoTime() / 1e9
        val status = AudioEngine.status.value
        val access = NowPlaying.permitted(context)
        mutableState.value = state.value.copy(notificationAccess = access, requestsRemaining = budget.remaining(System.currentTimeMillis() / 1000.0))
        if (status.running != capture.running || status.source != capture.source) {
            capture = status; invalidate(); playback = null; buffer.clear(); nextRecognition = now
        }
        if (!state.value.options.enabled || !status.running) {
            AudioEngine.tempoHint = null
            mutableState.value = state.value.copy(message = if (!state.value.options.enabled) "Song assist is off" else "Connect audio to find the song")
            return
        }
        if (status.source == AudioSource.SYSTEM) {
            val latest = if (access) NowPlaying.playback.value else null
            if (latest?.key != playback?.key) {
                invalidate(); buffer.clear(); nextRecognition = now
                playback = latest
                if (latest != null) {
                    mutableState.value = state.value.copy(song = latest.song, matchLabel = "Player metadata")
                    lookup(latest.song)
                }
            } else if (latest != playback) {
                if (latest?.discontinuityFrom(playback, now) == true) {
                    // A seek/pause/rate change invalidates an old acoustic position and phrase origin.
                    invalidate(); buffer.clear(); nextRecognition = now
                    mutableState.value = state.value.copy(song = latest.song, matchLabel = "Player metadata")
                    lookup(latest.song)
                }
                playback = latest
            }
        }
        if (recognizedUntil > 0 && now > recognizedUntil) {
            invalidate(); playback = null; nextRecognition = now
        }
        val position = if (recognizedAt != null) recognizedOffset?.let { ((it + now - recognizedAt!!) * 1000).toLong() }
            else playback?.positionAt(now)
        if (recognizedAt != null && position != null && state.value.song?.durationMs?.let { position > it + 2000 } == true) {
            // The matched recording has ended. Collect a fresh clip instead of extending its BPM forever.
            invalidate(); playback = null; buffer.clear(); nextRecognition = now
            return
        }
        mutableState.value = state.value.copy(positionMs = position?.coerceAtMost(state.value.song?.durationMs ?: Long.MAX_VALUE))
        updateHint()
        if (work?.isActive != true && now >= lookupRetryAt) lookupQuery?.let { lookup(it) }
        val needsRecognition = status.source == AudioSource.MICROPHONE || playback == null ||
            playback?.packageName?.contains("youtube") == true || state.value.catalog == null
        if (needsRecognition && now >= nextRecognition && playback?.playing != false) identify(now)
    }

    private fun updateHint() {
        val catalog = state.value.catalog
        val paused = capture.source == AudioSource.SYSTEM && playback?.playing == false
        val speed = if (recognizedAt == null) playback?.speed ?: 1.0 else 1.0
        AudioEngine.tempoHint = if (paused) null else catalog?.bpm?.let { TempoHint(it * speed, catalog.song.key) }
    }

    private fun lookup(query: SongIdentity) {
        if (work?.isActive == true) return
        lookupQuery = query
        val expected = generation
        work = scope.launch {
            mutableState.value = state.value.copy(busy = true, message = "Looking up song length and BPM")
            try {
                applyCatalog(catalog(query), expected)
                lookupRetryAt = Double.POSITIVE_INFINITY
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (expected == generation) {
                    mutableState.value = state.value.copy(message = serviceMessage(e))
                    lookupRetryAt = System.nanoTime() / 1e9 + retrySeconds(e)
                }
            } finally { if (expected == generation) mutableState.value = state.value.copy(busy = false) }
        }
    }

    private suspend fun catalog(query: SongIdentity): CatalogSong? {
        val now = System.nanoTime() / 1e9
        cache[query.key]?.takeIf { it.expires > now }?.let { return it.song }
        val result = withContext(Dispatchers.IO) { services.lookup(query) }
        cache[query.key] = Cached(result, now + if (result != null) 86400 else 300)
        while (cache.size > 100) cache.remove(cache.keys.first())
        return result
    }

    private fun applyCatalog(result: CatalogSong?, expected: Int) {
        if (expected != generation) return
        mutableState.value = state.value.copy(catalog = result,
            song = result?.song ?: state.value.song,
            message = when { result == null -> "No confident catalog match. Live timing still works."
                result.bpm == null -> "Song found; no catalog BPM. Using live timing."
                else -> "Catalog BPM available; listening for beat and bar alignment" })
        updateHint()
    }

    private fun identify(now: Double, manual: Boolean = false) {
        val options = state.value.options
        if (!options.enabled || !options.recognition || !options.hasKey || keyRejected && !manual || !capture.running || work?.isActive == true) return
        val clip = buffer.clip((now * 1e9).toLong())
        if (clip == null) {
            if (state.value.song == null || manual) mutableState.value = state.value.copy(message = "Listening: identification needs 8–10 seconds of music")
            return
        }
        val budgetTime = System.currentTimeMillis() / 1000.0
        val wait = budget.waitSeconds(budgetTime)
        if (wait > 0) {
            if (manual) mutableState.value = state.value.copy(message = "Recognition limit: try again in ${kotlin.math.ceil(wait).toInt()} seconds")
            return
        }
        val token = preferences.token() ?: return
        if (!budget.acquire(budgetTime)) return
        preferences.saveRequestTimes(budget.timestamps())
        keyRejected = false
        nextRecognition = now + 90
        val expected = generation
        work = scope.launch {
            mutableState.value = state.value.copy(busy = true, message = "Identifying a short audio clip with AudD", requestsRemaining = budget.remaining(budgetTime))
            try {
                val result = withContext(Dispatchers.IO) { services.recognize(clip, token) }
                if (expected != generation) return@launch
                if (result == null) {
                    if (recognizedAt != null) { invalidate(); playback = null }
                    mutableState.value = state.value.copy(message = "No song identified. Live timing still works.")
                    return@launch
                }
                val expectedOffset = recognizedAt?.let { start -> recognizedOffset?.let { it + clip.startNs / 1e9 - start } }
                if (state.value.song?.key != result.song.key || expectedOffset != null && result.offsetSeconds != null &&
                    kotlin.math.abs(expectedOffset - result.offsetSeconds) > 2.0) AudioEngine.songTimingRevision++
                recognizedAt = clip.startNs / 1e9
                recognizedOffset = result.offsetSeconds
                // Never keep an unidentified DJ transition tied to a stale catalog indefinitely.
                recognizedUntil = now + 180
                mutableState.value = state.value.copy(song = result.song, catalog = null, matchLabel = "Identified by AudD",
                    approximatePosition = true, message = "Song identified; looking up BPM")
                AudioEngine.tempoHint = null
                lookupQuery = result.song
                try {
                    applyCatalog(catalog(result.song), expected)
                    lookupRetryAt = Double.POSITIVE_INFINITY
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // A free catalog outage should retry the lookup, not spend another recognition request.
                    if (expected == generation) {
                        lookupRetryAt = System.nanoTime() / 1e9 + retrySeconds(e)
                        mutableState.value = state.value.copy(message = serviceMessage(e))
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (expected == generation) {
                    keyRejected = (e as? SongServiceException)?.needsKey == true
                    nextRecognition = now + retrySeconds(e)
                    mutableState.value = state.value.copy(message = serviceMessage(e))
                }
            } finally { if (expected == generation) mutableState.value = state.value.copy(busy = false) }
        }
    }

    private fun serviceMessage(e: Exception) = (e as? SongServiceException)?.message ?: "Couldn't reach the song service. Live timing still works."
    private fun retrySeconds(e: Exception) = (e as? SongServiceException)?.retrySeconds ?: 90

    override fun close() { scope.cancel(); AudioEngine.removePcmSink(sink); buffer.clear(); AudioEngine.tempoHint = null; AudioEngine.songTimingRevision++ }

    companion object { private var sharedBudget: RecognitionBudget? = null }
}
