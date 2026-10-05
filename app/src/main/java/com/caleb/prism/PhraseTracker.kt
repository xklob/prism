package com.caleb.prism

import kotlin.math.*

data class PhraseState(
    val bars: Int = 16, val bar: Int? = null, val confidence: Float = 0f,
    val manual: Boolean = false, val message: String = "Waiting for bar lock"
)

/** Causal bar-level structure hypotheses. A track start or BPM never establishes a phrase. */
class PhraseTracker {
    private data class Bar(val index: Long, val features: DoubleArray)
    private val history = ArrayDeque<Bar>()
    private var previous: RhythmState? = null
    private var currentBar: Long? = null
    private var anchor: Long? = null
    private var manual = false
    private var confidence = 0.0
    private var sums = DoubleArray(4)
    private var frames = 0
    private var bars = 16
    private var lastBoundary: Long? = null
    private var lastGoodTime = Double.NEGATIVE_INFINITY

    fun reset() {
        history.clear(); previous = null; currentBar = null; anchor = null
        manual = false; confidence = 0.0; sums.fill(0.0); frames = 0
        lastBoundary = null; lastGoodTime = Double.NEGATIVE_INFINITY
    }

    fun align(timing: RhythmState, time: Double) {
        if (!timing.locked || !timing.barLocked) return
        anchor = floor((timing.positionAt(time) - timing.barOffset) / timing.beatsPerBar + .15).toLong()
        manual = true; confidence = 1.0
    }

    fun update(timing: RhythmState, levels: AudioLevels, phraseBars: Int): PhraseState {
        require(phraseBars in listOf(8, 16, 32))
        if (bars != phraseBars) { reset(); bars = phraseBars }
        val old = previous
        if (old != null) {
            val expected = old.position + (timing.timestamp - old.timestamp) * old.bpm / 60
            if (timing.timestamp < old.timestamp || abs(timing.position - expected) > .7 ||
                timing.beatsPerBar != old.beatsPerBar || timing.barOffset != old.barOffset) reset()
        }
        previous = timing
        val ready = timing.locked && timing.barLocked && timing.signalPresent && !timing.coasting && timing.conflict == RhythmConflict.NONE
        if (!ready) {
            if (timing.timestamp - lastGoodTime > 2) {
                // Do not retain a phrase origin through a breakdown with an untrusted bar grid.
                anchor = null; manual = false; confidence = 0.0
                history.clear(); currentBar = null; sums.fill(0.0); frames = 0; lastBoundary = null
            }
            return PhraseState(bars = bars)
        }
        lastGoodTime = timing.timestamp
        val index = floor((timing.position - timing.barOffset) / timing.beatsPerBar).toLong()
        if (currentBar != index) {
            if (currentBar != null && frames >= 20) finishBar(currentBar!!)
            currentBar = index; sums.fill(0.0); frames = 0
        }
        sums[0] += levels.energy; sums[1] += levels.bass; sums[2] += levels.mid; sums[3] += levels.high
        frames++
        val origin = anchor ?: return PhraseState(bars = bars, message = "Listening for a phrase change")
        return PhraseState(bars, Math.floorMod(index - origin, bars.toLong()).toInt() + 1,
            (confidence * min(timing.confidence, timing.barConfidence)).toFloat(), manual,
            if (manual) "Phrase aligned manually" else "Phrase estimated from audio changes")
    }

    private fun finishBar(index: Long) {
        val feature = DoubleArray(4) { sums[it] / frames }
        if (!manual && history.size >= 4) {
            val baseline = DoubleArray(4) { i -> history.takeLast(4).sumOf { it.features[i] } / 4 }
            val change = feature.indices.sumOf { i -> abs(feature[i] - baseline[i]) / max(.08, baseline[i]) } / 4
            val recentChange = history.lastOrNull()?.features?.let { prior ->
                feature.indices.sumOf { i -> abs(feature[i] - prior[i]) / max(.08, prior[i]) } / 4
            } ?: 0.0
            // Require a sustained whole-bar change, not one loud kick. Detection is retrospective
            // by one bar, so the counter is aligned to the observed boundary without a fake flash.
            if (change > .38 && recentChange > .24 && (lastBoundary == null || index - lastBoundary!! >= 4)) {
                val origin = anchor
                if (origin == null) { anchor = index; confidence = .30 }
                else {
                    val phase = Math.floorMod(index - origin, bars.toLong())
                    if (phase == 0L) confidence = (confidence + .16).coerceAtMost(.78)
                    else if (phase != 1L && phase != bars - 1L) { anchor = index; confidence = .30 }
                }
                lastBoundary = index
            } else if (anchor != null) confidence = (confidence - .003).coerceAtLeast(.15)
        }
        history.addLast(Bar(index, feature))
        while (history.size > 8) history.removeFirst()
    }
}
