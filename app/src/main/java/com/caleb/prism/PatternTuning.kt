package com.caleb.prism

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Only controls used by the selected renderer belong in its tuning pages. */
@Composable internal fun PatternGeometry(s: VisualSettings, change: (VisualSettings) -> Unit,
                                         active: String?, editing: (String?) -> Unit, otherAlpha: Float) {
    @Composable fun slider(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
                           formatted: String = "%.2f".format(value), steps: Int = 0, set: (Float) -> Unit) =
        OverlaySlider(label, value, range, formatted, steps, active, editing, set)
    @Composable fun toggle(label: String, value: Boolean, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().alpha(otherAlpha), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = ReadableText, modifier = Modifier.weight(1f), fontSize = 13.sp)
            SettingHelp(label)
            Switch(value, set, Modifier.semantics { contentDescription = label })
        }
    }
    when (s.scene) {
        Scene.EQUALIZER -> {
            slider("Frequency bands", s.eqBuckets.toFloat(), 8f..64f, "${s.eqBuckets}", 55) { change(s.copy(eqBuckets = it.roundToInt())) }
            slider("Bar height", s.eqGain, .25f..3f, "%.2f×".format(s.eqGain)) { change(s.copy(eqGain = it)) }
            slider("Bar spacing", s.eqSpacing, .02f.. .65f, "${(s.eqSpacing * 100).roundToInt()}%") { change(s.copy(eqSpacing = it)) }
            toggle("Radial layout", s.eqRadial) { change(s.copy(eqRadial = it)) }
            toggle("Mirror bars", s.eqMirror) { change(s.copy(eqMirror = it)) }
            toggle("LED segments", s.eqSegments) { change(s.copy(eqSegments = it)) }
        }
        Scene.KALEIDOSCOPE -> {
            Row(Modifier.fillMaxWidth().alpha(otherAlpha), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingHelp("Space shape")
                listOf("Chamber", "Gallery", "Tunnel").forEachIndexed { index, label ->
                    FilterChip(selected = s.kaleidoscopeShape == index, onClick = { change(s.copy(kaleidoscopeShape = index)) },
                        label = { Text(label, fontSize = 12.sp) })
                }
            }
            slider("Fractal detail", s.complexity, 0f..1f, "${32 + (s.complexity * 40).toInt()} steps") { change(s.copy(complexity = it)) }
            slider("Open space", s.kaleidoscopeSpace, .4f..2f, "%.2f×".format(s.kaleidoscopeSpace)) { change(s.copy(kaleidoscopeSpace = it)) }
            slider("Fold shape", s.distortion, 0f..1.5f) { change(s.copy(distortion = it)) }
            slider("Camera zoom", s.zoom, .45f..3f, "%.2f×".format(s.zoom)) { change(s.copy(zoom = it)) }
            slider("Filament thickness", s.lineWidth, .6f..3f) { change(s.copy(lineWidth = it)) }
        }
        Scene.AURORA -> {
            slider("Contour density", s.complexity, 0f..1f) { change(s.copy(complexity = it)) }
            slider("Flow harmonics", s.symmetry.toFloat(), 3f..20f, "${s.symmetry}", 16) { change(s.copy(symmetry = it.roundToInt())) }
            slider("Ribbon warp", s.distortion, 0f..1.5f) { change(s.copy(distortion = it)) }
            slider("Field scale", s.zoom, .45f..3f, "%.2f×".format(s.zoom)) { change(s.copy(zoom = it)) }
            slider("Thread width", s.lineWidth, .6f..3f) { change(s.copy(lineWidth = it)) }
        }
        Scene.KALEIDO -> {
            slider("Recursion depth", s.complexity, 0f..1f, "${3 + (s.complexity * 6).toInt()} levels") { change(s.copy(complexity = it)) }
            slider("Mirror segments", s.symmetry.toFloat(), 3f..20f, "${s.symmetry}", 16) { change(s.copy(symmetry = it.roundToInt())) }
            slider("Fold angle", s.distortion, 0f..1.5f) { change(s.copy(distortion = it)) }
            slider("Zoom", s.zoom, .45f..3f, "%.2f×".format(s.zoom)) { change(s.copy(zoom = it)) }
            slider("Edge width", s.lineWidth, .6f..3f) { change(s.copy(lineWidth = it)) }
        }
        Scene.WORMHOLE -> {
            slider("Ring density", s.complexity, 0f..1f) { change(s.copy(complexity = it)) }
            slider("Tunnel facets", s.symmetry.toFloat(), 3f..20f, "${s.symmetry}", 16) { change(s.copy(symmetry = it.roundToInt())) }
            slider("Tunnel twist", s.distortion, 0f..1.5f) { change(s.copy(distortion = it)) }
            slider("Tunnel scale", s.zoom, .45f..3f, "%.2f×".format(s.zoom)) { change(s.copy(zoom = it)) }
            slider("Engraving width", s.lineWidth, .6f..3f) { change(s.copy(lineWidth = it)) }
        }
        Scene.JULIA -> {
            slider("Fractal iterations", s.complexity, 0f..1f, "${40 + (s.complexity * 104).toInt()}") { change(s.copy(complexity = it)) }
            slider("Julia seed", s.distortion, 0f..1.5f) { change(s.copy(distortion = it)) }
            slider("Zoom", s.zoom, .45f..3f, "%.2f×".format(s.zoom)) { change(s.copy(zoom = it)) }
            slider("Contour width", s.lineWidth, .6f..3f) { change(s.copy(lineWidth = it)) }
        }
        Scene.MANDELBROT -> {
            slider("Fractal iterations", s.complexity, 0f..1f, "${80 + (s.complexity * 176).toInt()}") { change(s.copy(complexity = it)) }
            slider("Zoom depth", s.distortion, 0f..1.5f, "${2.0.pow(4 + s.distortion * 3.0).roundToInt()}×") { change(s.copy(distortion = it)) }
            slider("Framing", s.zoom, .45f..3f, "%.2f×".format(s.zoom)) { change(s.copy(zoom = it)) }
            slider("Contour width", s.lineWidth, .6f..3f) { change(s.copy(lineWidth = it)) }
        }
    }
}

