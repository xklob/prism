package com.caleb.prism

import java.util.Locale

/** Thread-safe access for Android capture, UI and file playback. */
class TimingDiagnostics(capacity: Int = 3000) {
    private val history = TimingHistory(capacity)
    @Synchronized fun clear() = history.clear()
    @Synchronized fun add(sample: TimingSample) = history.add(sample)
    @Synchronized fun snapshot(): List<TimingSample> = history.snapshot()

    companion object {
        fun phaseErrorMs(r: RhythmState): Double? = TimingHistory.phaseErrorMs(r)
        fun csv(samples: List<TimingSample>): String = TimingHistory.csv(samples) { String.format(Locale.ROOT, "%.5f", it) }
    }
}
