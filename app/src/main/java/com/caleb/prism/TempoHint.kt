package com.caleb.prism

import kotlin.math.*

/** Catalog rate corroborates a live tempo; phase and downbeats still come from audio. */
data class TempoHint(val bpm: Double, val key: String, val strength: Double = .10) {
    fun weight(candidate: Double): Double {
        if (!bpm.isFinite() || bpm !in 30.0..300.0) return 0.0
        val normalized = when { bpm < 60 -> bpm * 2; bpm > 200 -> bpm / 2; else -> bpm }
        // A flat neighborhood lets the audio choose the exact rate, including DJ pitch changes.
        // The prior mainly distinguishes tempo families, rather than pulling a good grid off beat.
        val distance = max(0.0, abs(ln(candidate / normalized)) - .05)
        return strength.coerceIn(0.0,.3) * exp(-.5 * (distance / .04).pow(2))
    }
    fun agrees(candidate: Double): Boolean {
        if (!bpm.isFinite() || bpm !in 30.0..300.0 || candidate <= 0) return false
        val normalized = when { bpm < 60 -> bpm * 2; bpm > 200 -> bpm / 2; else -> bpm }
        return abs(ln(candidate / normalized)) < .03
    }
}
