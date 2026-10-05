package com.caleb.prism

import kotlin.math.*

data class CrowdCue(
    val revision: Long, val effective: Double, val anchor: Double, val beat: Double, val bpm: Double,
    val meter: Int, val phraseBars: Int, val phraseOrigin: Long, val running: Boolean,
    val diagnostic: Boolean, val settings: VisualSettings, val previousScene: Int, val transitionAt: Double,
    val flashEvery: Double, val flashMs: Double, val invertMs: Double
) {
    fun position(time: Double) = beat + (time - anchor) * bpm / 60000
}
data class CrowdSnapshot(
    val sequence: Long, val sent: Double, val validUntil: Double, val received: Double,
    val cues: List<CrowdCue>, val blackout: Boolean, val musicReady: Boolean,
    val beatConfidence: Float, val barConfidence: Float, val phraseConfidence: Float
) {
    fun cueAt(time: Double) = cues.lastOrNull { it.effective <= time } ?: cues.first()
}
data class CrowdFrame(val cue: CrowdCue, val time: Double, val motion: MusicalMotion,
    val dark: Boolean, val phraseBar: Int)

/** Samples absolute envelopes. A delayed frame cannot restart a flash at full intensity. */
class CrowdTimeline {
    private var armedAfter = Double.POSITIVE_INFINITY
    private var wasReady = false
    private var previousTime = Double.NaN
    private var previousRevision = -1L
    private var lastFlash = Double.NEGATIVE_INFINITY
    var missed = 0L; private set
    fun reset() {
        armedAfter = Double.POSITIVE_INFINITY; wasReady = false
        previousTime = Double.NaN; previousRevision = -1; lastFlash = Double.NEGATIVE_INFINITY; missed = 0
    }
    fun sample(snapshot: CrowdSnapshot, time: Double, clockReady: Boolean, flashes: Boolean): CrowdFrame {
        val cue = snapshot.cueAt(time)
        val fresh = time <= snapshot.validUntil && time >= snapshot.sent - 100 && clockReady
        if (fresh && !wasReady) armedAfter = time
        if (!snapshot.musicReady || snapshot.beatConfidence < .5 || snapshot.barConfidence < .5) armedAfter = time
        if (previousTime.isFinite() && time < previousTime - 1) armedAfter = max(armedAfter, previousTime)
        wasReady = fresh
        val position = cue.position(time)
        val phase = position - floor(position)
        val barPhase = position - floor(position / cue.meter) * cue.meter
        val beat = if (fresh && snapshot.musicReady && cue.running) exp(-phase / cue.settings.pulseLength) * snapshot.beatConfidence else 0.0
        val bar = if (fresh && snapshot.musicReady && cue.running) exp(-barPhase / (cue.settings.pulseLength * 2)) * snapshot.barConfidence else 0.0
        val step = if (cue.flashEvery >= 3) cue.meter.toDouble() else cue.flashEvery
        val event = floor((position + 1e-8) / step) * step
        val eventAt = cue.anchor + (event - cue.beat) * 60000 / cue.bpm
        val eventAge = time - eventAt
        val period = step * 60000 / cue.bpm
        val width = min(cue.flashMs, period * .8)
        val trusted = fresh && cue.running && snapshot.musicReady &&
            snapshot.beatConfidence >= .5 && snapshot.barConfidence >= .5 && !snapshot.blackout
        // An already-passed window is skipped. Count sampled misses separately from optical timing.
        if (trusted && previousRevision == cue.revision && previousTime.isFinite() && time >= previousTime) {
            val first = floor((cue.position(previousTime) + 1e-8) / step).toLong() + 1
            val last = floor((position + 1e-8) / step).toLong()
            if (last >= first) {
                missed += max(0, last - first)
                if (eventAge >= width) missed++
            }
        }
        val newOrSameFlash = abs(eventAt - lastFlash) < .001 || eventAt > lastFlash + period * .5
        val flash = if (trusted && flashes && eventAt > armedAfter && eventAge in 0.0..<width && newOrSameFlash) {
            lastFlash = eventAt; 1f
        } else 0f
        val downbeatAt = cue.anchor + (floor(position / cue.meter) * cue.meter - cue.beat) * 60000 / cue.bpm
        val fade = ((time - downbeatAt) / cue.invertMs).coerceIn(0.0, 1.0)
        val inversion = if (trusted && flashes && downbeatAt > armedAfter) (1 - fade * fade * (3 - 2 * fade)).toFloat() else 0f
        previousTime = time; previousRevision = cue.revision
        val phraseBar = Math.floorMod(floor(position / cue.meter).toLong() - cue.phraseOrigin, cue.phraseBars.toLong()).toInt() + 1
        return CrowdFrame(cue, min(time, snapshot.validUntil), MusicalMotion(
            beat.toFloat() * cue.settings.beatImpact, bar.toFloat() * cue.settings.barImpact,
            if (trusted) ((.5 - .5 * cos(2 * PI * barPhase / cue.meter)) * snapshot.beatConfidence).toFloat() else 0f,
            0f, flash, inversion), snapshot.blackout || !cue.running || (cue.diagnostic && !trusted), phraseBar)
    }
}
