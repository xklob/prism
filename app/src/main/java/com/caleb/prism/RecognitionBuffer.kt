package com.caleb.prism

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class RecognitionClip(val pcm: ShortArray, val startNs: Long, val endNs: Long) {
    fun wav(): ByteArray {
        val bytes = pcm.size * 2
        return ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(bytes)
            pcm.forEach { putShort(it) }
        }.array()
    }
}

/** Ten seconds of mono audio in memory. Capture only copies samples; HTTP runs elsewhere. */
class RecognitionBuffer {
    private val samples = ShortArray(16000 * 10)
    private var count = 0
    private var write = 0
    private var endNs = 0L
    @Synchronized fun clear() { count = 0; write = 0; endNs = 0; samples.fill(0) }
    @Synchronized fun append(pcm: ShortArray, firstSampleNs: Long, channels: Int) {
        if (channels !in 1..2 || pcm.size % (3 * channels) != 0) return
        if (endNs != 0L && kotlin.math.abs(firstSampleNs - endNs) > 100_000_000L) clear()
        // A short box filter before 3:1 decimation is sufficient for fingerprinting input.
        for (i in pcm.indices step 3 * channels) {
            var sum = 0
            for (j in 0 until 3 * channels) sum += pcm[i + j]
            samples[write] = (sum / (3 * channels)).toShort()
            write = (write + 1) % samples.size
            count = (count + 1).coerceAtMost(samples.size)
        }
        endNs = firstSampleNs + pcm.size.toLong() / channels * 1_000_000_000L / 48000
    }
    fun clip(nowNs: Long): RecognitionClip? {
        val clip = synchronized(this) {
            if (count < 16000 * 8 || nowNs - endNs !in 0..1_000_000_000L) return null
            RecognitionClip(ShortArray(count) { samples[(write - count + it + samples.size) % samples.size] },
                endNs - count * 1_000_000_000L / 16000, endNs)
        }
        val rms = sqrt(clip.pcm.sumOf { val x = it / 32768.0; x * x } / clip.pcm.size)
        if (rms < .0015) return null
        return clip
    }
}
