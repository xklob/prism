package com.caleb.prism

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val OverlayInk = Color(0xFF060914)
private val OverlayAccent = Color(0xFFD9C6FF)
private val ReadableText = TextStyle(color = Color.White, shadow = Shadow(Color.Black, Offset(0f, 2f), 7f))

/** One inset, readable panel over the unchanged full-size visual. */
@Composable
fun TuningOverlay(
    s: VisualSettings, status: CaptureStatus, connecting: Boolean,
    change: (VisualSettings) -> Unit, connect: () -> Unit, disconnect: () -> Unit,
    fps: () -> Float, dismiss: () -> Unit, captureLocked: Boolean = false,
    songAssistant: SongAssistant, initialTab: String = "Geometry", tablet: Boolean = false
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var activeControl by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("") }
    var measuredFps by remember { mutableFloatStateOf(0f) }
    var timing by remember { mutableStateOf(RhythmState()) }
    var timingNow by remember { mutableDoubleStateOf(0.0) }
    var inputEnergy by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val store = remember(context) { SettingsStore(context) }
    var saved by remember(s.scene) { mutableStateOf(store.hasSavedLook(s.scene)) }
    val otherAlpha by animateFloatAsState(if (activeControl == null) 1f else 0f, tween(120), label = "Control visibility")
    LaunchedEffect(notice) { if (notice.isNotEmpty()) { delay(2200); notice = "" } }
    LaunchedEffect(Unit) { while (true) { measuredFps = fps(); delay(1000) } }
    LaunchedEffect(status.running, tab, hidden) {
        if (!status.running) { timing = RhythmState(); inputEnergy = 0f }
        while (status.running && tab == "Audio" && !hidden) {
            timing = AudioEngine.rhythm
            inputEnergy = AudioEngine.levels.energy
            timingNow = System.nanoTime()/1e9
            delay(50)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
        val sidePanel = maxWidth >= 600.dp
        val controlsWidth = if (sidePanel) Modifier.width((maxWidth * .46f).coerceIn(360.dp, 480.dp)) else Modifier.fillMaxWidth()
        val controlsHeight = if (sidePanel || maxHeight < 480.dp) maxHeight else maxHeight * .68f
        if (hidden) {
            TextButton(onClick = { hidden = false }, modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)
                .clip(CircleShape).background(OverlayInk.copy(alpha = 0.4f))) {
                Text("Show controls", color = Color.White, style = ReadableText)
            }
        } else {
            if (tablet && s.audioEnabled) TimingSidebar(s, status.running, change,
                Modifier.align(Alignment.TopStart).padding(top=if (captureLocked) 114.dp else 0.dp)
                    .width(300.dp).heightIn(max=maxHeight*.55f-if (captureLocked) 114.dp else 0.dp).alpha(otherAlpha))
            Column(Modifier.align(if (sidePanel) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(controlsWidth).heightIn(max = controlsHeight)
                .clip(RoundedCornerShape(24.dp))
                .background(OverlayInk.copy(alpha = .82f))
                .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(24.dp))
                .semantics { contentDescription = "Tuning panel" }
                .padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().alpha(otherAlpha), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${s.scene.title} // TUNE", style = ReadableText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(if (notice.isNotEmpty()) notice else if (s.paused) "PAUSED · CHANGES ARE LIVE" else "LIVE · ${measuredFps.roundToInt()} FPS", style = ReadableText, color = OverlayAccent, fontSize = 9.sp, letterSpacing = 1.sp)
                }
                TextButton(onClick = { hidden = true }) { Text("Hide", style = ReadableText, fontSize = 12.sp) }
                TextButton(onClick = dismiss) { Text("Done", style = ReadableText, color = OverlayAccent, fontSize = 12.sp) }
            }
                FlowRow(Modifier.fillMaxWidth().alpha(otherAlpha), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val tabs = listOf("Geometry", "Color", "Motion", "Audio", "Song", "Display")
                    tabs.forEach { name ->
                        TextButton(onClick = { tab = name }, modifier = Modifier.heightIn(min = 48.dp)
                            .semantics { role = Role.Tab; selected = tab == name }
                            .background(if (tab == name) OverlayInk.copy(alpha = 0.58f) else OverlayInk.copy(alpha = 0.18f), CircleShape),
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(name, style = ReadableText, color = if (tab == name) OverlayAccent else Color.White, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                key(tab) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 3.dp)) {
                        fun editing(label: String?) { activeControl = label }
                        @Composable fun slider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, formatted: String, steps: Int = 0, set: (Float) -> Unit) {
                            OverlaySlider(label, value, range, formatted, steps, activeControl, ::editing, set)
                        }
                        when (tab) {
                            "Song" -> SongControls(songAssistant, showManualTiming = !tablet)
                            "Display" -> {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Tablet monitor", style = ReadableText, fontSize = 14.sp)
                                        Text("Song and timing at bottom left, including fullscreen", style = ReadableText, fontSize = 12.sp)
                                    }
                                    Switch(s.tabletMonitor, { change(s.copy(tabletMonitor = it)) },
                                        Modifier.semantics { contentDescription = "Tablet monitor" })
                                }
                                Text("Shown on tablet-sized windows. Hidden from saved videos.", style = ReadableText, fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 12.dp))
                            }
                            "Geometry" -> if (s.scene == Scene.EQUALIZER) {
                                Text("Connect audio in the Audio tab to see live frequency bands.", style = ReadableText, fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 8.dp).alpha(otherAlpha))
                                slider("Frequency bands", s.eqBuckets.toFloat(), 8f..64f, "${s.eqBuckets}", 55) { change(s.copy(eqBuckets = it.roundToInt())) }
                                slider("Bar height", s.eqGain, .25f..3f, "%.2f×".format(s.eqGain)) { change(s.copy(eqGain = it)) }
                                slider("Bar spacing", s.eqSpacing, .02f..0.65f, "${(s.eqSpacing * 100).roundToInt()}%") { change(s.copy(eqSpacing = it)) }
                                slider("Falloff", s.eqFalloff, .08f..1.2f, "%.2f s".format(s.eqFalloff)) { change(s.copy(eqFalloff = it)) }
                                slider("Peak hold", s.eqPeakHold, 0f..2f, if (s.eqPeakHold == 0f) "Off" else "%.1f s".format(s.eqPeakHold)) { change(s.copy(eqPeakHold = it)) }
                                for ((label, checked, set) in listOf(
                                    Triple("Mirror bars", s.eqMirror, { value: Boolean -> change(s.copy(eqMirror = value)) }),
                                    Triple("Radial layout", s.eqRadial, { value: Boolean -> change(s.copy(eqRadial = value)) }),
                                    Triple("LED segments", s.eqSegments, { value: Boolean -> change(s.copy(eqSegments = value)) })
                                )) Row(Modifier.fillMaxWidth().alpha(otherAlpha), verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, style = ReadableText, modifier = Modifier.weight(1f), fontSize = 13.sp)
                                    Switch(checked, set, Modifier.semantics { contentDescription = label })
                                }
                            } else {
                                val detail = when (s.scene) {
                                    Scene.KALEIDO -> "${3 + (s.complexity * 6).toInt()} levels"
                                    Scene.JULIA -> "${40 + (s.complexity * 104).toInt()} iterations"
                                    Scene.MANDELBROT -> "${80 + (s.complexity * 176).toInt()} iterations"
                                    else -> "${(s.complexity * 100).roundToInt()}%"
                                }
                                slider(s.scene.detailLabel, s.complexity, 0f..1f, detail) { change(s.copy(complexity = it)) }
                                if (s.scene != Scene.JULIA && s.scene != Scene.MANDELBROT) slider(s.scene.symmetryLabel, s.symmetry.toFloat(), 3f..20f, "${s.symmetry}", 16) { change(s.copy(symmetry = it.roundToInt())) }
                                slider(s.scene.distortionLabel, s.distortion, 0f..1.5f, "%.2f".format(s.distortion)) { change(s.copy(distortion = it)) }
                                slider("Zoom", s.zoom, 0.45f..3f, "%.2f×".format(s.zoom)) { change(s.copy(zoom = it)) }
                                slider("Line weight", s.lineWidth, 0.6f..3f, "%.1f".format(s.lineWidth)) { change(s.copy(lineWidth = it)) }
                            }
                            "Color" -> {
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).alpha(otherAlpha), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Palette.entries.forEach { palette ->
                                        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                            .selectable(s.palette == palette, onClick = { change(s.copy(palette = palette)) }, role = Role.RadioButton)
                                            .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Box(Modifier.fillMaxWidth().height(16.dp).clip(CircleShape).background(Brush.horizontalGradient(palette.colors)))
                                            Text(palette.title, fontSize = 10.sp, style = ReadableText, color = if (s.palette == palette) OverlayAccent else Color.White, modifier = Modifier.padding(top = 6.dp))
                                        }
                                    }
                                }
                                slider("Hue shift", s.hue, 0f..1f, "${(s.hue * 360).roundToInt()}°") { change(s.copy(hue = it)) }
                                slider("Saturation", s.saturation, 0f..1.6f, "${(s.saturation * 100).roundToInt()}%") { change(s.copy(saturation = it)) }
                                slider("Brightness", s.intensity, 0.25f..1.6f, "${(s.intensity * 100).roundToInt()}%") { change(s.copy(intensity = it)) }
                                slider("Contrast", s.contrast, 0.6f..1.8f, "%.2f×".format(s.contrast)) { change(s.copy(contrast = it)) }
                            }
                            "Motion" -> {
                                if (s.scene != Scene.EQUALIZER) slider(if (s.scene == Scene.MANDELBROT) "Zoom speed" else "Travel speed", s.speed, 0.05f..2f, "%.2f×".format(s.speed)) { change(s.copy(speed = it)) }
                                if (s.scene != Scene.EQUALIZER || s.eqRadial) slider("Rotation", s.rotation, -1f..1f, if (kotlin.math.abs(s.rotation) < 0.015f) "Still" else "%.2f".format(s.rotation)) { change(s.copy(rotation = if (kotlin.math.abs(it) < 0.015f) 0f else it)) }
                                if (s.scene != Scene.MANDELBROT && s.scene != Scene.EQUALIZER) slider("Shape evolution", s.morph, 0f..1.5f, "%.2f×".format(s.morph)) { change(s.copy(morph = it)) }
                                slider("Color cycling", s.colorSpeed, 0f..0.2f, if (s.colorSpeed == 0f) "Still" else "%.2f×".format(s.colorSpeed * 10)) { change(s.copy(colorSpeed = it)) }
                                Row(Modifier.fillMaxWidth().alpha(otherAlpha), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Battery saver", style = ReadableText, fontSize = 13.sp)
                                        Text("Lower resolution · 30 fps", style = ReadableText, fontSize = 10.sp)
                                    }
                                    Switch(s.batterySaver, { change(s.copy(batterySaver = it)) }, Modifier.semantics { contentDescription = "Battery saver" })
                                }
                            }
                            "Audio" -> {
                                Row(Modifier.fillMaxWidth().alpha(otherAlpha), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("React to audio", style = ReadableText, fontSize = 13.sp)
                                        Text("Remembered for ${s.scene.title}", style = ReadableText, fontSize = 10.sp)
                                    }
                                    Switch(s.audioEnabled, { change(s.copy(audioEnabled = it)) }, Modifier.semantics { contentDescription = "React to audio" })
                                }
                                Row(Modifier.fillMaxWidth().alpha(otherAlpha), verticalAlignment = Alignment.CenterVertically) {
                                    AudioSource.entries.forEach { source ->
                                        TextButton(onClick = { change(s.copy(source = source)) }, enabled = !captureLocked, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                                            Text(source.label, style = ReadableText, fontSize = 11.sp, color = if (source == s.source) OverlayAccent else Color.White)
                                        }
                                    }
                                    TextButton(onClick = if (status.running) disconnect else connect, enabled = s.audioEnabled && !connecting && !captureLocked, contentPadding = PaddingValues(4.dp)) {
                                        Text(if (connecting) "Wait…" else if (status.running) "Stop" else "Connect", style = ReadableText, fontSize = 11.sp)
                                    }
                                }
                                Column(Modifier.alpha(otherAlpha)) {
                                    RhythmMonitor(status.running, timing, timingNow, inputEnergy)
                                    if (!tablet) TimingControls(s, status.running, timing, change)
                                }
                                if (status.message != null) Text(status.message, style = ReadableText, fontSize = 10.sp, modifier = Modifier.alpha(otherAlpha))
                                slider("Reaction strength", s.audioAmount, 0f..2.5f, "${(s.audioAmount * 100).roundToInt()}%") { change(s.copy(audioAmount = it)) }
                                if (s.scene != Scene.EQUALIZER) {
                                    slider("Beat pulse", s.beatImpact, 0f..2f, "${(s.beatImpact * 100).roundToInt()}%") { change(s.copy(beatImpact = it)) }
                                    slider("Bar accent", s.barImpact, 0f..2f, "${(s.barImpact * 100).roundToInt()}%") { change(s.copy(barImpact = it)) }
                                }
                                slider("Downbeat flash", s.downbeatFlash, 0f..1f, "${(s.downbeatFlash * 100).roundToInt()}%") { change(s.copy(downbeatFlash = it)) }
                                slider("Color inversion", s.downbeatInvert, 0f..1f, "${(s.downbeatInvert * 100).roundToInt()}%") { change(s.copy(downbeatInvert = it)) }
                                slider("Invert fade", s.invertFadeMs, 60f..400f, "${s.invertFadeMs.roundToInt()} ms") { change(s.copy(invertFadeMs = it)) }
                                if (s.scene != Scene.EQUALIZER) {
                                    slider("Musical motion", s.flowImpact, 0f..2f, "${(s.flowImpact * 100).roundToInt()}%") { change(s.copy(flowImpact = it)) }
                                    slider("Pulse length", s.pulseLength, 0.08f..0.8f, "%.2f beats".format(s.pulseLength)) { change(s.copy(pulseLength = it)) }
                                }
                                slider("Sync offset", s.syncOffsetMs, -250f..250f, "%+.0f ms".format(s.syncOffsetMs)) { change(s.copy(syncOffsetMs = it)) }
                                slider("Input gain", s.sensitivity, 0.4f..4f, "%.1f×".format(s.sensitivity)) { change(s.copy(sensitivity = it)) }
                                if (s.scene != Scene.EQUALIZER) slider("Extra audio texture", s.textureAmount, 0f..1f, "${(s.textureAmount * 100).roundToInt()}%") { change(s.copy(textureAmount = it)) }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().alpha(otherAlpha), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { change(s.randomLook()); notice = "New variation" }) { Text("Shuffle", style = ReadableText, fontSize = 11.sp) }
                    TextButton(onClick = { store.saveLook(s); saved = true; notice = "Look saved for ${s.scene.title}" }) { Text("Save", style = ReadableText, fontSize = 11.sp) }
                    TextButton(onClick = { change(store.loadLook(s.scene, s.source).copy(paused = s.paused)); notice = "Saved look restored" }, enabled = saved) { Text("Recall", style = ReadableText, fontSize = 11.sp, color = if (saved) Color.White else Color.LightGray.copy(alpha = 0.6f)) }
                    TextButton(onClick = { change(s.resetLook()); notice = "Scene defaults restored" }) { Text("Reset", style = ReadableText, fontSize = 11.sp) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun OverlaySlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, formatted: String,
    steps: Int, active: String?, editing: (String?) -> Unit, change: (Float) -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    val dragging by interactions.collectIsDraggedAsState()
    val alpha by animateFloatAsState(if (active == null || active == label) 1f else 0f, tween(120), label = "Slider visibility")
    LaunchedEffect(dragging) {
        if (dragging) editing(label) else if (active == label) editing(null)
    }
    Column(Modifier.fillMaxWidth().alpha(alpha).padding(top = 4.dp, bottom = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = ReadableText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(formatted, style = ReadableText, color = OverlayAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(value, onValueChange = change, onValueChangeFinished = { editing(null) }, valueRange = range, steps = steps,
            interactionSource = interactions, modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = label },
            thumb = { Box(Modifier.size(14.dp).background(Color.White, CircleShape).border(1.dp, OverlayInk.copy(alpha = 0.5f), CircleShape)) },
            track = {
                Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    val y = size.height / 2
                    drawLine(Color.Black.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), 5.dp.toPx(), StrokeCap.Round)
                    drawLine(Color.White.copy(alpha = 0.32f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(OverlayAccent, Offset(0f, y), Offset(size.width * fraction, y), 2.dp.toPx(), StrokeCap.Round)
                }
            },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = OverlayAccent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
    }
}
