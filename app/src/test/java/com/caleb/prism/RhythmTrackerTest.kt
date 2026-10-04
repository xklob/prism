package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RhythmTrackerTest {
    private fun feed(tracker: RhythmTracker, bpm: Double, beats: Int, seconds: Double = 24.0,
                     downbeats: Boolean = true, skip: Boolean = false, start: Double = 0.0): RhythmState {
        var state=RhythmState()
        for (frame in 0..(seconds*50).toInt()) {
            val time=start+frame/50.0
            val position=(time-.3)*bpm/60
            val index=round(position).toInt()
            val distance=abs(position-index)*60/bpm
            val probability=if (skip && Math.floorMod(index,7)==5) .01 else .92*exp(-distance*distance/.0008)
            val down=if (downbeats && Math.floorMod(index,beats)==0) probability*.95 else probability*.02
            state=tracker.observe(time,(probability-down).toFloat(),down.toFloat(),true)
        }
        return state
    }
    @Test fun learnsTempoPhaseAndBothCommonMeters() {
        for (meter in listOf(3,4)) for (bpm in listOf(80.0,120.0,174.0)) {
            val state=feed(RhythmTracker(),bpm,meter)
            assertTrue("Tempo lock $bpm / $meter: $state",state.locked)
            assertEquals(bpm,state.bpm.toDouble(),1.5)
            assertTrue("Bar lock $bpm / $meter: $state",state.barLocked)
            assertEquals(meter,state.beatsPerBar)
            val lastDownbeat=.3+floor((24-.3)*bpm/60/meter)*meter*60/bpm
            val position=state.positionAt(lastDownbeat.coerceAtLeast(23.5))
            if (lastDownbeat >= 23.5) assertEquals(0,Math.floorMod(round(position).toInt()-state.barOffset,meter))
        }
    }
    @Test fun missingBeatsDoNotTurnEighthNotesIntoTempoAndAmbiguousBarsStayUnlocked() {
        val missing=feed(RhythmTracker(),128.0,4,skip=true)
        assertEquals(128f,missing.bpm,1.5f)
        val ambiguous=feed(RhythmTracker(),128.0,4,downbeats=false)
        assertTrue(ambiguous.locked)
        assertFalse("A beat counter alone is not evidence of a bar start",ambiguous.barLocked)
    }
    @Test fun silenceAndSteadyInputNeverInventMusic() {
        val tracker=RhythmTracker()
        repeat(1000) { assertFalse(tracker.observe(it*.02,.01f,.002f,true).locked) }
        val tracked=RhythmTracker()
        assertTrue(feed(tracked,120.0,4).locked)
        var state=RhythmState()
        for (i in 1..100) state=tracked.observe(24+i*.02,0f,0f,false)
        assertFalse(state.signalPresent); assertFalse(state.locked); assertFalse(state.barLocked)
    }
    @Test fun manualTempoBarAlignmentAndReturnToAutomatic() {
        val tracker=RhythmTracker()
        for (time in listOf(1.0,1.5,2.0,2.5)) tracker.tap(time)
        tracker.observe(2.5,.01f,0f,true)
        tracker.setMeter(3)
        tracker.alignBar(2.5)
        var state=tracker.state(2.5)
        assertEquals(120f,state.bpm,0.001f)
        assertTrue(state.manualTempo && state.manualBar && state.barLocked)
        assertEquals(1,state.beatInBar(2.5))
        assertEquals(2,state.beatInBar(3.0))
        tracker.automatic()
        state=feed(tracker,140.0,3,start=2.52)
        assertFalse(state.manualTempo || state.manualBar)
        assertEquals(140f,state.bpm,1.5f)
    }
    @Test fun reacquiresAfterTempoChange() {
        val tracker=RhythmTracker()
        assertEquals(120f,feed(tracker,120.0,4).bpm,1f)
        val next=feed(tracker,140.0,4,start=24.02)
        assertTrue(next.locked)
        assertEquals(140f,next.bpm,1.5f)
    }

    @Test fun gapsCoastThenLoseConfidenceEvenWhileInputRemainsAudible() {
        val tracker=RhythmTracker()
        assertTrue(feed(tracker,120.0,4).barLocked)
        val shortlyAfter=tracker.observe(24.02,0f,0f,true)
        assertTrue(shortlyAfter.locked)
        var state=shortlyAfter
        for (frame in 2..60) state=tracker.observe(24+frame*.02,0f,0f,true)
        assertTrue(state.signalPresent)
        assertTrue(state.coasting)
        assertFalse("Coasting is not evidence for a new downbeat",state.barLocked)
        for (frame in 61..400) state=tracker.observe(24+frame*.02,0f,0f,true)
        assertTrue(state.confidence<.05f && state.barConfidence<.05f)
        assertTrue(feed(tracker,120.0,4,start=32.02).barLocked)
    }

    @Test fun weakOffbeatFlourishesDoNotStealAnEstablishedGrid() {
        val tracker=RhythmTracker()
        feed(tracker,128.0,4)
        var state=RhythmState()
        for (frame in 1..800) {
            val time=24+frame*.02
            val position=(time-.3)*128/60
            val index=round(position).toInt()
            val distance=abs(position-index)*60/128
            val strong=.92*exp(-distance*distance/.0008)
            val ghostDistance=abs(position-.5-round(position-.5))*60/128
            val weak=.3*exp(-ghostDistance*ghostDistance/.0004)
            val down=if (Math.floorMod(index,4)==0) strong*.95 else strong*.02
            state=tracker.observe(time,(strong-down+weak).toFloat(),down.toFloat(),true)
        }
        assertTrue(state.locked && state.barLocked)
        assertEquals(128f,state.bpm,1f)
        val expected=(state.timestamp-.3)*128/60
        assertTrue(abs(state.position-expected-round(state.position-expected))*60/128<.04)
    }

    @Test fun weakPeriodicEvidenceDoesNotMasqueradeAsHighConfidence() {
        val tracker=RhythmTracker()
        var state=RhythmState()
        for (frame in 0..1000) {
            val time=frame*.02
            val position=(time-.3)*2
            val distance=abs(position-round(position))*.5
            state=tracker.observe(time,(.15*exp(-distance*distance/.0008)).toFloat(),0f,true)
        }
        assertEquals(120f,state.bpm,1f)
        assertTrue("Very weak model predictions are tentative: $state",state.confidence<.65f)
        assertFalse(state.barLocked)
    }

    @Test fun reacquiresAQuieterBeatAfterItsPhaseChangesWithoutChangingTempo() {
        val tracker=RhythmTracker()
        feed(tracker,120.0,4)
        var state=RhythmState()
        for (frame in 1..500) {
            val time=24+frame*.02
            val position=(time-.55)*2 // Same tempo, shifted half a beat, with weaker predictions.
            val distance=abs(position-round(position))*.5
            val probability=.3*exp(-distance*distance/.0008)
            val down=if (Math.floorMod(round(position).toInt(),4)==0) probability*.95 else probability*.02
            state=tracker.observe(time,(probability-down).toFloat(),down.toFloat(),true)
        }
        assertTrue("Old confidence must not reject a changed rhythm forever: $state",state.locked)
        assertEquals(120f,state.bpm,1.5f)
        val expected=(state.timestamp-.55)*2
        assertTrue("Must follow the new phase",abs(state.position-expected-round(state.position-expected))*.5<.06)
    }

    @Test fun learnedProbabilitiesTrackMusicalFixtures() {
        for ((name,bpm,meter) in listOf(Triple("drums128",128f,4),Triple("drums174",174f,4),Triple("waltz96",96f,3))) {
            val tracker=RhythmTracker()
            var state=RhythmState()
            requireNotNull(javaClass.getResourceAsStream("/rhythm/$name.csv")).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val values=line.split(',').map(String::toDouble)
                    state=tracker.observe(values[0],values[1].toFloat(),values[2].toFloat(),true)
                }
            }
            println("$name: $state")
            assertTrue("Must find musical tempo for $name: $state",state.locked)
            assertEquals(bpm,state.bpm,2f)
            assertTrue("Must find measure boundaries for $name: $state",state.barLocked)
            assertEquals(meter,state.beatsPerBar)
            val expectedBeat=(state.timestamp-.3)*bpm/60
            val phaseError=abs((state.position-expectedBeat)-round(state.position-expectedBeat))
            assertTrue("Beat phase within 65 ms: $phaseError",phaseError*60/bpm<.065)
            val nearest=round(expectedBeat).toInt()
            if (meter == 4) {
                assertEquals("Detected first beat must match the composed downbeat",Math.floorMod(nearest,meter),
                    Math.floorMod(round(state.position).toInt()-state.barOffset,meter))
            } else {
                // This syncopated fixture's strongest accent is on beat 2. A user-supplied bar origin
                // must override that ambiguity without losing the correctly detected tempo or meter.
                val first=.3+ceil(expectedBeat/meter)*meter*60/bpm
                tracker.alignBar(first)
                assertEquals(1,tracker.state(first).beatInBar(first))
                assertTrue(tracker.state(first).manualBar)
            }
        }
    }
}
