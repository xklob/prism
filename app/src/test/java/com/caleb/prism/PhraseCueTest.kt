package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.floor

class PhraseCueTest {
    private fun rhythm(now: Double, position: Double = now * 2) = RhythmState(timestamp = now, bpm = 120f, position = position,
        confidence = .9f, barConfidence = .85f, barLocked = true, signalPresent = true)
    private fun phrase(position: Double) = PhraseState(bars = 8, bar = Math.floorMod(floor(position / 4).toInt(), 8) + 1, confidence = .8f)
    @Test fun firesOnceOnFutureBoundary() {
        val cue = PhraseCue(); val fired = mutableListOf<Double>()
        for (i in 1000..3300) {
            val now = i / 100.0
            if (cue.update(rhythm(now), phrase(now * 2), now, true, 1)) fired += now
        }
        assertEquals(listOf(16.0), fired)
    }
    @Test fun arrivingOnBoundaryWaitsForNextOne() {
        val cue = PhraseCue(); val fired = mutableListOf<Double>()
        for (i in 1600..3300) {
            val now = i / 100.0
            if (cue.update(rhythm(now), phrase(now * 2), now, true, 1)) fired += now
        }
        assertEquals(listOf(32.0), fired)
    }
    @Test fun discontinuityDoesNotFire() {
        for (reason in 0..6) {
            val cue = PhraseCue()
            for (i in 1400..1599) {
                val now = i / 100.0
                assertFalse(cue.update(rhythm(now), phrase(now * 2), now, true, 1))
            }
            val now = if (reason == 5) 16.5 else 16.0
            val base = rhythm(now)
            val r = when (reason) {
                0 -> base.copy(confidence = .2f)
                1 -> base.copy(position = base.position + 4)
                2 -> base.copy(timestamp = 15.0)
                3 -> base.copy(conflict = RhythmConflict.TEMPO)
                4 -> base.copy(coasting = true)
                else -> base
            }
            assertFalse("reason $reason", cue.update(r, phrase(r.position), now, true, if (reason == 6) 2 else 1))
        }
    }
    @Test fun manualPhraseAndSyncOffsetAreSupported() {
        val cue = PhraseCue(); var firedAt = 0.0
        for (i in 1400..1620) {
            val now = i / 100.0
            if (cue.update(rhythm(now), phrase(now * 2).copy(manual = true), now, true, 1, 100f)) firedAt = now
        }
        assertEquals(15.9, firedAt, .011)
    }
    @Test fun missingPhraseOrCaptureNeverFires() {
        for (running in listOf(false, true)) {
            val cue = PhraseCue()
            for (i in 1400..1700) {
                val now = i / 100.0
                assertFalse(cue.update(rhythm(now), if (running) PhraseState() else phrase(now*2), now, running, 1))
            }
        }
    }
    @Test fun explicitCorrectionRearmsEvenWhenThePhaseChangeIsSmall() {
        val cue = PhraseCue()
        for (i in 1400..1599) {
            val now = i / 100.0
            assertFalse(cue.update(rhythm(now), phrase(now*2), now, true, 1))
        }
        assertFalse(cue.update(rhythm(16.0, 32.05), phrase(32.05), 16.0, true, 2))
    }
    @Test fun heldTempoCanCarryAnEstablishedPhraseCue() {
        val cue = PhraseCue(); var fired = 0
        for (i in 1400..1700) {
            val now = i / 100.0
            if (cue.update(rhythm(now).copy(holding = true), phrase(now*2), now, true, 1)) fired++
        }
        assertEquals(1, fired)
    }
}
