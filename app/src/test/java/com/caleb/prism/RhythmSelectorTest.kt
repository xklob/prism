package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test

class RhythmSelectorTest {
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
