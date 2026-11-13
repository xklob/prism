package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class EqualizerMotionTest {
    @Test fun everyBucketCountPreservesFrequencyOrderAndSilence() {
        for (count in 8..64) {
            val motion=EqualizerMotion()
            val spectrum=FloatArray(64).also { it[52]=1f }
            repeat(30) { motion.update(spectrum,count,1f/60,1f,.3f,.6f) }
            assertTrue(motion.bars.take(count).max()>0)
            val peak=motion.bars.indices.maxBy { motion.bars[it] }
            assertTrue(peak in floor(51.0*count/64).toInt()..ceil(53.0*count/64).toInt())
            repeat(600) { motion.update(FloatArray(64),count,1f/60,1f,.3f,.6f) }
            assertTrue(motion.bars.all { it<.0001f })
            assertTrue(motion.peaks.all { it<.0001f })
        }
    }
    @Test fun attackReleaseAndPeakHoldAreIndependentOfFrameRate() {
        fun at(fps: Int): EqualizerMotion {
            val motion=EqualizerMotion()
            repeat(fps) { motion.update(FloatArray(64) { .7f },32,1f/fps,1f,.3f,.6f) }
            repeat(fps/2) { motion.update(FloatArray(64),32,1f/fps,1f,.3f,.6f) }
            return motion
        }
        val a=at(30); val b=at(120)
        assertEquals(a.bars[0],b.bars[0],.002f)
        assertEquals(.7f,a.peaks[0],.002f)
        assertEquals(a.peaks[0],b.peaks[0],.002f)
    }
    @Test fun subBinBassBucketsHaveNoArtificialGaps() {
        val analyzer=SpectrumAnalyzer()
        val input=ShortArray(2048) { (sin(2*PI*46.875*it/48000)*16000).toInt().toShort() }
        val spectrum=analyzer.analyze(input).spectrum
        assertEquals(64,spectrum.size)
        assertTrue(spectrum.take(5).all { it>0f })
        assertTrue(spectrum.drop(45).all { it<.01f })
    }
}
