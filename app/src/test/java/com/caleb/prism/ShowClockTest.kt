package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test

class ShowClockTest {
    @Test fun offsetAndDriftAreRecoveredFromFourTimestamps() {
        val clock = ShowClock()
        val mapping = { local: Double -> local + 73500.0 + local * .00012 }
        for (i in 0 until 60) {
            val t1 = i * 500.0
            val travel = if (i % 6 == 0) 40.0 else 1.0
            clock.observe(t1, mapping(t1 + travel), mapping(t1 + travel + .2), t1 + 2 * travel + .2)
        }
        val estimate = clock.estimate!!
        assertTrue(estimate.ready(30000.0))
        assertEquals(mapping(30000.0), estimate.time(30000.0), 1.0)
        assertEquals(.00012, estimate.drift, .00002)
        assertTrue(estimate.error(30000.0) < 5)
        assertFalse(estimate.ready(40000.0))
    }
    @Test fun asymmetryIsReflectedInUncertaintyAndInvalidSamplesDoNotChangeTheClock() {
        val clock = ShowClock()
        for (i in 0..10) {
            val t = i * 100.0
            clock.observe(t, t + 1000 + 8, t + 1000 + 8.2, t + 10.2)
        }
        val estimate = clock.estimate!!
        assertTrue(kotlin.math.abs(estimate.time(1100.0) - 2100) <= estimate.error(1100.0))
        assertFalse(clock.observe(Double.NaN, 0.0, 0.0, 1.0))
        assertFalse(clock.observe(10.0, 20.0, 19.0, 30.0))
        assertSame(estimate, clock.estimate)
        clock.clear()
        assertNull(clock.estimate)
    }
    @Test fun slowLinksNeverClaimReady() {
        val clock = ShowClock()
        for (i in 0..12) {
            val t = i * 500.0
            clock.observe(t,t+1050,t+1051,t+101)
        }
        assertFalse(clock.estimate!!.ready(6200.0))
    }
}
