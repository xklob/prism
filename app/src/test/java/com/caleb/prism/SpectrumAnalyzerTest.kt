package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumAnalyzerTest {
    private fun tone(hz: Double, amplitude: Double = 0.4) = ShortArray(2048) {
        (sin(2 * PI * hz * it / 48000) * amplitude * Short.MAX_VALUE).toInt().toShort()
    }
    private fun settled(hz: Double, sensitivity: Float = 1.4f): AudioLevels {
        val analyzer = SpectrumAnalyzer()
        var result = AudioLevels()
        repeat(30) { result = analyzer.analyze(tone(hz), sensitivity) }
        return result
    }

    @Test fun silenceStaysSilent() {
        val analyzer = SpectrumAnalyzer()
        repeat(100) {
            val levels = analyzer.analyze(ShortArray(2048))
            assertEquals(0f, levels.energy, 0f)
            assertEquals(0f, levels.beat, 0f)
            assertTrue(levels.bands.all { it == 0f })
        }
    }

    @Test fun frequenciesDriveTheirOwnBands() {
        val bass = settled(93.75)
        val mid = settled(1007.8125)
        val high = settled(6000.0)
        assertTrue("Low tone must drive bass", bass.bass > 0.5f && bass.bass > bass.mid * 10)
        assertTrue("Mid tone must drive midrange", mid.mid > 0.5f && mid.mid > mid.bass * 10 && mid.mid > mid.high * 10)
        assertTrue("High tone must drive treble", high.high > 0.5f && high.high > high.mid * 10)
        val peak = high.bands.indices.maxBy { high.bands[it] }
        assertTrue("6 kHz should occupy the upper spectrum", peak in 25..29)
    }

    @Test fun gainChangesResponseAndAllOutputsAreBounded() {
        assertTrue(settled(1000.0, 3f).energy > settled(1000.0, 0.5f).energy)
        val analyzer = SpectrumAnalyzer()
        repeat(60) { iteration ->
            val levels = analyzer.analyze(if (iteration % 2 == 0) ShortArray(2048) { Short.MAX_VALUE } else tone(8000.0, 1.0), 4f)
            assertTrue((levels.bands.toList() + listOf(levels.bass, levels.mid, levels.high, levels.energy, levels.beat)).all { it.isFinite() && it in 0f..1f })
        }
    }

    @Test fun transientProducesBeatAndSilenceDecays() {
        val analyzer = SpectrumAnalyzer()
        repeat(40) { analyzer.analyze(ShortArray(2048)) }
        val impact = analyzer.analyze(tone(93.75, 0.8))
        assertEquals(1f, impact.beat, 0f)
        var end = impact
        repeat(100) { end = analyzer.analyze(ShortArray(2048)) }
        assertTrue(end.bass < 0.0001f)
        assertTrue(end.energy < 0.0001f)
        assertTrue(end.beat < 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompletePcmBlock() { SpectrumAnalyzer().analyze(ShortArray(32)) }
}
