package com.caleb.prism

/** Choose sustained timing evidence across complementary models without averaging away downbeats. */
class RhythmSelector {
    var selected = 0; private set
    private var challenger = -1
    private var since = 0.0

    fun choose(states: List<RhythmState>, time: Double): RhythmState {
        val current = states[selected]
        if (current.manualTempo || current.manualBar) return current
        fun score(state: RhythmState) = state.confidence + if (state.barLocked) state.barConfidence * 0.3f else 0f
        val best = states.indices.maxBy { score(states[it]) }
        if (best != selected && states[best].locked && score(states[best]) > score(current) + 0.18f) {
            if (challenger != best) { challenger = best; since = time }
            if (time - since >= 1.5) { selected = best; challenger = -1 }
        } else challenger = -1
        return states[selected]
    }
}
