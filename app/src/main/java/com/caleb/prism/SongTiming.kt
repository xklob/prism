package com.caleb.prism

import java.text.Normalizer
import kotlin.math.*

/** A catalog tempo is a weak prior, never an observation of a beat or downbeat. */
data class TempoHint(val bpm: Double, val key: String) {
    fun weight(candidate: Double): Double {
        if (!bpm.isFinite() || bpm !in 30.0..300.0) return 0.0
        val normalized = when { bpm < 60 -> bpm * 2; bpm > 200 -> bpm / 2; else -> bpm }
        // A flat neighborhood lets the audio choose the exact rate, including DJ pitch changes.
        // The prior mainly distinguishes tempo families, rather than pulling a good grid off beat.
        val distance = max(0.0, abs(ln(candidate / normalized)) - .05)
        return .10 * exp(-.5 * (distance / .04).pow(2))
    }
    fun agrees(candidate: Double): Boolean {
        if (!bpm.isFinite() || bpm !in 30.0..300.0 || candidate <= 0) return false
        val normalized = when { bpm < 60 -> bpm * 2; bpm > 200 -> bpm / 2; else -> bpm }
        return abs(ln(candidate / normalized)) < .03
    }
}

data class SongIdentity(
    val title: String, val artist: String = "", val durationMs: Long? = null,
    val spotifyId: String? = null, val isrc: String? = null
) {
    val key: String get() = spotifyId ?: isrc ?: "$title|$artist|$durationMs"
}

data class SongPlayback(
    val song: SongIdentity, val packageName: String, val mediaId: String,
    val positionMs: Long?, val speed: Double, val playing: Boolean, val sampledAt: Double
) {
    val key get() = "$packageName|$mediaId|${song.key}"
    fun positionAt(now: Double): Long? = positionMs?.let {
        val elapsed = if (playing) (now - sampledAt).coerceAtLeast(0.0) * speed else 0.0
        (it + elapsed * 1000).toLong().coerceIn(0, song.durationMs ?: Long.MAX_VALUE)
    }
    fun discontinuityFrom(previous: SongPlayback?, now: Double): Boolean {
        if (previous == null || key != previous.key || playing != previous.playing) return true
        val before = previous.positionAt(now) ?: return false
        val after = positionAt(now) ?: return false
        return abs(after - before) > 2000 || abs(speed - previous.speed) > .01
    }
}

data class CatalogSong(val song: SongIdentity, val bpm: Double?, val catalogId: String)
data class RecognizedSong(val song: SongIdentity, val offsetSeconds: Double?)

/** Conservative matching: do not silently apply the original's BPM to a remix or edit. */
object SongMatching {
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}"), "").lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex(" +"), " ")

    fun choose(query: SongIdentity, candidates: List<CatalogSong>): CatalogSong? {
        query.spotifyId?.let { id -> candidates.firstOrNull { it.song.spotifyId == id }?.let { return it } }
        query.isrc?.let { id -> candidates.firstOrNull { it.song.isrc?.equals(id, true) == true }?.let { return it } }
        if (query.artist.isBlank()) return null
        val matches = candidates.filter {
            normalize(query.title) == normalize(it.song.title) &&
                it.song.artist.split(" / ").any { artist ->
                    normalize(artist) == normalize(query.artist) || normalize(it.song.artist) == normalize(query.artist)
                } &&
                (query.durationMs == null || it.song.durationMs == null || abs(query.durationMs - it.song.durationMs) <= 3000)
        }
        return matches.singleOrNull() ?: matches.takeIf { list -> list.isNotEmpty() && list.map { it.song.isrc }.distinct().size == 1 && list.first().song.isrc != null }?.first()
    }

    fun spotifyId(value: String?): String? {
        if (value == null) return null
        return Regex("(?:spotify:track:|https://open\\.spotify\\.com/(?:intl-[^/]+/)?track/)([A-Za-z0-9]{22})(?:[?/#].*)?")
            .matchEntire(value)?.groupValues?.get(1)
    }

    fun timecode(value: String?): Double? {
        val parts = value?.split(':') ?: return null
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toDoubleOrNull() ?: return null }
        if (numbers.any { !it.isFinite() || it < 0 } || numbers.drop(1).any { it >= 60 }) return null
        return numbers.fold(0.0) { total, n -> total * 60 + n }
    }
}

/** Limits paid requests even when automatic retries and the manual button overlap. */
class RecognitionBudget(private val maxPerHour: Int = 40, previousAttempts: List<Double> = emptyList()) {
    private val attempts = ArrayDeque(previousAttempts.filter { it.isFinite() && it >= 0 }.sorted().takeLast(maxPerHour))
    fun timestamps(): List<Double> = attempts.toList()
    fun remaining(now: Double): Int { expire(now); return (maxPerHour - attempts.size).coerceAtLeast(0) }
    fun waitSeconds(now: Double): Double {
        expire(now)
        val spacing = attempts.lastOrNull()?.let { it + 30 - now } ?: 0.0
        val limit = if (attempts.size >= maxPerHour) attempts.first() + 3600 - now else 0.0
        return maxOf(0.0, spacing, limit)
    }
    fun acquire(now: Double): Boolean {
        if (waitSeconds(now) > 0) return false
        attempts.addLast(now); return true
    }
    private fun expire(now: Double) { while (attempts.isNotEmpty() && now - attempts.first() >= 3600) attempts.removeFirst() }
}
