package com.caleb.prism

import kotlin.math.abs

/** Maps the capture clock to the video's clock without stretching or dropping silent time. */
internal class RecordingTimeline(private val channels: Int, private val originNs: Long) {
    data class Chunk(val samples: ShortArray, val frame: Long)
    var frames = 0L; private set
    private val rate = 48_000L

    fun append(samples: ShortArray, firstSampleNs: Long, emit: (Chunk) -> Unit) {
        require(samples.size % channels == 0)
        var start = Math.floorDiv((firstSampleNs - originNs) * rate, 1_000_000_000L)
        // Hardware timestamp rounding must not insert/remove a few samples on every read.
        if (abs(start - frames) <= 96) start = frames
        if (start > frames) padTo(start, emit)
        val skip = (frames - start).coerceIn(0, (samples.size / channels).toLong()).toInt()
        var offset = skip * channels
        while (offset < samples.size) {
            val end = minOf(samples.size, offset + 960 * channels)
            emit(Chunk(samples.copyOfRange(offset, end), frames))
            frames += (end - offset) / channels
            offset = end
        }
    }

    fun finish(stopNs: Long, emit: (Chunk) -> Unit) {
        padTo(((stopNs - originNs).coerceAtLeast(0) * rate / 1_000_000_000L), emit)
    }

    private fun padTo(target: Long, emit: (Chunk) -> Unit) {
        check(target - frames <= rate * 10) { "Audio capture stalled for too long" }
        while (frames < target) {
            val count = minOf(960L, target - frames).toInt()
            emit(Chunk(ShortArray(count * channels), frames))
            frames += count
        }
    }
}
