package com.caleb.prism

import kotlin.math.*

/** Streaming band-limited conversion for file playback/recording. No change to 48 kHz input. */
internal class FilePcmResampler(private val rate: Int, private val channels: Int, private val emit: (ShortArray) -> Unit) {
    init { require(rate in 8000..192000 && channels in 1..2) }
    private val radius = ceil(24.0 * max(1.0, rate / 48000.0)).toInt()
    private val ringSize = radius * 4 + 8
    private val ring = Array(channels) { FloatArray(ringSize) }
    private var written = 0L
    private var output = 0L
    private val block = ShortArray(960 * channels)
    private var filled = 0
    private val kernels by lazy {
        Array(1024) { phase ->
            val fraction = phase / 1024.0
            val cutoff = .475 * min(1.0, 48000.0 / rate)
            val values = DoubleArray(radius * 2) { i ->
                val x = i - radius + 1 - fraction
                val sinc = if (abs(x) < 1e-12) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                sinc * (.5 + .5 * cos(PI * x / radius)).coerceAtLeast(0.0)
            }
            val sum = values.sum()
            FloatArray(values.size) { (values[it] / sum).toFloat() }
        }
    }
    fun push(samples: ShortArray) {
        require(samples.size % channels == 0)
        if (rate == 48000) { emit(samples); written += samples.size / channels; return }
        for (frame in 0 until samples.size / channels) {
            for (channel in 0 until channels) ring[channel][(written % ringSize).toInt()] = samples[frame * channels + channel].toFloat()
            written++
            drain(written - radius - 1, Long.MAX_VALUE)
        }
    }
    private fun drain(availableCenter: Long, maxOutput: Long) {
        while (output < maxOutput) {
            val numerator = output * rate
            val center = numerator / 48000
            if (center > availableCenter) return
            val kernel = kernels[((numerator % 48000) * 1024 / 48000).toInt()]
            for (channel in 0 until channels) {
                var value = 0f
                for (i in kernel.indices) {
                    val index = center + i - radius + 1
                    if (index >= 0 && index < written) value += ring[channel][(index % ringSize).toInt()] * kernel[i]
                }
                block[filled++] = value.roundToInt().coerceIn(-32768, 32767).toShort()
            }
            output++
            if (filled == block.size) { emit(block.copyOf()); filled = 0 }
        }
    }
    fun finish() {
        if (rate == 48000) return
        drain(written, (written * 48000 + rate - 1) / rate)
        if (filled > 0) { emit(block.copyOf(filled)); filled = 0 }
    }
}
