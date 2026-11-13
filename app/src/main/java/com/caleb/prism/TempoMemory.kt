package com.caleb.prism

import kotlin.math.*

/** A bounded, recency- and evidence-weighted tempo history. Missing beats add no zero votes. */
internal class TempoMemory {
    private data class Sample(val time: Double, val bpm: Double, val confidence: Double)
    private val samples = ArrayDeque<Sample>()
    var stable = false; private set
    fun clear() { samples.clear(); stable = false }

    fun observe(time: Double, bpm: Double, confidence: Double): Double {
        if (confidence < .6) return bpm
        if (samples.lastOrNull()?.let { abs(ln(bpm / it.bpm)) > .06 } == true) clear()
        while (samples.isNotEmpty() && time - samples.first().time > 12) samples.removeFirst()
        samples.addLast(Sample(time, bpm, confidence))
        var total = 0.0; var sum = 0.0
        for (sample in samples) {
            val weight = sample.confidence.pow(2) * exp(-(time - sample.time) / 3.0)
            total += weight; sum += sample.bpm * weight
        }
        val average = sum / total
        val variance = samples.sumOf {
            it.confidence.pow(2) * exp(-(time - it.time) / 3.0) * ln(it.bpm / average).pow(2)
        } / total
        stable = samples.size >= 6 && time - samples.first().time >= 3 && variance < .0001
        return average
    }
}
