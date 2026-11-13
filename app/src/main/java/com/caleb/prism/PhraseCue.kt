package com.caleb.prism

import kotlin.math.*

/** One-shot boundary gate. Reacquisition, seeks and phase corrections never count as a cue. */
internal class PhraseCue {
    var message = "Waiting for a stable phrase grid"; private set
    private var previousPosition = Double.NaN
    private var previousNow = Double.NaN
    private var previousBpm = 0f
    private var grid: String? = null
    private var stableSince = Double.NaN
    private var boundary = Double.NaN
    private var fired = false

    fun reset() {
        previousPosition = Double.NaN; previousNow = Double.NaN; previousBpm = 0f
        grid = null; stableSince = Double.NaN; boundary = Double.NaN; fired = false
        message = "Waiting for a stable phrase grid"
    }

    fun update(r: RhythmState, phrase: PhraseState, now: Double, running: Boolean, revision: Long, offsetMs: Float = 0f): Boolean {
        if (fired) return false
        val ready = running && now - r.timestamp in -.1.. .35 && r.locked && r.barLocked && r.signalPresent &&
            !r.coasting && r.conflict == RhythmConflict.NONE && r.barConfidence >= .55f &&
            phrase.confidence >= .55f && phrase.bar != null && phrase.bar in 1..phrase.bars && r.beatsPerBar in 3..4
        if (!ready) { reset(); message = if (!running) "Connect audio to cue on a phrase" else "Waiting for a stable phrase grid"; return false }
        val barIndex = floor((r.position - r.barOffset) / r.beatsPerBar).toLong()
        val origin = Math.floorMod(barIndex - (phrase.bar!! - 1), phrase.bars.toLong())
        val key = "$revision:${r.beatsPerBar}:${r.barOffset}:${phrase.bars}:$origin"
        val position = r.positionAt(now + offsetMs / 1000.0)
        val dt = now - previousNow
        val continuous = grid == key && dt in 0.0.. .25 &&
            abs(position - previousPosition - dt * r.bpm / 60) < .18 &&
            abs(r.bpm - previousBpm) < max(2f, previousBpm * .06f)
        if (!continuous) {
            stableSince = now; boundary = Double.NaN
            message = "Waiting for a stable phrase grid"
        }
        val phraseBeats = phrase.bars * r.beatsPerBar
        if (continuous && now - stableSince >= .4 && !boundary.isFinite()) {
            val start = origin * r.beatsPerBar + r.barOffset
            boundary = start + (floor((position - start) / phraseBeats) + 1) * phraseBeats
        }
        val crossed = continuous && boundary.isFinite() && previousPosition < boundary && position >= boundary
        val onTime = crossed && position - boundary <= .3
        if (crossed && !onTime) boundary += phraseBeats
        if (boundary.isFinite()) message = "Next phrase // ${ceil((boundary - position).coerceAtLeast(0.0)).toInt()} beats"
        grid = key; previousPosition = position; previousNow = now; previousBpm = r.bpm
        if (onTime) { fired = true; message = "Phrase cue applied" }
        return onTime
    }
}
