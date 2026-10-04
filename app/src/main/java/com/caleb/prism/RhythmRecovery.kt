package com.caleb.prism

/** Keep an established onset clock; use envelope repetition when transient picking breaks down. */
class RhythmRecovery {
    var usingRecovery=false; private set
    private var challenger: Boolean?=null
    private var since=0.0

    fun choose(primary: RhythmState,recovered: RhythmState,time: Double): RhythmState {
        val current=if (usingRecovery) recovered else primary
        if (current.manualTempo || current.manualBar) { challenger=null; return current }
        val wantRecovery=if (usingRecovery) {
            !(primary.locked && primary.confidence>recovered.confidence+.12f)
        } else {
            primary.confidence<.70f && recovered.locked && recovered.confidence>primary.confidence+.12f
        }
        if (wantRecovery != usingRecovery) {
            if (challenger != wantRecovery) { challenger=wantRecovery; since=time }
            if (time-since>=1.0) { usingRecovery=wantRecovery; challenger=null }
        } else challenger=null
        return if (usingRecovery) recovered else primary
    }
}
