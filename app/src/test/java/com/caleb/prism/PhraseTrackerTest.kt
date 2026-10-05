package com.caleb.prism

import org.junit.Test
import org.junit.Assert.*

class PhraseTrackerTest {
    private fun timing(t: Double) = RhythmState(timestamp = t, bpm = 120f, position = t * 2,
        confidence = .95f, barConfidence = .9f, barLocked = true, signalPresent = true)
    private fun levels(n: Float) = AudioLevels(n, n, n, n)

    @Test fun uniformBarsNeverClaimAnAutomaticPhraseOrigin() {
        val tracker = PhraseTracker()
        var state = PhraseState()
        repeat(6400) { state = tracker.update(timing(it * .02), levels(.4f), 16) }
        assertNull(state.bar); assertEquals(0f, state.confidence, 0f)
    }

    @Test fun repeatedEightBarChangesBuildAnExplicitlyEstimatedCounter() {
        val tracker = PhraseTracker()
        var state = PhraseState()
        repeat(6400) { i ->
            val time = i * .02
            val value = if ((time / 16).toInt() % 2 == 0) .25f else .7f
            state = tracker.update(timing(time), levels(value), 8)
        }
        assertNotNull(state.bar); assertFalse(state.manual)
        assertTrue(state.confidence in .4f..0.8f)
        assertEquals(8, state.bar)
    }

    @Test fun manualOriginIsPreservedUntilGridBecomesUnreliableOrJumps() {
        val tracker = PhraseTracker()
        tracker.update(timing(10.0), levels(.4f), 16)
        tracker.align(timing(10.0), 10.0)
        var state = tracker.update(timing(10.02), levels(.4f), 16)
        assertTrue(state.manual); assertEquals(1, state.bar)
        for (i in 502..602) state = tracker.update(timing(i * .02), levels(.4f), 16)
        assertEquals(2, state.bar)
        state = tracker.update(timing(12.06).copy(position = 120.0), levels(.4f), 16)
        assertNull(state.bar); assertFalse(state.manual)
        tracker.align(timing(12.06).copy(position = 120.0), 12.06)
        repeat(160) { state = tracker.update(timing(12.08 + it * .02).copy(barLocked = false), levels(.4f), 16) }
        assertNull(state.bar)
        state = tracker.update(timing(15.3), levels(.4f), 16)
        assertNull(state.bar)
    }

    @Test fun changingPhraseLengthAndMeterClearsOldAlignment() {
        val tracker = PhraseTracker()
        tracker.update(timing(10.0), levels(.4f), 8); tracker.align(timing(10.0), 10.0)
        assertNull(tracker.update(timing(10.02), levels(.4f), 32).bar)
        tracker.align(timing(10.02), 10.02)
        assertNull(tracker.update(timing(10.04).copy(beatsPerBar = 3), levels(.4f), 32).bar)
    }
}
