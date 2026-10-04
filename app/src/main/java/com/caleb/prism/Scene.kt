package com.caleb.prism

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

// Stable IDs preserve existing installations when scenes are removed or reordered.
enum class Scene(val id: Int, val title: String, val subtitle: String, val accent: Color) {
    AURORA(0, "Aurora", "Interwoven currents and contour fields", Color(0xFFB7A0FF)),
    KALEIDO(1, "Kaleido", "Recursive geometry inside every reflection", Color(0xFFFF91D0)),
    WORMHOLE(2, "Wormhole", "An infinite tunnel of engraved geometry", Color(0xFF7FDCEB)),
    JULIA(6, "Julia", "Explore the edge of an evolving fractal", Color(0xFFFFD38E));

    val detailLabel: String get() = when (this) {
        AURORA -> "Contour density"; KALEIDO -> "Recursion depth"; WORMHOLE -> "Tunnel detail"
        JULIA -> "Fractal iterations"
    }
    val symmetryLabel: String get() = when (this) {
        AURORA -> "Flow harmonics"; KALEIDO -> "Mirror segments"; WORMHOLE -> "Tunnel facets"
        JULIA -> "Symmetry"
    }
    val distortionLabel: String get() = when (this) {
        WORMHOLE -> "Tunnel twist"; JULIA -> "Fractal shape"; else -> "Distortion"
    }
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
    val audioEnabled: Boolean = false,
    val audioAmount: Float = 1f,
    val speed: Float = 0.7f,
    val intensity: Float = 0.95f,
    val sensitivity: Float = 1.4f,
    val batterySaver: Boolean = false,
    val paused: Boolean = false,
    val complexity: Float = 0.72f,
    val symmetry: Int = 8,
    val distortion: Float = 0.6f,
    val zoom: Float = 1f,
    val lineWidth: Float = 1.1f,
    val hue: Float = 0f,
    val saturation: Float = 1f,
    val contrast: Float = 1.05f,
    val colorSpeed: Float = 0.04f,
    val rotation: Float = 0.12f,
    val morph: Float = 0.45f,
    val beatImpact: Float = 1f,
    val barImpact: Float = 0.9f,
    val flowImpact: Float = 0.5f,
    val pulseLength: Float = 0.28f,
    val textureAmount: Float = 0f,
    val downbeatFlash: Float = 0.85f,
    val downbeatInvert: Float = 1f,
    val invertFadeMs: Float = 180f,
    val beatsPerBar: Int = 0,
    val syncOffsetMs: Float = 0f
) {
    fun randomLook(random: Random = Random.Default) = copy(
        palette = Palette.entries[random.nextInt(Palette.entries.size)],
        complexity = random.nextFloat() * 0.4f + 0.6f,
        symmetry = random.nextInt(4, 17), distortion = random.nextFloat() * 1.3f,
        zoom = random.nextFloat() * 0.9f + 0.75f, hue = random.nextFloat(),
        rotation = random.nextFloat() * 0.7f - 0.35f, morph = random.nextFloat() * 0.7f + 0.15f
    )
    fun resetLook() = defaults(scene).copy(source = source, audioEnabled = audioEnabled, sensitivity = sensitivity,
        beatsPerBar = beatsPerBar, syncOffsetMs = syncOffsetMs, batterySaver = batterySaver, paused = paused)

    companion object {
        fun defaults(scene: Scene) = when (scene) {
            Scene.KALEIDO -> VisualSettings(scene = scene, complexity = 0.8f, symmetry = 12, distortion = 0.55f)
            Scene.WORMHOLE -> VisualSettings(scene = scene, symmetry = 12, distortion = 0.65f, rotation = 0.08f)
            Scene.JULIA -> VisualSettings(scene = scene, complexity = 0.8f, distortion = 0.65f, rotation = 0.025f, morph = 0.15f)
            else -> VisualSettings(scene = scene)
        }
    }
}

