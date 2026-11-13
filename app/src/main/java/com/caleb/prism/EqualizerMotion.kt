package com.caleb.prism

import kotlin.math.*

/** Display-rate attack/release and peak hold, independent of rendering frame rate. */
class EqualizerMotion {
    val bars = FloatArray(64)
    val peaks = FloatArray(64)
    private val holds = FloatArray(64)
    private var count = 0
    fun update(spectrum: FloatArray, buckets: Int, dt: Float, gain: Float, falloff: Float, peakHold: Float) {
        require(buckets in 8..64)
        if (count != buckets) { bars.fill(0f); peaks.fill(0f); holds.fill(0f); count = buckets }
        val elapsed = dt.coerceIn(0f, .25f)
        for (i in 0 until buckets) {
            val first = i * spectrum.size.toDouble() / buckets
            val last = (i + 1) * spectrum.size.toDouble() / buckets
            var sum = 0.0
            for (bin in floor(first).toInt() until ceil(last).toInt()) {
                val overlap = min(last, bin + 1.0) - max(first, bin.toDouble())
                val value = spectrum[bin].takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                sum += value * value * overlap
            }
            val target = (sqrt(sum / max(1.0, last - first)) * gain).toFloat().coerceIn(0f, 1f)
            val tau = if (target > bars[i]) .025f else falloff.coerceAtLeast(.01f)
            bars[i] += (target - bars[i]) * (1 - exp(-elapsed / tau))
            if (bars[i] >= peaks[i]) { peaks[i] = bars[i]; holds[i] = peakHold }
            else {
                holds[i] = (holds[i] - elapsed).coerceAtLeast(0f)
                if (holds[i] == 0f) peaks[i] = max(bars[i], peaks[i] - elapsed / max(.1f, falloff * 2))
            }
            if (peakHold == 0f) peaks[i] = 0f
        }
    }
}
