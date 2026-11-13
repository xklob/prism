package com.caleb.prism

/** Frame-rate feedback changes pixel cost, never animation time or audio clocks. */
internal class RenderQuality {
    private val scales = floatArrayOf(1f, .85f, .7f, .55f, .45f)
    private var level = 0
    private var seconds = 0.0
    private var frames = 0
    private var healthySeconds = 0.0
    private var lastTarget = 0
    val scale get() = scales[level]

    fun sample(frameSeconds: Double, targetFps: Int, enabled: Boolean, thermal: Int, paused: Boolean): Float {
        if (!enabled) { level = 0; resetWindow(); healthySeconds = 0.0; return scale }
        val thermalFloor = when { thermal >= 4 -> 4; thermal >= 3 -> 3; thermal >= 2 -> 1; else -> 0 }
        if (level < thermalFloor) { level = thermalFloor; resetWindow(); healthySeconds = 0.0 }
        if (paused || !frameSeconds.isFinite() || frameSeconds <= 0.0 || frameSeconds > 2.0 || targetFps != lastTarget) {
            lastTarget = targetFps; resetWindow(); healthySeconds = 0.0; return scale
        }
        seconds += frameSeconds; frames++
        if (seconds < 3.0) return scale
        val fps = frames / seconds
        if (fps < targetFps * .88) {
            level = (level + 1).coerceAtMost(scales.lastIndex)
            healthySeconds = 0.0
        } else if (fps >= targetFps * .96) {
            healthySeconds = if (level > thermalFloor) healthySeconds + seconds else 0.0
            if (healthySeconds >= 12.0 && level > thermalFloor) { level--; healthySeconds = 0.0 }
        } else healthySeconds = 0.0
        resetWindow()
        return scale
    }

    private fun resetWindow() { seconds = 0.0; frames = 0 }
}

data class RenderQualityReport(val scale: Float = 1f, val width: Int = 0, val height: Int = 0,
                              val thermal: Int = 0, val targetFps: Int = 60)

data class PostEffects(
    val enabled: Boolean = false, val strength: Float = 1f,
    val bloom: Float = .35f, val bloomRadius: Float = .5f, val threshold: Float = .65f,
    val blur: Float = 0f, val blurRadius: Float = .35f, val quality: Int = 1
) {
    val active get() = enabled && strength > 0f && (bloom > 0f || blur > 0f)
}
