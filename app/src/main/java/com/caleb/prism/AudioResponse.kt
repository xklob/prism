package com.caleb.prism

import kotlin.math.exp

data class AudioMotion(val bass: Float = 0f, val mid: Float = 0f, val treble: Float = 0f, val beat: Float = 0f) {
    val energy: Float get() = bass * 0.5f + mid * 0.3f + treble * 0.2f
}

/** Shared by every scene. Disabling audio clears modulation even when the image is paused. */
class AudioResponse {
    var motion = AudioMotion(); private set

    fun update(input: AudioLevels, enabled: Boolean, paused: Boolean, dt: Float,
               amount: Float, bassGain: Float, midGain: Float, trebleGain: Float, smoothing: Float): AudioMotion {
        if (!enabled || amount <= 0f) {
            motion = AudioMotion()
            return motion
        }
        if (paused) return motion
        fun follow(old: Float, level: Float, gain: Float, beat: Boolean = false): Float {
            val target = (level * gain * amount).coerceIn(0f, 3f)
            // Keep transients snappy; smoothing mainly controls the release tail.
            val tau = if (target > old) 0.008f + smoothing * 0.035f
                      else if (beat) 0.035f + smoothing * 0.10f else 0.025f + smoothing * 0.25f
            val blend = 1f - exp(-dt.coerceIn(0f, 0.1f) / tau)
            return old + (target - old) * blend
        }
        motion = AudioMotion(follow(motion.bass, input.bass, bassGain),
            follow(motion.mid, input.mid, midGain), follow(motion.treble, input.high, trebleGain),
            follow(motion.beat, input.beat, bassGain, beat = true))
        return motion
    }
}