/** Each scene keeps its own tuning; shared capture preferences remain independent of saved looks. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("prism", Context.MODE_PRIVATE)
    fun load(): VisualSettings {
        val scene = Scene.entries.find { it.name == prefs.getString("sceneName", null) }
            ?: Scene.entries.find { it.id == prefs.getInt("scene", 0) } ?: Scene.AURORA
        val source = AudioSource.entries.getOrElse(prefs.getInt("source", 0)) { AudioSource.MICROPHONE }
        return loadScene(scene, source)
    }
    fun loadScene(scene: Scene, source: AudioSource) = read(scene, source, "scene.${scene.name}.")
    fun hasSavedLook(scene: Scene) = prefs.getBoolean("look.${scene.name}.saved", false)
    fun saveLook(s: VisualSettings) = write(s, "look.${s.scene.name}.", shared = false)
    fun loadLook(scene: Scene, source: AudioSource) = read(scene, source, "look.${scene.name}.")

    private fun read(scene: Scene, source: AudioSource, prefix: String): VisualSettings {
        val d = VisualSettings.defaults(scene)
        fun f(key: String, fallback: Float, low: Float, high: Float): Float {
            // Preserve v1's palette, motion, and intensity when migrating an existing install.
            val legacy = if (key in listOf("speed", "intensity")) prefs.getFloat(key, fallback) else fallback
            val value = prefs.getFloat(prefix + key, legacy)
            return if (value.isFinite()) value.coerceIn(low, high) else fallback
        }
        return d.copy(
            source = source,
            audioEnabled = prefs.getBoolean("scene.${scene.name}.audioEnabled", false),
            audioAmount = f("audioAmount", d.audioAmount, 0f, 2.5f),
            palette = Palette.entries.getOrElse(prefs.getInt(prefix + "palette", prefs.getInt("palette", d.palette.ordinal))) { d.palette },
            speed = f("speed", d.speed, 0.05f, 2f), intensity = f("intensity", d.intensity, 0.25f, 1.6f),
            sensitivity = prefs.getFloat("sensitivity", 1.4f).coerceIn(0.4f, 4f),
            batterySaver = prefs.getBoolean("batterySaver", false),
            complexity = f("complexity", d.complexity, 0f, 1f),
            symmetry = prefs.getInt(prefix + "symmetry", d.symmetry).coerceIn(3, 20),
            distortion = f("distortion", d.distortion, 0f, 1.5f), zoom = f("zoom", d.zoom, 0.45f, 3f),
            lineWidth = f("lineWidth", d.lineWidth, 0.6f, 3f), hue = f("hue", d.hue, 0f, 1f),
            saturation = f("saturation", d.saturation, 0f, 1.6f), contrast = f("contrast", d.contrast, 0.6f, 1.8f),
            colorSpeed = f("colorSpeed", d.colorSpeed, 0f, 0.2f), rotation = f("rotation", d.rotation, -1f, 1f),
            morph = f("morph", d.morph, 0f, 1.5f), beatImpact = f("beatImpact", d.beatImpact, 0f, 2f),
            barImpact = f("barImpact", d.barImpact, 0f, 2f), flowImpact = f("flowImpact", d.flowImpact, 0f, 2f),
            pulseLength = f("pulseLength", d.pulseLength, 0.08f, 0.8f), textureAmount = f("textureAmount", d.textureAmount, 0f, 1f),
            downbeatFlash = f("downbeatFlash", d.downbeatFlash, 0f, 1f),
            downbeatInvert = f("downbeatInvert", d.downbeatInvert, 0f, 1f),
            invertFadeMs = f("invertFadeMs", d.invertFadeMs, 60f, 400f),
            beatsPerBar = prefs.getInt("beatsPerBar", 0).takeIf { it in listOf(0,3,4) } ?: 0,
            syncOffsetMs = prefs.getFloat("syncOffsetMs", 0f).let { if (it.isFinite()) it.coerceIn(-250f,250f) else 0f }
        )
    }
    fun save(s: VisualSettings) = write(s, "scene.${s.scene.name}.", shared = true)
    private fun write(s: VisualSettings, prefix: String, shared: Boolean) {
        val editor = prefs.edit().putBoolean(prefix + "saved", true).putInt(prefix + "palette", s.palette.ordinal)
            .putInt(prefix + "symmetry", s.symmetry)
        for ((key, value) in listOf(
            "speed" to s.speed, "intensity" to s.intensity, "complexity" to s.complexity,
            "distortion" to s.distortion, "zoom" to s.zoom, "lineWidth" to s.lineWidth,
            "hue" to s.hue, "saturation" to s.saturation, "contrast" to s.contrast,
            "colorSpeed" to s.colorSpeed, "rotation" to s.rotation, "morph" to s.morph,
            "beatImpact" to s.beatImpact, "barImpact" to s.barImpact, "flowImpact" to s.flowImpact,
            "pulseLength" to s.pulseLength, "textureAmount" to s.textureAmount,
            "downbeatFlash" to s.downbeatFlash, "downbeatInvert" to s.downbeatInvert, "invertFadeMs" to s.invertFadeMs,
            "audioAmount" to s.audioAmount
        )) editor.putFloat(prefix + key, value)
        if (shared) editor.putInt("scene", s.scene.id).putString("sceneName", s.scene.name)
            .putBoolean(prefix + "audioEnabled", s.audioEnabled).putInt("source", s.source.ordinal)
            .putFloat("sensitivity", s.sensitivity).putBoolean("batterySaver", s.batterySaver)
            .putInt("beatsPerBar", s.beatsPerBar).putFloat("syncOffsetMs", s.syncOffsetMs)
        editor.apply()
    }
}
