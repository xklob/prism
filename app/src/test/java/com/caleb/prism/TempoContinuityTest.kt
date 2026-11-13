package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class TempoContinuityTest {
    private fun music(tracker: RhythmTracker, from: Int, to: Int, bpm: Double = 120.0, phase: Double = .3): RhythmState {
        var state = RhythmState()
        for (frame in from..to) {
            val time = frame * .02
            val position = (time-phase)*bpm/60
            val p = .92*exp(-.5*((position-round(position))*60/bpm/.025).pow(2))
            val down = p * if (Math.floorMod(round(position).toInt(),4)==0) .95 else .02
            state = tracker.observe(time,(p-down).toFloat(),down.toFloat(),true)
        }
        return state
    }
    @Test fun lookupExtendsAnEstablishedGridButNeverInventsBarPlacement() {
        for (periodic in listOf(false,true)) {
            val tracker = RhythmTracker(periodic)
            tracker.setTempoHint(TempoHint(120.0,"song"))
            val before = music(tracker,0,1200)
            assertTrue("Lock before bridge: $before",before.barLocked)
            var held = before
            for (frame in 1201..2800) held = tracker.observe(frame*.02,0f,0f,true)
            assertTrue("32-second bridge with lookup: $held",held.locked && held.barLocked && held.holding)
            assertTrue(held.tempoAssisted)
            assertEquals(before.bpm,held.bpm,.01f)
            assertEquals(before.position+32*before.bpm/60,held.position,.02)
            val returned = music(tracker,2801,3000)
            assertTrue("Beat return keeps the bar: $returned",returned.locked && returned.barLocked)
            tracker.discontinuity()
            val reset = tracker.observe(60.02,0f,0f,true)
            assertFalse(reset.locked || reset.barLocked || reset.holding)
        }
    }
    @Test fun bridgeDoesNotPreventAChangedPhaseOrTempoFromTakingOver() {
        for (periodic in listOf(false,true)) for ((bpm,phase) in listOf(140.0 to .3,120.0 to .55)) {
            val tracker=RhythmTracker(periodic)
            tracker.setTempoHint(TempoHint(120.0,"original"))
            music(tracker,0,1200)
            for (frame in 1201..1600) tracker.observe(frame*.02,0f,0f,true)
            val state=music(tracker,1601,2400,bpm,phase)
            assertTrue("New live grid must win: $state",state.locked && !state.holding)
            assertEquals(bpm,state.bpm.toDouble(),1.5)
            val error=state.position-(state.timestamp-phase)*bpm/60
            assertTrue("New phase must win: $state",abs(error-round(error))*60/bpm<.065)
        }
    }
    @Test fun changingSongAndSilenceDiscardHeldEvidence() {
        for (periodic in listOf(false,true)) {
            val tracker=RhythmTracker(periodic)
            tracker.setTempoHint(TempoHint(120.0,"one"))
            music(tracker,0,1200)
            tracker.setTempoHint(TempoHint(120.0,"two"))
            assertFalse(tracker.observe(24.02,0f,0f,true).locked)
            music(tracker,1202,2400)
            for (frame in 2401..2500) tracker.observe(frame*.02,0f,0f,false)
            assertFalse(tracker.observe(50.02,0f,0f,true).locked)
        }
    }
    @Test fun weakIrregularNotesInABridgeDoNotVoteDownTheEstablishedTempo() {
        for (periodic in listOf(false,true)) {
            val tracker=RhythmTracker(periodic)
            val before=music(tracker,0,1200)
            var held=before
            for (frame in 1201..1800) {
                val time=frame*.02
                val weak=.18*exp(-.5*((time% .73)/.025).pow(2))
                held=tracker.observe(time,weak.toFloat(),0f,true)
            }
            assertTrue("Weak notes should not erase the grid: $held",held.locked && held.barLocked && held.holding)
            assertEquals(before.bpm,held.bpm,.1f)
            assertEquals(before.position+12*before.bpm/60,held.position,.1)
        }
    }
    @Test fun weightedHistorySmoothsJitterAndGivesNewEvidenceMoreWeight() {
        val history=TempoMemory()
        var bpm=0.0
        repeat(40) { bpm=history.observe(it*.3,if (it%2==0) 119.0 else 121.0,.9) }
        assertEquals(120.0,bpm,.2)
        assertTrue(history.stable)
        repeat(30) { bpm=history.observe(12+it*.3,123.0,.9) }
        assertEquals(123.0,bpm,.2)
        assertEquals(150.0,history.observe(21.0,150.0,.95),.001)
        assertFalse(history.stable)
    }
    @Test fun autoInheritsManualTempoBeatAndBarWithHighInitialConfidence() {
        for (periodic in listOf(false,true)) {
            val tracker=RhythmTracker(periodic)
            music(tracker,0,1200,bpm=140.0)
            tracker.tap(24.5); tracker.tap(25.0); tracker.tap(25.5)
            tracker.alignBar(25.5)
            val manual=tracker.state(25.5)
            // The analyzer hands this one grid to all four competing trackers.
            val recipients=listOf(tracker,RhythmTracker(!periodic))
            for (recipient in recipients) {
                recipient.automatic(manual)
                var state=recipient.observe(25.52,0f,0f,true)
                assertFalse(state.manualTempo || state.manualBar)
                assertTrue("Manual evidence should seed confident automatic timing: $state",state.guided && state.confidence>.9f && state.barConfidence>.9f && state.barLocked)
                assertEquals(120f,state.bpm,.001f)
                assertEquals(manual.position+.04,state.position,.001)
                for (frame in 1277..1500) state=recipient.observe(frame*.02,0f,0f,true)
                assertTrue(state.holding && state.locked && state.barLocked)
                state=music(recipient,1501,2500,140.0)
                assertTrue(state.locked)
                assertEquals("New evidence can replace the initial guidance",140f,state.bpm,1.5f)
                assertFalse(state.guided)
            }
        }
    }
    @Test fun manuallyAligningOneBeatDoesNotRelabelTheBarAndAutoKeepsThePhase() {
        val tracker=RhythmTracker()
        music(tracker,0,1200)
        val before=tracker.state(24.0)
        tracker.alignBeat(24.1)
        val aligned=tracker.state(24.1)
        assertEquals(before.barOffset,aligned.barOffset)
        assertEquals(round(aligned.position),aligned.position,.001)
        tracker.automatic(aligned)
        val after=tracker.observe(24.12,0f,0f,true)
        assertTrue(after.guided && after.confidence>.9f)
        assertEquals(aligned.position+.04,after.position,.001)
    }
}
