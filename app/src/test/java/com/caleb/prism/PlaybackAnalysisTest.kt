package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PlaybackAnalysisTest {
    @Test fun quietPlaybackDrivesTheMeterWithoutChangingTheRecordedSamples() {
        val input = ShortArray(960 * 2) { (sin(2 * PI * 120 * (it / 2) / 48000) * 30).toInt().toShort() }
        val original = input.copyOf()
        val output = ShortArray(960)
        val conditioner = PlaybackAnalysis()
        val analyzer = SpectrumAnalyzer(48000, 1024)
        repeat(12) { conditioner.process(input, 2, output) }
        val padded = ShortArray(1024) { output[it % output.size] }
        assertTrue("Quiet digital playback must remain visible", analyzer.analyze(padded, 1f).energy > .05f)
        assertArrayEquals("The recorder's stereo samples are untouched", original, input)
        assertTrue(conditioner.rawRms in .0005f.. .001f)
    }
    @Test fun oppositePhaseStereoDoesNotDisappearButRealSilenceStaysSilent() {
        val input = ShortArray(1920)
        for (i in 0 until 960) {
            input[2*i] = (sin(2*PI*440*i/48000)*8000).toInt().toShort()
            input[2*i+1] = (-input[2*i]).toShort()
        }
        val output = ShortArray(960)
        val conditioner = PlaybackAnalysis()
        conditioner.process(input, 2, output)
        assertTrue(conditioner.cancelingStereo)
        assertTrue(output.any { abs(it.toInt()) > 1000 })
        conditioner.process(ShortArray(1920), 2, output)
        assertEquals(0f, conditioner.rawRms, 0f)
        assertTrue(output.all { it == 0.toShort() })
    }
}
