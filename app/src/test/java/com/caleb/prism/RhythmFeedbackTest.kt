package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test

class RhythmFeedbackTest {
    private val timing=RhythmState(timestamp=10.0,bpm=128f,confidence=.9f,barConfidence=.85f,
        barLocked=true,signalPresent=true)

    @Test fun captureAndConfidenceAreIndependentAndStaleDataNeverLooksLocked() {
        val good=RhythmFeedback.from(true,timing,10.1)
        assertEquals("Sound received",good.inputLabel)
        assertTrue(good.beatReady && good.barReady)
        for (state in listOf(timing.copy(signalPresent=false),timing.copy(timestamp=9.0))) {
            val stale=RhythmFeedback.from(true,state,10.1)
            assertFalse(stale.beatReady || stale.barReady)
            assertEquals(0f,stale.beatConfidence,0f)
            assertEquals(0f,stale.barConfidence,0f)
        }
        assertEquals("Input stalled",RhythmFeedback.from(true,timing,11.0).beatLabel)
        assertEquals("Disconnected",RhythmFeedback.from(false,timing,10.1).beatLabel)
    }

    @Test fun ambiguousBarsDoNotHideWorkingBeatDetection() {
        val feedback=RhythmFeedback.from(true,timing.copy(barLocked=false,barConfidence=.2f),10.1)
        assertTrue(feedback.beatReady)
        assertFalse(feedback.barReady)
        assertEquals("Beat locked",feedback.beatLabel)
        assertTrue(feedback.help.contains("Bar starts here"))
        val coast=RhythmFeedback.from(true,timing.copy(coasting=true),10.1)
        assertEquals("Coasting",coast.beatLabel)
        assertFalse(coast.barReady)
    }

    @Test fun conflictsAndManualOverridesAreClearlyLabeled() {
        val conflict=RhythmFeedback.from(true,timing.copy(conflict=RhythmConflict.TEMPO),10.1)
        assertEquals("Tempo uncertain",conflict.beatLabel)
        assertFalse(conflict.beatReady || conflict.barReady)
        val manual=RhythmFeedback.from(true,timing.copy(manualTempo=true,manualBar=true),10.1)
        assertEquals("Manual tempo",manual.beatLabel)
        assertEquals("Bar aligned",manual.barLabel)
        assertTrue(manual.help.contains("Auto timing"))
    }
}
