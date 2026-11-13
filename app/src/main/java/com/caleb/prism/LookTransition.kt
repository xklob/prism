package com.caleb.prism

internal data class LookTransition(val settings: VisualSettings, val seconds: Float)

internal fun blendEffects(from: PostEffects, to: PostEffects, fraction: Float): PostEffects {
    if (fraction <= 0f) return from
    if (fraction >= 1f) return to
    fun mix(a: Float, b: Float) = a + (b - a) * fraction
    val a = if (from.enabled) from.strength else 0f
    val b = if (to.enabled) to.strength else 0f
    return PostEffects(enabled = from.enabled || to.enabled, strength = 1f,
        bloom = mix(from.bloom * a, to.bloom * b), blur = mix(from.blur * a, to.blur * b),
        bloomRadius = mix(from.bloomRadius, to.bloomRadius), blurRadius = mix(from.blurRadius, to.blurRadius),
        threshold = mix(from.threshold, to.threshold), quality = maxOf(from.quality, to.quality))
}
