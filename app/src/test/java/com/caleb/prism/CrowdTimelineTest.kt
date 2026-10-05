package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test

class CrowdTimelineTest {
    private val cue = CrowdCue(1,0.0,1000.0,0.0,120.0,4,16,0,true,false,
        VisualSettings(audioEnabled=true),1,0.0,1.0,50.0,180.0)
    private val snapshot = CrowdSnapshot(1,0.0,10000.0,0.0,listOf(cue),false,true,1f,1f,1f)

    @Test fun framesSampleTheSameWindowAtDifferentRefreshRates() {
        val starts = mutableListOf<Double>()
        for (fps in listOf(60,90,120)) {
            val timeline = CrowdTimeline()
            timeline.sample(snapshot,800.0,true,true)
            val frames = (0..30).map { 901.0 + it * 1000.0 / fps }
            val lit = frames.filter { timeline.sample(snapshot,it,true,true).motion.flash > 0 }
            assertTrue(lit.isNotEmpty())
            assertTrue(lit.all { it in 1000.0..<1050.0 })
            starts += lit.first()
        }
        assertTrue(starts.max() - starts.min() <= 1000.0 / 60)
    }
    @Test fun expiredWindowsAreSkippedAndRejoiningCannotReplayAFlash() {
        val timeline = CrowdTimeline()
        timeline.sample(snapshot,999.0,true,true)
        assertEquals(0f,timeline.sample(snapshot,1070.0,true,true).motion.flash,0f)
        assertEquals(1L,timeline.missed)
        assertEquals(1f,timeline.sample(snapshot,1500.0,true,true).motion.flash,0f)
        timeline.sample(snapshot,1999.0,false,true)
        assertEquals(0f,timeline.sample(snapshot,2005.0,true,true).motion.flash,0f)
        assertEquals(1f,timeline.sample(snapshot,2500.0,true,true).motion.flash,0f)
    }
    @Test fun blackoutExpiryLowConfidenceAndLocalOptOutSuppressFlashes() {
        for (sample in listOf(snapshot.copy(blackout=true),snapshot.copy(validUntil=990.0),
            snapshot.copy(musicReady=false),snapshot.copy(barConfidence=.2f))) {
            val timeline = CrowdTimeline()
            timeline.sample(sample,800.0,true,true)
            val result = timeline.sample(sample,1000.0,true,true)
            assertEquals(0f,result.motion.flash,0f)
            assertEquals(0f,result.motion.inversion,0f)
        }
        val timeline = CrowdTimeline()
        timeline.sample(snapshot,800.0,true,true)
        assertEquals(0f,timeline.sample(snapshot,1000.0,true,false).motion.flash,0f)
    }
    @Test fun scheduledCueIsNotAppliedOnPacketArrivalAndInversionUsesItsAbsoluteAge() {
        val future = cue.copy(revision=2,effective=3000.0,anchor=3000.0,settings=cue.settings.copy(scene=Scene.JULIA))
        val scheduled = snapshot.copy(cues=listOf(cue,future))
        val timeline = CrowdTimeline()
        timeline.sample(scheduled,800.0,true,true)
        assertEquals(Scene.AURORA,timeline.sample(scheduled,2999.0,true,true).cue.settings.scene)
        assertEquals(Scene.JULIA,timeline.sample(scheduled,3000.0,true,true).cue.settings.scene)
        assertEquals(.5f,timeline.sample(scheduled,3090.0,true,true).motion.inversion,.001f)
        assertEquals(0f,timeline.sample(scheduled,3090.0,true,true).motion.flash,0f)
    }
    @Test fun aClockCorrectionCannotReplayThePriorFlash() {
        val timeline = CrowdTimeline()
        timeline.sample(snapshot,800.0,true,true)
        timeline.sample(snapshot,1000.0,true,true)
        timeline.sample(snapshot,1500.0,true,true)
        assertEquals(0f,timeline.sample(snapshot,1010.0,true,true).motion.flash,0f)
    }
    @Test fun aBackwardCorrectionCannotRestartAnExpiredDownbeatEnvelope() {
        val timeline = CrowdTimeline()
        timeline.sample(snapshot,800.0,true,true)
        timeline.sample(snapshot,1000.0,true,true)
        timeline.sample(snapshot,1250.0,true,true)
        val corrected = timeline.sample(snapshot,1010.0,true,true)
        assertEquals(0f,corrected.motion.flash,0f)
        assertEquals(0f,corrected.motion.inversion,0f)
        assertEquals(1f,timeline.sample(snapshot,3000.0,true,true).motion.flash,0f)
    }
}
