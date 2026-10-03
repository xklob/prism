package com.caleb.prism

import kotlin.math.*

data class AudioLevels(
    val bass: Float = 0f, val mid: Float = 0f, val high: Float = 0f,
    val energy: Float = 0f, val beat: Float = 0f,
    val bands: FloatArray = FloatArray(32)
)

/** Streaming Hann-windowed FFT. Input PCM and derived levels stay in memory only. */
class SpectrumAnalyzer(private val sampleRate: Int = 48000, val size: Int = 2048) {
    init { require(size > 1 && size and (size - 1) == 0); require(sampleRate > 0) }
    private val real = DoubleArray(size)
    private val imaginary = DoubleArray(size)
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
    private var previous = AudioLevels()
    private var average = 0.01
    private var sinceBeat = 1.0

    fun analyze(samples: ShortArray, sensitivity: Float = 1.4f): AudioLevels {
        require(samples.size == size)
        val gain = sensitivity.coerceIn(0.4f, 4f).toDouble()
        var squareSum = 0.0
        for (i in 0 until size) {
            val value = samples[i] / 32768.0
            real[i] = value * window[i]
            imaginary[i] = 0.0
            squareSum += value * value
        }
        fft()
        fun power(low: Double, high: Double): Double {
            val first = max(1, ceil(low * size / sampleRate).toInt())
            val last = min(size / 2 - 1, floor(high * size / sampleRate).toInt())
            var sum = 0.0
            for (i in first..last) sum += real[i] * real[i] + imaginary[i] * imaginary[i]
            return sqrt(sum) / size * 3.2
        }
        fun normalized(value: Double) = (1.0 - exp(-value * gain * 3.2)).toFloat().coerceIn(0f, 1f)
        fun smooth(value: Float, old: Float) = old + (value - old) * if (value > old) 0.72f else 0.16f
        val rms = sqrt(squareSum / size)
        sinceBeat += size.toDouble() / sampleRate
        val beat = if (rms > average * 1.45 + 0.009 && sinceBeat > 0.18) {
            sinceBeat = 0.0; 1f
        } else previous.beat * 0.82f
        average += (rms - average) * 0.06
        val bands = FloatArray(32) { i ->
            val low = 35.0 * (14000.0 / 35.0).pow(i / 32.0)
            val high = 35.0 * (14000.0 / 35.0).pow((i + 1) / 32.0)
            smooth(normalized(power(low, high)), previous.bands[i])
        }
        return AudioLevels(
            smooth(normalized(power(35.0, 250.0)), previous.bass),
            smooth(normalized(power(250.0, 2500.0)), previous.mid),
            smooth(normalized(power(2500.0, 16000.0)), previous.high),
            smooth(normalized(rms), previous.energy), beat, bands
        ).also { previous = it }
    }

    private fun fft() {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im
            }
        }
        var length = 2
        while (length <= size) {
            val stepR = cos(-2 * PI / length)
            val stepI = sin(-2 * PI / length)
            for (start in 0 until size step length) {
                var wr = 1.0
                var wi = 0.0
                for (offset in 0 until length / 2) {
                    val a = start + offset
                    val b = a + length / 2
                    val br = real[b] * wr - imaginary[b] * wi
                    val bi = real[b] * wi + imaginary[b] * wr
                    real[b] = real[a] - br; imaginary[b] = imaginary[a] - bi
                    real[a] += br; imaginary[a] += bi
                    val next = wr * stepR - wi * stepI
                    wi = wr * stepI + wi * stepR; wr = next
                }
            }
            length = length shl 1
        }
    }
}
