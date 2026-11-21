package com.caleb.prism

import kotlin.math.*

data class TimingSample(val rhythm: RhythmState = RhythmState(), val phrase: PhraseState = PhraseState(), val energy: Float = 0f)

/** Bounded, audio-free history. Confidence is evidence strength, not measured accuracy. */
internal class TimingHistory(private val capacity: Int = 3000) {
    private val samples = ArrayDeque<TimingSample>()
    fun clear() = samples.clear()
    fun add(sample: TimingSample) {
        if (!sample.rhythm.timestamp.isFinite()) return
        if (samples.lastOrNull()?.rhythm?.timestamp?.let { sample.rhythm.timestamp <= it } == true) return
        samples.addLast(sample)
        while (samples.size > capacity || sample.rhythm.timestamp - samples.first().rhythm.timestamp > 60.0) samples.removeFirst()
    }
    fun snapshot(): List<TimingSample> = samples.toList()

    companion object {
        fun phaseErrorMs(r: RhythmState): Double? {
            if (r.bpm <= 0 || !r.lastMatchedBeatTime.isFinite() || r.timestamp - r.lastMatchedBeatTime !in 0.0..2.0) return null
            val atMatch = r.position + (r.lastMatchedBeatTime - r.timestamp) * r.bpm / 60
            return (atMatch - round(atMatch)) * 60_000 / r.bpm
        }
        fun csv(samples: List<TimingSample>, format: (Double) -> String): String = buildString {
            append("seconds,bpm,beat_position,beat_confidence,bar_confidence,phase_confidence,beat_probability,downbeat_probability,beats_per_bar,bar_offset,bar_locked,phrase_bar,phrase_bars,phrase_confidence,match_error_ms,energy,manual_tempo,manual_bar,manual_phrase,guided,holding,coasting,signal,conflict\n")
            val start = samples.firstOrNull()?.rhythm?.timestamp ?: 0.0
            fun n(value: Double) = if (value.isFinite()) format(value) else ""
            samples.forEach { sample ->
                val r = sample.rhythm; val p = sample.phrase
                append(listOf(n(r.timestamp-start), n(r.bpm.toDouble()), n(r.position), n(r.confidence.toDouble()),
                    n(r.barConfidence.toDouble()), n(r.phaseConfidence.toDouble()), n(r.beatProbability.toDouble()),
                    n(r.downbeatProbability.toDouble()), r.beatsPerBar, r.barOffset, r.barLocked, p.bar ?: "", p.bars,
                    n(p.confidence.toDouble()), phaseErrorMs(r)?.let(::n) ?: "", n(sample.energy.toDouble()),
                    r.manualTempo, r.manualBar, p.manual, r.guided || p.guided, r.holding, r.coasting, r.signalPresent, r.conflict.name).joinToString(","))
                append('\n')
            }
        }
    }
}
