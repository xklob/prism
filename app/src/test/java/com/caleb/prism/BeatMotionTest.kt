package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test

class BeatMotionTest {
    private val timing=RhythmState(timestamp=10.0,bpm=120f,position=0.0,confidence=1f,
        barConfidence=1f,barLocked=true,signalPresent=true,lastBeatTime=10.0)
    private val settings=VisualSettings(audioEnabled=true)
    private val loud=AudioLevels(bass=1f,mid=1f,high=1f,energy=1f)

    @Test fun steadyVolumeCannotTriggerDefaultMotion() {
        val motion=BeatMotion()
        assertEquals(MusicalMotion(),motion.update(RhythmState(),loud,10.0,settings))
        val quiet=motion.update(timing,AudioLevels(),10.0,settings)
        assertEquals("Musical timing, not volume, drives the default response",quiet,motion.update(timing,loud,10.0,settings))
    }
    @Test fun beatsHaveFastAttacksAndBarsGetSeparateAccents() {
        val motion=BeatMotion()
        val first=motion.update(timing,loud,10.0,settings)
        assertEquals(1f,first.beat,0.001f)
        assertEquals(0.9f,first.bar,0.001f)
        val tail=motion.update(timing,loud,10.3,settings)
        assertTrue(tail.beat<first.beat*.15f)
        val second=motion.update(timing.copy(timestamp=10.5,position=1.0),loud,10.5,settings)
        assertEquals(1f,second.beat,0.001f)
        assertTrue(second.bar<first.bar*.3f)
        assertEquals(0f,motion.update(timing.copy(barLocked=false),loud,10.0,settings).bar,0f)
    }
    @Test fun offZeroStaleAndSilenceClearMotionAndPauseFreezesIt() {
        val motion=BeatMotion()
        val first=motion.update(timing,loud,10.0,settings)
        assertEquals(first,motion.update(timing,loud,10.3,settings.copy(paused=true)))
        assertEquals(MusicalMotion(),motion.update(timing,loud,10.3,settings.copy(audioEnabled=false,paused=true)))
        assertEquals(MusicalMotion(),motion.update(timing,loud,10.0,settings.copy(audioAmount=0f)))
        assertEquals(MusicalMotion(),motion.update(timing,loud,11.0,settings))
        assertEquals(MusicalMotion(),motion.update(timing.copy(signalPresent=false),loud,10.0,settings))
    }
    @Test fun manualAlignmentAndSyncOffsetAffectBeatPhase() {
        val motion=BeatMotion()
        val late=timing.copy(timestamp=10.05,position=.1)
        assertTrue(motion.update(late,loud,10.05,settings).beat<.75f)
        assertEquals(1f,motion.update(late,loud,10.05,settings.copy(syncOffsetMs=-50f)).beat,.001f)
    }

    private fun at(time: Double, meter: Int=4) = timing.copy(timestamp=time,position=(time-10)*2,beatsPerBar=meter)

    @Test fun eachBarGetsOneQuickFlashAndFullInversionThatFadesBack() {
        for (meter in listOf(3,4)) for (fps in listOf(30,60,120)) {
            val motion=BeatMotion()
            var flashes=0
            var previous=0f
            for (frame in 0..(fps*8)) {
                val time=10.1+frame.toDouble()/fps
                val sample=motion.update(at(time,meter),loud,time,settings)
                if (sample.flash > previous+.1f) { flashes++; assertEquals(1f,sample.inversion,.001f) }
                previous=sample.flash
            }
            assertEquals("One flash per bar at $fps fps, meter $meter",if (meter==4) 4 else 5,flashes)
        }
        val motion=BeatMotion()
        motion.update(at(11.99),loud,11.99,settings)
        val first=motion.update(at(12.0),loud,12.0,settings)
        assertEquals(.85f,first.flash,.001f)
        assertEquals(1f,first.inversion,.001f)
        val middle=motion.update(at(12.09),loud,12.09,settings)
        assertEquals(0f,middle.flash,0f)
        assertEquals(.5f,middle.inversion,.001f)
        val end=motion.update(at(12.18),loud,12.18,settings)
        assertEquals(0f,end.inversion,.001f)
    }

    @Test fun confidenceLossAndTimingJumpsCannotProduceFalseFlashes() {
        for (uncertain in listOf(at(11.99).copy(barLocked=false),at(11.99).copy(confidence=.3f),
            at(11.99).copy(coasting=true),at(11.99).copy(conflict=RhythmConflict.BAR),at(11.99).copy(signalPresent=false))) {
            val motion=BeatMotion()
            motion.update(uncertain,loud,11.99,settings)
            assertEquals("Acquiring lock is not a downbeat",0f,motion.update(at(12.0),loud,12.0,settings).flash,0f)
        }
        val motion=BeatMotion()
        motion.update(at(11.7),loud,11.7,settings)
        assertEquals("Phase jump is not a downbeat",0f,motion.update(at(12.0).copy(timestamp=11.71),loud,11.71,settings).flash,0f)
        motion.update(at(13.9),loud,13.9,settings)
        assertEquals("Meter/bar corrections are not downbeats",0f,motion.update(at(14.0).copy(barOffset=1),loud,14.0,settings).flash,0f)
    }

    @Test fun phaseJitterDoesNotStrobeTwiceAndPauseClearsTheFlash() {
        val motion=BeatMotion()
        motion.update(at(11.99),loud,11.99,settings)
        assertTrue(motion.update(at(12.0),loud,12.0,settings).flash>0f)
        motion.update(at(12.01).copy(position=3.99),loud,12.01,settings)
        val second=motion.update(at(12.02),loud,12.02,settings)
        assertTrue("Second crossing must not restart flash",second.flash<.5f)
        motion.update(at(12.03).copy(barLocked=false),loud,12.03,settings)
        motion.update(at(12.04).copy(position=3.99),loud,12.04,settings)
        assertEquals("Brief confidence loss must not allow a second flash",0f,motion.update(at(12.05),loud,12.05,settings).flash,0f)
        val paused=motion.update(at(12.02),loud,12.02,settings.copy(paused=true))
        assertEquals(0f,paused.flash,0f); assertEquals(0f,paused.inversion,0f)
        assertEquals(0f,motion.update(at(12.03),loud,12.03,settings).flash,0f)
    }

    @Test fun flashAndInversionCanBeAdjustedIndependently() {
        val motion=BeatMotion()
        val s=settings.copy(downbeatFlash=0f,downbeatInvert=.6f,invertFadeMs=300f)
        motion.update(at(11.99),loud,11.99,s)
        val first=motion.update(at(12.0),loud,12.0,s)
        assertEquals(0f,first.flash,0f); assertEquals(.6f,first.inversion,.001f)
        assertEquals(.3f,motion.update(at(12.15),loud,12.15,s).inversion,.001f)
        assertEquals(MusicalMotion(),motion.update(at(12.16),loud,12.16,s.copy(audioEnabled=false)))
    }
}
