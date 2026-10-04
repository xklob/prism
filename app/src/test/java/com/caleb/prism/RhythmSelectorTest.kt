package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test

class RhythmSelectorTest {
    @Test fun equallyStrongDetectorsMustAdmitTempoOrBarDisagreement() {
        val a=RhythmState(bpm=120f,confidence=.9f,barConfidence=.9f,barLocked=true,signalPresent=true)
        for ((b,expected) in listOf(a.copy(bpm=160f) to RhythmConflict.TEMPO,
            a.copy(position=.4) to RhythmConflict.TEMPO,a.copy(barOffset=1) to RhythmConflict.BAR,
            a.copy(beatsPerBar=3) to RhythmConflict.BAR)) {
            val selector=RhythmSelector()
            assertEquals(RhythmConflict.NONE,selector.choose(listOf(a,b),0.0).conflict)
            val result=selector.choose(listOf(a,b),1.0)
            assertEquals(expected,result.conflict)
            assertFalse("Conflicting bar predictions cannot strobe",result.barLocked)
            assertEquals(expected != RhythmConflict.TEMPO,result.locked)
            assertEquals(RhythmConflict.NONE,selector.choose(listOf(a,a),1.1).conflict)
            val manual=a.copy(manualBar=true)
            assertEquals(manual,selector.choose(listOf(manual,b),2.0))
        }
        val selector=RhythmSelector()
        // A different beat index can still identify exactly the same physical bar start.
        val sameBar=a.copy(position=9.0,barOffset=1)
        selector.choose(listOf(a,sameBar),0.0)
        assertEquals(RhythmConflict.NONE,selector.choose(listOf(a,sameBar),1.0).conflict)
    }

    @Test fun sustainedEvidenceCanReplaceWeakTimingWithoutFlappingOrOverridingManualAlignment() {
        val selector = RhythmSelector()
        val weak = RhythmState(bpm=120f, confidence=0.35f)
        val strong = RhythmState(bpm=136f, confidence=0.98f, barConfidence=0.9f, barLocked=true)
        assertEquals(weak, selector.choose(listOf(weak,strong), 0.0))
        assertEquals(weak, selector.choose(listOf(weak,strong), 1.0))
        assertEquals(strong, selector.choose(listOf(weak,strong), 1.6))
        // A brief dropout is not enough evidence to switch the beat grid.
        selector.choose(listOf(strong,weak), 2.0)
        assertEquals(strong, selector.choose(listOf(weak,strong), 2.2))
        val aligned = weak.copy(manualBar=true)
        selector.choose(listOf(strong,aligned), 3.0)
        assertEquals(aligned, selector.choose(listOf(strong,aligned), 10.0))
    }

    @Test fun complementaryModelsKeepCorrectTempoOnMusicalFixtures() {
        for ((name,bpm,meter) in listOf(Triple("drums128",128f,4),Triple("drums174",174f,4),Triple("waltz96",96f,3))) {
            fun rows(suffix: String) = requireNotNull(javaClass.getResourceAsStream("/rhythm/$name$suffix.csv"))
                .bufferedReader().use { it.readLines().map { row -> row.split(',').map(String::toDouble) } }
            val primary=rows("")
            val general=rows("-general")
            val trackers=listOf(RhythmTracker(),RhythmTracker())
            val selector=RhythmSelector()
            var result=RhythmState()
            for ((a,b) in primary.zip(general)) {
                val states=listOf(trackers[0].observe(a[0],a[1].toFloat(),a[2].toFloat(),true),
                    trackers[1].observe(b[0],b[1].toFloat(),b[2].toFloat(),true))
                result=selector.choose(states,a[0])
            }
            println("Selected $name: $result")
            assertTrue(result.locked)
            assertEquals(bpm,result.bpm,2f)
            assertTrue(result.barLocked)
            assertEquals(meter,result.beatsPerBar)
        }
    }
}