@Composable internal fun PatternMotion(s: VisualSettings, change: (VisualSettings) -> Unit,
                                       active: String?, editing: (String?) -> Unit) {
    @Composable fun slider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, formatted: String = "%.2f×".format(value), set: (Float) -> Unit) =
        OverlaySlider(label, value, range, formatted, 0, active, editing, set)
    if (s.scene == Scene.EQUALIZER) {
        slider("Falloff", s.eqFalloff, .08f..1.2f, "%.2f s".format(s.eqFalloff)) { change(s.copy(eqFalloff = it)) }
        slider("Peak hold", s.eqPeakHold, 0f..2f, if (s.eqPeakHold == 0f) "Off" else "%.1f s".format(s.eqPeakHold)) { change(s.copy(eqPeakHold = it)) }
    } else if (s.scene != Scene.JULIA) {
        val label = when (s.scene) {
            Scene.AURORA -> "Weave drift"; Scene.KALEIDO -> "Ring drift"; Scene.MANDELBROT -> "Zoom speed"
            Scene.KALEIDOSCOPE -> "Camera drift"; else -> "Travel speed"
        }
        slider(label, s.speed, .05f..2f) { change(s.copy(speed = it)) }
    }
    if (s.scene != Scene.EQUALIZER || s.eqRadial)
        slider(if (s.scene == Scene.EQUALIZER) "Radial spin" else "Rotation", s.rotation, -1f..1f,
            if (abs(s.rotation) < .015f) "Still" else "%.2f".format(s.rotation)) { change(s.copy(rotation = if (abs(it) < .015f) 0f else it)) }
    val evolution = when (s.scene) {
        Scene.AURORA -> "Ribbon evolution"; Scene.KALEIDO -> "Fold evolution"; Scene.WORMHOLE -> "Twist evolution"
        Scene.JULIA -> "Seed evolution"; Scene.KALEIDOSCOPE -> "Fractal evolution"; else -> null
    }
    if (evolution != null) slider(evolution, s.morph, 0f..1.5f) { change(s.copy(morph = it)) }
}

@Composable internal fun PatternBeat(s: VisualSettings, change: (VisualSettings) -> Unit,
                                     active: String?, editing: (String?) -> Unit, otherAlpha: Float) {
    @Composable fun slider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, formatted: String = "${(value * 100).roundToInt()}%", set: (Float) -> Unit) =
        OverlaySlider(label, value, range, formatted, 0, active, editing, set)
    Row(Modifier.fillMaxWidth().alpha(otherAlpha), verticalAlignment = Alignment.CenterVertically) {
        Text("React to audio", style = ReadableText, fontSize = 13.sp, modifier = Modifier.weight(1f))
        SettingHelp("React to audio")
        Switch(s.audioEnabled, { change(s.copy(audioEnabled = it)) }, Modifier.semantics { contentDescription = "React to audio" })
    }
    slider(if (s.scene == Scene.KALEIDOSCOPE) "Audio-driven folds" else "Reaction strength", s.audioAmount, 0f..2.5f) { change(s.copy(audioAmount = it)) }
    if (s.scene != Scene.EQUALIZER) {
        val beatLabel = when (s.scene) { Scene.WORMHOLE -> "Beat travel"; Scene.JULIA -> "Beat deformation"; Scene.MANDELBROT -> "Beat zoom"; else -> "Beat expansion" }
        val barLabel = when (s.scene) { Scene.AURORA -> "Bar bend"; Scene.JULIA -> "Bar deformation"; Scene.MANDELBROT -> "Bar rotation"; Scene.KALEIDOSCOPE -> "Bar fold"; else -> "Bar twist" }
        slider(beatLabel, s.beatImpact, 0f..2f) { change(s.copy(beatImpact = it)) }
        slider(barLabel, s.barImpact, 0f..2f) { change(s.copy(barImpact = it)) }
        slider("Pulse length", s.pulseLength, .08f.. .8f, "%.2f beats".format(s.pulseLength)) { change(s.copy(pulseLength = it)) }
        slider(if (s.scene == Scene.MANDELBROT) "Palette breathing" else "Shape breathing", s.flowImpact, 0f..2f) { change(s.copy(flowImpact = it)) }
        slider(if (s.scene in listOf(Scene.MANDELBROT, Scene.KALEIDOSCOPE)) "Treble color" else "Treble detail", s.textureAmount, 0f..1f) { change(s.copy(textureAmount = it)) }
    }
    slider("Downbeat flash", s.downbeatFlash, 0f..1f) { change(s.copy(downbeatFlash = it)) }
    slider("Color inversion", s.downbeatInvert, 0f..1f) { change(s.copy(downbeatInvert = it)) }
    if (s.downbeatInvert > 0f) slider("Invert fade", s.invertFadeMs, 60f..400f, "${s.invertFadeMs.roundToInt()} ms") { change(s.copy(invertFadeMs = it)) }
}
