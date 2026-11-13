package com.caleb.prism

/** Keep an established onset clock; use envelope repetition when transient picking breaks down. */
class RhythmRecovery {
    var usingRecovery=false; private set
    private var challenger: Boolean?=null
    private var since=0.0

    fun choose(primary: RhythmState,recovered: RhythmState,time: Double): RhythmState {
        val current=if (usingRecovery) recovered else primary
        if (current.manualTempo || current.manualBar) { challenger=null; return current }
        fun score(state: RhythmState) = state.confidence +
            (if (state.barLocked && state.conflict == RhythmConflict.NONE) state.barConfidence*.15f else 0f)
        val wantRecovery=if (usingRecovery) {
            !(primary.locked && score(primary)>score(recovered)+.12f)
        } else {
            primary.confidence<.70f && recovered.locked && score(recovered)>score(primary)+.12f
        }
        if (wantRecovery != usingRecovery) {
            if (challenger != wantRecovery) { challenger=wantRecovery; since=time }
            if (time-since>=1.0) { usingRecovery=wantRecovery; challenger=null }
        } else challenger=null
        return if (usingRecovery) recovered else primary
    }
}
