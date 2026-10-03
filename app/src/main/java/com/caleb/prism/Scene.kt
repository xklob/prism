package com.caleb.prism

import android.content.Context
import androidx.compose.ui.graphics.Color

enum class Scene(val title: String, val subtitle: String, val reactive: Boolean, val accent: Color) {
    AURORA("Aurora", "Liquid light, endlessly unfolding", false, Color(0xFFB7A0FF)),
    KALEIDO("Kaleido", "A cathedral of shifting symmetry", false, Color(0xFFFF91D0)),
    WORMHOLE("Wormhole", "Drift through an infinite neon tunnel", false, Color(0xFF7FDCEB)),
    PULSE("Pulse", "Bass becomes a living mandala", true, Color(0xFFCAAEFF)),
    STRINGS("Strings", "A luminous landscape of frequencies", true, Color(0xFF82E8CF)),
    NOVA("Nova", "Sound blossoms into electric petals", true, Color(0xFFFFAA89));
}

enum class AudioSource(val label: String) { MICROPHONE("Microphone"), SYSTEM("System audio") }

enum class Palette(val title: String, val colors: List<Color>) {
    SPECTRUM("Spectrum", listOf(Color(0xFF8364FF), Color(0xFFFF65C6), Color(0xFF5DF0E5))),
    EMBER("Ember", listOf(Color(0xFF7A28AD), Color(0xFFFF517D), Color(0xFFFFC15E))),
    TIDAL("Tidal", listOf(Color(0xFF3842CA), Color(0xFF3FB7E7), Color(0xFF9EFFCD))),
    ACID("Acid", listOf(Color(0xFF7B34FF), Color(0xFFCF47DA), Color(0xFFD8FF53)))
}

data class VisualSettings(
    val scene: Scene = Scene.AURORA,
    val palette: Palette = Palette.SPECTRUM,
    val source: AudioSource = AudioSource.MICROPHONE,
    val speed: Float = 0.7f,
    val intensity: Float = 0.9f,
    val sensitivity: Float = 1.4f,
    val batterySaver: Boolean = false,
    val paused: Boolean = false
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("prism", Context.MODE_PRIVATE)
    fun load() = VisualSettings(
        scene = Scene.entries.getOrElse(prefs.getInt("scene", 0)) { Scene.AURORA },
        palette = Palette.entries.getOrElse(prefs.getInt("palette", 0)) { Palette.SPECTRUM },
        source = AudioSource.entries.getOrElse(prefs.getInt("source", 0)) { AudioSource.MICROPHONE },
        speed = prefs.getFloat("speed", 0.7f).coerceIn(0.15f, 2f),
        intensity = prefs.getFloat("intensity", 0.9f).coerceIn(0.2f, 1.5f),
        sensitivity = prefs.getFloat("sensitivity", 1.4f).coerceIn(0.4f, 4f),
        batterySaver = prefs.getBoolean("batterySaver", false)
    )
    fun save(s: VisualSettings) {
        prefs.edit().putInt("scene", s.scene.ordinal).putInt("palette", s.palette.ordinal)
            .putInt("source", s.source.ordinal).putFloat("speed", s.speed)
            .putFloat("intensity", s.intensity).putFloat("sensitivity", s.sensitivity)
            .putBoolean("batterySaver", s.batterySaver).apply()
    }
}
