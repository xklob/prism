package com.caleb.prism

/** Select and maintain the shared clock from the two BeatNet activation streams. */
internal class RhythmClock {
    private val trackers=listOf(RhythmTracker(),RhythmTracker())
    private val recoveryTrackers=listOf(RhythmTracker(true),RhythmTracker(true))
    private val selector=RhythmSelector()
    private val recoverySelector=RhythmSelector()
    private val recovery=RhythmRecovery()
    val tracker: RhythmTracker get() = if (recovery.usingRecovery) recoveryTrackers[recoverySelector.selected] else trackers[selector.selected]
    fun automaticTiming(time: Double) {
        val seed=tracker.state(time)
        (trackers+recoveryTrackers).forEach { it.automatic(seed) }
    }
    fun setTempoHint(hint: TempoHint?) { (trackers+recoveryTrackers).forEach { it.setTempoHint(hint) } }
    fun discontinuity() { (trackers+recoveryTrackers).forEach { it.discontinuity() } }

    fun setMeter(meter: Int) { (trackers+recoveryTrackers).forEach { it.setMeter(meter) } }

    fun observe(time: Double, probabilities: FloatArray, general: FloatArray, audible: Boolean): RhythmState {
        val states=listOf(trackers[0].observe(time,probabilities[0],probabilities[1],audible),
            trackers[1].observe(time,general[0],general[1],audible))
        val recovered=listOf(recoveryTrackers[0].observe(time,probabilities[0],probabilities[1],audible),
            recoveryTrackers[1].observe(time,general[0],general[1],audible))
        return recovery.choose(selector.choose(states,time),recoverySelector.choose(recovered,time),time)
    }
}
