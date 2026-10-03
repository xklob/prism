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
}
