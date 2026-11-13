package com.caleb.prism

import kotlin.math.*

/** Compact measurements in file time. No audio or platform clock is retained here. */
data class LocalFrame(val time: Double, val rhythm: RhythmState, val phrase: PhraseState,
    val bass: Float, val mid: Float, val high: Float, val energy: Float, val rms: Float, val peak: Float)

data class LocalTiming(val rhythm: RhythmState, val phrase: PhraseState)
data class LocalMarker(val time: Double, val kind: Int)

/** Uses the complete track to fill acquisition gaps. Unresolved meter/phrase stays unresolved. */
class LocalTimingMap(val frames: List<LocalFrame>, val duration: Double) {
    private data class Anchor(val time: Double, val rhythm: RhythmState, val phrase: PhraseState)
    private val anchors: List<Anchor>
    val bpm: Float
    init {
        val selected = ArrayList<Anchor>()
        for (frame in frames) {
            val raw = frame.rhythm
            if (!raw.locked || raw.confidence < .6f || raw.phaseConfidence < .5f || raw.conflict != RhythmConflict.NONE || raw.coasting) continue
            val previous = selected.lastOrNull()
            // Keep beat numbering continuous when a live tracker reacquires its origin.
            val expected = previous?.let { it.rhythm.position + (raw.timestamp - it.time) * it.rhythm.bpm / 60 }
            val shift = expected?.let { round(it - raw.position).toInt() } ?: 0
            val aligned = raw.copy(position = raw.position + shift,
                barOffset = Math.floorMod(raw.barOffset + shift, raw.beatsPerBar))
            selected.add(Anchor(raw.timestamp, aligned, frame.phrase))
        }
        anchors = selected
        bpm = selected.groupBy { (it.rhythm.bpm / 2).roundToInt() }.values.maxByOrNull { cluster ->
            cluster.sumOf { it.rhythm.confidence.toDouble() }
        }?.map { it.rhythm.bpm }?.sorted()?.let { it[it.size / 2] } ?: 0f
    }
    private fun closest(time: Double, list: List<Anchor> = anchors): Anchor? {
        if (list.isEmpty()) return null
        val index = list.binarySearchBy(time) { it.time }.let { if (it >= 0) it else -it - 1 }
        return listOfNotNull(list.getOrNull(index - 1), list.getOrNull(index)).minByOrNull { abs(it.time - time) }
    }
    private val bars by lazy { anchors.filter { it.rhythm.barLocked && it.rhythm.barConfidence >= .5f } }
    private val phrases by lazy { anchors.filter { it.phrase.bar != null && it.phrase.confidence >= .15f && it.rhythm.barLocked } }
    fun sample(seconds: Double): LocalTiming {
        val time = seconds.coerceIn(0.0, duration)
        val anchor = closest(time) ?: return LocalTiming(RhythmState(timestamp = time), PhraseState(message = "No confident phrase grid"))
        val distance = abs(time - anchor.time)
        if (distance > 64) return LocalTiming(RhythmState(timestamp = time), PhraseState(message = "Timing unresolved in this section"))
        val r = anchor.rhythm
        val position = r.position + (time - anchor.time) * r.bpm / 60
        val bar = closest(time, bars)?.takeIf {
            abs(it.time - time) <= 64 && it.rhythm.beatsPerBar == r.beatsPerBar && abs(it.rhythm.bpm / r.bpm - 1) < .04
        }
        val confidence = r.confidence * (1 - (distance / 160).coerceAtMost(.35)).toFloat()
        val result = r.copy(timestamp = time, position = position, confidence = confidence,
            phaseConfidence = minOf(confidence, r.phaseConfidence),
            barLocked = bar != null, barOffset = bar?.rhythm?.barOffset ?: r.barOffset,
            barConfidence = bar?.rhythm?.barConfidence?.times((1 - abs(time - bar.time) / 180).coerceIn(.5, 1.0).toFloat()) ?: 0f,
            manualTempo = false, manualBar = false, guided = false, tempoAssisted = false,
            signalPresent = true, coasting = false, holding = distance > 2,
            lastBeatTime = time - (position - floor(position)) * 60 / r.bpm,
            lastMatchedBeatTime = time - (position - floor(position)) * 60 / r.bpm)
        val origin = closest(time, phrases)?.takeIf {
            bar != null && abs(it.time - time) <= 128 && it.rhythm.beatsPerBar == result.beatsPerBar &&
                it.rhythm.barOffset == result.barOffset && abs(it.rhythm.bpm / result.bpm - 1) < .04
        }
        val phrase = if (origin == null) PhraseState(message = "Phrase boundary unresolved") else {
            val originBar = floor((origin.rhythm.position - origin.rhythm.barOffset) / result.beatsPerBar).toLong() - (origin.phrase.bar!! - 1)
            val currentBar = floor((position - result.barOffset) / result.beatsPerBar).toLong()
            PhraseState(origin.phrase.bars, Math.floorMod(currentBar - originBar, origin.phrase.bars.toLong()).toInt() + 1,
                minOf(origin.phrase.confidence, result.barConfidence), message = "Phrase estimated from full-track analysis")
        }
        return LocalTiming(result, phrase)
    }
    val markers: List<LocalMarker> by lazy {
        val result = ArrayList<LocalMarker>()
        var previous: RhythmState? = null
        for (frame in frames) {
            val timing = sample(frame.time)
            val r = timing.rhythm
            val old = previous
            if (r.locked && old?.locked == true && floor(r.position) == floor(old.position) + 1 && r.position - old.position < .5) {
                val downbeat = r.barLocked && Math.floorMod(floor(r.position).toInt() - r.barOffset, r.beatsPerBar) == 0
                val kind = if (downbeat && timing.phrase.bar == 1) 2 else if (downbeat) 1 else 0
                result.add(LocalMarker((frame.time - (r.position - floor(r.position)) * 60 / r.bpm).coerceAtLeast(0.0), kind))
            }
            previous = r
        }
        result
    }
    fun frame(seconds: Double): LocalFrame? {
        if (frames.isEmpty()) return null
        val index = frames.binarySearchBy(seconds) { it.time }.let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
        return frames[index.coerceAtMost(frames.lastIndex)]
    }
}
