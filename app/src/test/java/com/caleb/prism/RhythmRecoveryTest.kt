package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test

class RhythmRecoveryTest {
    @Test fun recoveryNeedsSustainedEvidenceAndDoesNotFlapOrOverrideManualTiming() {
        val gate=RhythmRecovery()
        val weak=RhythmState(bpm=125f,confidence=.3f)
        val strong=weak.copy(confidence=.8f)
        assertEquals(weak,gate.choose(weak,strong,0.0))
        assertEquals(strong,gate.choose(weak,strong,1.1))
        assertTrue(gate.usingRecovery)
        assertEquals(strong,gate.choose(strong.copy(confidence=.85f),strong,2.0))
        assertEquals(strong,gate.choose(strong.copy(confidence=.85f),strong,4.0))
        gate.choose(strong,weak,5.0)
        assertEquals(strong,gate.choose(weak,strong,5.5))
        val manual=weak.copy(manualBar=true,barLocked=true)
        assertEquals(manual,gate.choose(strong,manual,8.0))
        assertEquals(manual,gate.choose(strong,manual,12.0))
        gate.choose(strong,weak,13.0)
        assertEquals(strong,gate.choose(strong,weak,14.1))
        assertFalse(gate.usingRecovery)
    }

    @Test fun completeSelectionKeepsTheKnownMusicalFixtures() {
        for ((name,bpm,meter) in listOf(Triple("drums128",128f,4),Triple("drums174",174f,4),Triple("waltz96",96f,3))) {
            fun rows(suffix: String) = requireNotNull(javaClass.getResourceAsStream("/rhythm/$name$suffix.csv"))
                .bufferedReader().use { it.readLines().map { row -> row.split(',').map(String::toDouble) } }
            val primary=List(2) { RhythmTracker() }; val repeated=List(2) { RhythmTracker(true) }
            val selector=RhythmSelector(); val recoverySelector=RhythmSelector(); val gate=RhythmRecovery()
            var result=RhythmState()
            for ((a,b) in rows("").zip(rows("-general"))) {
                fun states(trackers: List<RhythmTracker>)=trackers.zip(listOf(a,b)).map { (tracker,row) ->
                    tracker.observe(row[0],row[1].toFloat(),row[2].toFloat(),true)
                }
                result=gate.choose(selector.choose(states(primary),a[0]),recoverySelector.choose(states(repeated),a[0]),a[0])
            }
            assertTrue("$name must lock",result.locked)
            assertEquals(bpm,result.bpm,2f)
            assertTrue("$name must find measures: $result",result.barLocked)
            assertEquals(meter,result.beatsPerBar)
        }
    }
}
