package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class LocalAudioTimingTest {
    private fun frame(time: Double, bpm: Float = 120f, confidence: Float = .9f, phrase: Int? = null): LocalFrame {
        val r = RhythmState(timestamp = time, bpm = bpm, position = time * bpm / 60,
            confidence = confidence, phaseConfidence = confidence, barLocked = true, barConfidence = .85f,
            signalPresent = true, lastBeatTime = floor(time * bpm / 60) * 60 / bpm)
        return LocalFrame(time, r, PhraseState(bar = phrase, confidence = if (phrase == null) 0f else .6f), .3f, .2f, .1f, .4f, .1f, .5f)
    }
    @Test fun completeTrackBackfillsAcquisitionAndPreservesTempoThroughAQuietBridge() {
        val frames = (0..2000).map { n -> val t = n / 25.0; frame(t, confidence = if (t in 8.0..24.0 || t in 50.0..78.0) .9f else .1f) }
        val map = LocalTimingMap(frames, 80.0)
        assertEquals(120f, map.bpm, .01f)
        for (t in listOf(0.0, 5.25, 30.0, 42.25, 60.0)) {
            val r = map.sample(t).rhythm
            assertTrue("$t has a precomputed grid", r.locked)
            assertEquals(t * 2, r.position, .0001)
            assertTrue(r.barLocked)
        }
        assertTrue(map.markers.size > 120)
        assertNull(map.sample(0.0).phrase.bar)
    }
    @Test fun uncertainAudioDoesNotInventBeatOrPhraseBoundaries() {
        val map = LocalTimingMap((0..500).map { frame(it / 25.0, confidence = .1f) }, 20.0)
        assertEquals(0f, map.bpm, 0f)
        assertFalse(map.sample(10.0).rhythm.locked)
        assertNull(map.sample(10.0).phrase.bar)
        assertTrue(map.markers.isEmpty())
    }
    @Test fun phraseEvidenceBackfillsAndSeekingDoesNotChangeTheMap() {
        val map = LocalTimingMap(listOf(frame(16.0, phrase = 9), frame(24.0, phrase = 13), frame(32.0, phrase = 1)), 40.0)
        assertEquals(1, map.sample(0.0).phrase.bar)
        assertEquals(6, map.sample(10.0).phrase.bar)
        val before = map.sample(5.125)
        map.sample(39.0); map.sample(0.0)
        assertEquals(before, map.sample(5.125))
    }
    @Test fun fileResamplingPreservesStereoPitchDurationAndChunkBoundaries() {
        val pcm = ShortArray(44100 * 2) { i -> (sin(2 * PI * (if (i % 2 == 0) 440 else 880) * (i / 2) / 44100) * 12000).roundToInt().toShort() }
        fun convert(chunk: Int): ShortArray {
            val result = ArrayList<Short>()
            val sampler = FilePcmResampler(44100, 2) { result.addAll(it.toList()) }
            var at = 0
            while (at < pcm.size) { val end = minOf(at + chunk * 2, pcm.size); sampler.push(pcm.copyOfRange(at, end)); at = end }
            sampler.finish(); return result.toShortArray()
        }
        val one = convert(44100); val chunks = convert(137)
        assertArrayEquals(one, chunks); assertEquals(96000, one.size)
        fun correlation(channel: Int, hz: Int): Double {
            var re = 0.0; var im = 0.0
            for (n in 500 until 47500) { val sample = one[n * 2 + channel] / 32768.0; re += sample * cos(2 * PI * hz * n / 48000); im += sample * sin(2 * PI * hz * n / 48000) }
            return hypot(re, im) / 47000
        }
        assertTrue(correlation(0, 440) > .17); assertTrue(correlation(1, 880) > .17)
        assertTrue(correlation(0, 880) < .001); assertTrue(correlation(1, 440) < .001)
    }
    @Test fun downsamplingRejectsUltrasonicAliasing() {
        fun rms(hz: Int): Double {
            val out = ArrayList<Short>()
            val resampler = FilePcmResampler(192000, 1) { out.addAll(it.toList()) }
            resampler.push(ShortArray(19200) { (sin(2 * PI * hz * it / 192000) * 12000).roundToInt().toShort() }); resampler.finish()
            return sqrt(out.drop(200).dropLast(200).sumOf { (it / 32768.0).pow(2) } / (out.size - 400))
        }
        assertTrue(rms(60000) < rms(5000) * .01)
    }
}
