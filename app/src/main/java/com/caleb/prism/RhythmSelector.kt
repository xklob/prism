package com.caleb.prism

import kotlin.math.*

/** Choose sustained timing evidence across complementary models without averaging away downbeats. */
class RhythmSelector {
    var selected = 0; private set
    private var challenger = -1
    private var since = 0.0
    private var conflict = RhythmConflict.NONE
    private var conflictSince = 0.0

    fun choose(states: List<RhythmState>, time: Double): RhythmState {
        val current = states[selected]
        if (current.manualTempo || current.manualBar) { conflict=RhythmConflict.NONE; return current }
        fun score(state: RhythmState) = state.confidence + if (state.barLocked) state.barConfidence * 0.3f else 0f
        val best = states.indices.maxBy { score(states[it]) }
        if (best != selected && states[best].locked && score(states[best]) > score(current) + 0.18f) {
            if (challenger != best) { challenger = best; since = time }
            if (time - since >= 1.5) { selected = best; challenger = -1 }
        } else challenger = -1
        val chosen=states[selected]
        val other=states.withIndex().filter { it.index != selected }.maxByOrNull { score(it.value) }?.value
        var disagreement=RhythmConflict.NONE
        if (other != null && chosen.confidence > 0.65f && other.confidence > 0.65f && abs(score(chosen)-score(other)) < 0.18f) {
            val tempoDifference=abs(ln(chosen.bpm/other.bpm))
            val phase=chosen.positionAt(time)-other.positionAt(time)
            if (tempoDifference > 0.045 || abs(phase-round(phase)) > 0.24) disagreement=RhythmConflict.TEMPO
            else if (chosen.barLocked && other.barLocked && chosen.barConfidence > 0.65f && other.barConfidence > 0.65f) {
                val bars=chosen.beatsPerBar
                val delta=(chosen.positionAt(time)-chosen.barOffset)-(other.positionAt(time)-other.barOffset)
                if (bars != other.beatsPerBar || abs(delta-round(delta/bars)*bars) > 0.4) disagreement=RhythmConflict.BAR
            }
        }
        if (disagreement != conflict) { conflict=disagreement; conflictSince=time }
        if (conflict == RhythmConflict.NONE || time-conflictSince < 0.75) return chosen
        return chosen.copy(conflict=conflict,
            confidence=if (conflict == RhythmConflict.TEMPO) min(chosen.confidence,0.49f) else chosen.confidence,
            barConfidence=min(chosen.barConfidence,0.35f),barLocked=false)
    }
}
