package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test

class RecordingTimelineTest {
    private val origin = 800_000_000_000L
    @Test fun preservesInitialSilenceAndStereoSampleOrder() {
        val timeline = RecordingTimeline(2, origin)
        val chunks = mutableListOf<RecordingTimeline.Chunk>()
        timeline.append(shortArrayOf(12, 34, 56, 78), origin + 20_000_000, chunks::add)
        assertEquals(962, timeline.frames)
        assertEquals(0L, chunks.first().frame)
        assertTrue(chunks.first().samples.all { it == 0.toShort() })
        assertEquals(960L, chunks.last().frame)
        assertArrayEquals(shortArrayOf(12, 34, 56, 78), chunks.last().samples)
    }

    @Test fun trimsPreRollAndOverlappingInputWithoutMovingLaterAudio() {
        val timeline = RecordingTimeline(1, origin)
        val chunks = mutableListOf<RecordingTimeline.Chunk>()
        timeline.append(ShortArray(960) { it.toShort() }, origin - 10_000_000, chunks::add)
        assertEquals(480, timeline.frames)
        assertEquals(480.toShort(), chunks.first().samples.first())
        timeline.append(ShortArray(960) { (it + 1000).toShort() }, origin + 5_000_000, chunks::add)
        assertEquals(1200, timeline.frames)
        assertEquals(1240.toShort(), chunks.last().samples.first())
    }

    @Test fun timestampRoundingDoesNotRepeatedlyInsertSilence() {
        val timeline = RecordingTimeline(1, origin)
        val chunks = mutableListOf<RecordingTimeline.Chunk>()
        repeat(100) { block ->
            timeline.append(ShortArray(960) { 100 }, origin + block * 20_000_000L + 1_000_000, chunks::add)
        }
        assertEquals(96_000, timeline.frames)
        assertTrue(chunks.all { chunk -> chunk.samples.all { it == 100.toShort() } })
    }

    @Test fun padsMissingCaptureAndFinalTailInClockTime() {
        val timeline = RecordingTimeline(1, origin)
        val chunks = mutableListOf<RecordingTimeline.Chunk>()
        timeline.append(ShortArray(960) { 1 }, origin, chunks::add)
        timeline.append(ShortArray(960) { 2 }, origin + 60_000_000, chunks::add)
        timeline.finish(origin + 100_000_000, chunks::add)
        val samples = chunks.flatMap { it.samples.toList() }
        assertEquals(4800, samples.size)
        assertTrue(samples.subList(960, 2880).all { it == 0.toShort() })
        assertEquals(2.toShort(), samples[2880])
        assertTrue(samples.subList(3840, 4800).all { it == 0.toShort() })
        for ((a, b) in chunks.zipWithNext()) assertEquals(a.frame + a.samples.size, b.frame)
    }

    @Test(expected = IllegalStateException::class) fun rejectsAnUnboundedCaptureClockJump() {
        RecordingTimeline(1, origin).append(shortArrayOf(1), origin + 100_000_000_000L) {}
    }
}
