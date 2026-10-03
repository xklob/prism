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
            assertTrue((levels.bands.toList() + listOf(levels.bass, levels.mid, levels.high, levels.energy)).all { it.isFinite() && it in 0f..1f })
        }
    }

    @Test fun transientEnergyAndSilenceDecay() {
        val analyzer = SpectrumAnalyzer()
        repeat(40) { analyzer.analyze(ShortArray(2048)) }
        val impact = analyzer.analyze(tone(93.75, 0.8))
        assertTrue(impact.bass > 0.5f)
        var end = impact
        repeat(100) { end = analyzer.analyze(ShortArray(2048)) }
        assertTrue(end.bass < 0.0001f)
        assertTrue(end.energy < 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompletePcmBlock() { SpectrumAnalyzer().analyze(ShortArray(32)) }

    @Test fun quietMusicHasUsefulDynamicRangeButNoiseDoesNot() {
        val quiet = SpectrumAnalyzer()
        val loud = SpectrumAnalyzer()
        val low = quiet.analyze(tone(93.75, 0.015))
        val high = loud.analyze(tone(93.75, 0.5))
        assertTrue("Quiet playback should react on its first block: ${low.bass}", low.bass > 0.4f)
        assertTrue("Loud music should retain headroom: ${high.bass}", high.bass in 0.5f..0.95f)
        val noise = SpectrumAnalyzer()
        repeat(150) { assertEquals(0f, noise.analyze(tone(1000.0, 0.001)).energy, 0f) }
    }

    @Test fun successiveAmplitudePeaksStayDistinct() {
        val analyzer = SpectrumAnalyzer()
        repeat(5) {
            repeat(8) { analyzer.analyze(ShortArray(2048)) }
            val kick = analyzer.analyze(tone(93.75, 0.12))
            assertTrue(kick.bass > 0.5f)
            var tail = kick
            repeat(5) { tail = analyzer.analyze(ShortArray(2048)) }
            assertTrue("Release should separate beats", tail.bass < kick.bass * 0.15f)
        }
        repeat(100) { analyzer.analyze(tone(93.75, 0.12)) }
        assertTrue(analyzer.analyze(tone(93.75, 0.12)).bass > 0.5f)
    }
}
