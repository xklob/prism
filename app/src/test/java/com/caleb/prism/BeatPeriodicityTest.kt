package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class BeatPeriodicityTest {
    private fun activation(time: Double,bpm: Double,meter: Int,noise: Double): Pair<Float,Float> {
        val position=(time-.3)*bpm/60
        val index=round(position).toInt()
        val age=(position-index)*60/bpm
        // A wide onset, a delayed reflection, and flutter create multiple local maxima per beat.
        val envelope=.70*exp(-.5*(age/.048).pow(2))+.28*exp(-.5*((age-.10)/.035).pow(2))
        val total=(envelope*(.85+.15*cos(time*73))+noise).coerceIn(0.0,.98)
        val down=if (Math.floorMod(index,meter)==0) .93 else .06
        return (total*(1-down)).toFloat() to (total*down).toFloat()
    }

    @Test fun broadEchoingBeatsKeepTempoPhaseAndMeterAcrossMusicalRates() {
        for ((bpm,meter) in listOf(80.0 to 4,125.0 to 4,174.0 to 4,96.0 to 3)) {
            val tracker=RhythmTracker(true)
            val random=Random(19)
            var locked=0; var frames=0; var result=RhythmState()
            for (frame in 0..1800) {
                val time=frame*.02
                val (beat,down)=activation(time,bpm,meter,random.nextDouble()*.06)
                result=tracker.observe(time,beat,down,true)
                if (time>10) {
                    frames++; if (result.locked) locked++
                    assertEquals("Tempo at $bpm BPM, t=$time",bpm,result.bpm.toDouble(),1.5)
                    val phase=result.position-(time-.3)*bpm/60
                    assertTrue("Beat alignment at $bpm BPM: $phase",abs(phase-round(phase))*60/bpm<.060)
                }
            }
            assertTrue("Lock coverage at $bpm BPM: $locked/$frames",locked>frames*.95)
            assertTrue("Bar evidence must survive reflections: $result",result.barLocked)
            assertEquals(meter,result.beatsPerBar)
            val expected=round((result.timestamp-.3)*bpm/60).toInt()
            assertEquals(Math.floorMod(expected,meter),Math.floorMod(round(result.position).toInt()-result.barOffset,meter))
        }
    }

    @Test fun absoluteCaptureClockDoesNotChangeTheResult() {
        val trackers=listOf(RhythmTracker(true),RhythmTracker(true),RhythmTracker(true))
        val clocks=listOf(0.0,1000.0,1_000_000.0)
        for (frame in 0..1100) {
            val time=frame*.02
            val (beat,down)=activation(time,125.0,4,.03)
            val states=trackers.zip(clocks).map { (tracker,clock) -> tracker.observe(time+clock,beat,down,true) }
            for (other in states.drop(1)) {
                assertEquals(states[0].bpm,other.bpm,.01f)
                assertEquals(states[0].confidence,other.confidence,.001f)
                assertEquals(states[0].barLocked,other.barLocked)
                assertEquals(states[0].position,other.position,.002)
            }
        }
    }

    @Test fun steadyOrUncorrelatedActivationsCannotEstablishAClock() {
        val random=Random(41)
        val estimators=List(3) { BeatPeriodicity() }
        for (frame in 0..1800) {
            val values=listOf(.8,random.nextDouble(),random.nextDouble()*.04)
            estimators.zip(values).forEach { (estimator,value) ->
                estimator.observe(frame*.02,value)
                assertNull("Unstructured input cannot claim periodic beats",estimator.estimate)
            }
        }
    }

    @Test fun missingBeatsLoseConfidenceAndNewTempoCanBeAcquired() {
        val tracker=RhythmTracker(true)
        var result=RhythmState()
        for (frame in 0..650) {
            val (beat,down)=activation(frame*.02,125.0,4,.01)
            result=tracker.observe(frame*.02,beat,down,true)
        }
        assertTrue(result.locked)
        for (frame in 651..850) result=tracker.observe(frame*.02,0f,0f,true)
        assertFalse("A stale envelope cannot keep claiming beat lock",result.locked)
        assertTrue(result.coasting)
        assertFalse(result.barLocked)
        for (frame in 851..1700) {
            val (beat,down)=activation(frame*.02,150.0,4,.01)
            result=tracker.observe(frame*.02,beat,down,true)
        }
        assertTrue(result.locked)
        assertEquals(150f,result.bpm,1.5f)
        for (frame in 1701..1800) result=tracker.observe(frame*.02,0f,0f,false)
        assertFalse(result.signalPresent)
        assertFalse(result.locked)
        assertFalse(result.barLocked)
    }

    @Test fun manualTempoAndBarStayInControlUntilAutomaticIsRequested() {
        val tracker=RhythmTracker(true)
        for (frame in 0..600) {
            val (beat,down)=activation(frame*.02,125.0,4,.02)
            tracker.observe(frame*.02,beat,down,true)
        }
        tracker.tap(12.5); tracker.tap(13.0); tracker.tap(13.5)
        tracker.alignBar(13.5)
        for (frame in 676..1000) {
            val (beat,down)=activation(frame*.02,125.0,4,.02)
            val state=tracker.observe(frame*.02,beat,down,true)
            assertEquals(120f,state.bpm,.001f)
            assertTrue(state.manualTempo && state.manualBar && state.barLocked)
            assertEquals((frame*.02-13.5)*2,state.position,.0001)
        }
        tracker.automatic()
        var state=RhythmState()
        for (frame in 1001..1800) {
            val (beat,down)=activation(frame*.02,125.0,4,.02)
            state=tracker.observe(frame*.02,beat,down,true)
        }
        assertFalse(state.manualTempo || state.manualBar)
        assertTrue(state.locked && state.barLocked)
        assertEquals(125f,state.bpm,1f)
    }
}
