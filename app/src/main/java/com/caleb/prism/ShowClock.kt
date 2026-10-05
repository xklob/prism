package com.caleb.prism

import kotlin.math.*

/** An application clock mapping; never changes the Android system clock. Times are milliseconds. */
class ShowClock {
    private data class Sample(val local: Double, val offset: Double, val delay: Double)
    data class Estimate(val origin: Double, val offset: Double, val drift: Double,
        val uncertainty: Double, val observedAt: Double, val samples: Int) {
        fun time(local: Double) = local + offset + (local - origin) * drift
        fun error(local: Double) = uncertainty + max(0.0, local - observedAt) * .0001
        fun ready(local: Double) = samples >= 6 && local - observedAt in 0.0..5000.0 && error(local) <= 15
    }
    private val samples = ArrayDeque<Sample>()
    @Volatile var estimate: Estimate? = null; private set
    @Synchronized fun clear() { samples.clear(); estimate = null }
    @Synchronized fun observe(t1: Double, t2: Double, t3: Double, t4: Double): Boolean {
        if (!listOf(t1,t2,t3,t4).all { it.isFinite() } || t4 < t1 || t3 < t2) return false
        val delay = (t4 - t1) - (t3 - t2)
        if (delay !in 0.0..250.0 || t3 - t2 > 100) return false
        val sample = Sample((t1 + t4) / 2, ((t2 - t1) + (t3 - t4)) / 2, delay)
        samples.addLast(sample)
        while (samples.size > 80 || (samples.isNotEmpty() && t4 - samples.first().local > 30000)) samples.removeFirst()
        val best = samples.sortedBy { it.delay }.take(max(3, samples.size / 3))
        val origin = best.map { it.local }.average()
        val offset = best.map { it.offset }.average()
        val variance = best.sumOf { (it.local - origin).pow(2) }
        val span = best.maxOf { it.local } - best.minOf { it.local }
        val drift = if (span >= 5000 && variance > 0)
            (best.sumOf { (it.local - origin) * (it.offset - offset) } / variance).coerceIn(-.0005, .0005) else 0.0
        val residual = best.maxOf { abs(it.offset - offset - (it.local - origin) * drift) }
        // RTT/2 accounts conservatively for unknown path asymmetry. Nanosecond
        // timestamp units do not imply nanosecond clock accuracy.
        estimate = Estimate(origin, offset, drift, best.maxOf { it.delay } / 2 + residual + .25, t4, samples.size)
        return true
    }
}
