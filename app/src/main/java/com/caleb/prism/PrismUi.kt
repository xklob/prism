package com.caleb.prism

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.*

private val Ink = Color(0xFF080A16)
private val Panel = Color(0xFF101320)
private val Muted = Color(0xFFA7A8BD)
private val Lilac = Color(0xFFCBB6FF)
private val Mint = Color(0xFF82E8CF)

@Composable
fun PrismApp(
    settings: VisualSettings, renderError: String?, connecting: Boolean,
    createSurface: () -> PrismSurface, onChange: (VisualSettings) -> Unit,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onImmersive: (Boolean) -> Unit,
    recording: SessionRecorder, onRecord: (AudioSource) -> Unit,
    onOpenRecording: (SavedRecording) -> Unit, onShareRecording: (SavedRecording) -> Unit
) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var tuning by rememberSaveable { mutableStateOf(false) }
    var surface by remember { mutableStateOf<PrismSurface?>(null) }
    val status by AudioEngine.status.collectAsStateWithLifecycle()
    val recordingState by recording.state.collectAsStateWithLifecycle()
    LaunchedEffect(fullscreen) { onImmersive(fullscreen) }
    BackHandler(fullscreen || tuning) { if (tuning) tuning = false else fullscreen = false }
    MaterialTheme(colorScheme = darkColorScheme(primary = Lilac, secondary = Mint, background = Ink, surface = Panel, onSurface = Color.White)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
            val landscape = maxWidth > maxHeight
            AndroidView(
                factory = { createSurface().also { surface = it } },
                update = { it.update(settings) },
                modifier = Modifier.fillMaxSize()
                    .semantics { contentDescription = "${settings.scene.title} visualizer. Drag to move the scene." }
                    .pointerInput(fullscreen) { detectTapGestures { if (fullscreen) fullscreen = false } }
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { surface?.engine?.touchX = 0f; surface?.engine?.touchY = 0f }) { change, _ ->
                            change.consume()
                            surface?.engine?.touchX = change.position.x / size.width * 2 - 1
                            surface?.engine?.touchY = -(change.position.y / size.height * 2 - 1)
                        }
                    }
            )
            AnimatedVisibility(!fullscreen && !tuning, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(210.dp).background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.9f), Color.Transparent))))
                    Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 18.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PrismMark(Modifier.size(30.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("PRISM", color = Color.White, fontSize = 19.sp, letterSpacing = 5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            StatusPill(if (settings.paused) "PAUSED" else if (!settings.audioEnabled) "AMBIENT" else if (status.running) "AUDIO ON" else "AUDIO READY", if (status.running) Mint else Lilac)
                        }
                        if (!landscape && !settings.audioEnabled) {
                            Spacer(Modifier.height(if (recordingState.busy) 82.dp else 34.dp))
                            Text(settings.scene.title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
                            Spacer(Modifier.height(5.dp))
                            Text(settings.scene.subtitle, color = Color(0xFFCDD0E0), fontSize = 13.sp)
                        }
                    }
                    val panelModifier = if (landscape) Modifier.align(Alignment.CenterEnd).width(340.dp).fillMaxHeight().safeDrawingPadding().padding(top = 65.dp)
                        else Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    Column(panelModifier.background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.95f), Ink)))) {
                        Spacer(Modifier.height(if (landscape) 8.dp else 42.dp))
                        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                            if (settings.audioEnabled) {
                                AudioPanel(settings.source, status, connecting, { onChange(settings.copy(source = it)) }, onConnect, onDisconnect, recordingState.busy)
                                Spacer(Modifier.height(20.dp))
                            }
                            Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("PATTERNS", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                Text("React to audio", color = Color.White, fontSize = 12.sp)
                                Spacer(Modifier.width(10.dp))
                                Switch(settings.audioEnabled, { onChange(settings.copy(audioEnabled = it)) },
                                    Modifier.semantics { contentDescription = "React to audio" })
                            }
                            Spacer(Modifier.height(12.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(Scene.entries) { scene ->
                                    SceneCard(scene, scene == settings.scene) { onChange(settings.copy(scene = scene, paused = false)) }
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                SmallAction(if (settings.paused) "Play" else "Pause", if (settings.paused) "play" else "pause") { onChange(settings.copy(paused = !settings.paused)) }
                                OutlinedButton(onClick = { tuning = true }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), contentPadding = PaddingValues(horizontal = 14.dp)) {
                                    Glyph("tune", Modifier.size(18.dp), Color.White)
                                    Spacer(Modifier.width(9.dp))
                                    Text("Tune", color = Color.White, fontSize = 13.sp)
                                }
                                OutlinedButton(onClick = { fullscreen = true }, modifier = Modifier.weight(1.3f).height(48.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Lilac.copy(alpha = 0.7f)), contentPadding = PaddingValues(horizontal = 14.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = Lilac.copy(alpha = 0.2f), contentColor = Color.White)) {
                                    Glyph("expand", Modifier.size(17.dp), Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Immerse", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            RecordingLauncher(recordingState, settings.source, onRecord, onOpenRecording, onShareRecording)
                            if (!landscape) {
                                Text("DRAG TO BEND THE LIGHT", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 9.sp, letterSpacing = 2.sp, color = Muted.copy(alpha = 0.65f))
                            }
                        }
                    }
                }
            }
            if (fullscreen) {
                var hintVisible by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { delay(2400); hintVisible = false }
                if (hintVisible) Text("Tap anywhere to return", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).clip(CircleShape).background(Ink.copy(alpha = 0.7f)).padding(horizontal = 18.dp, vertical = 10.dp), color = Color.White, fontSize = 12.sp)
            }
            if (renderError != null) Text(renderError, Modifier.align(Alignment.Center).padding(30.dp).background(Panel).padding(20.dp), color = Color.White)
            if (tuning) TuningOverlay(
                settings, status, connecting, onChange, onConnect, onDisconnect,
                fps = { surface?.engine?.measuredFps ?: 0f }, dismiss = { tuning = false }, captureLocked = recordingState.busy
            )
            if (recordingState.busy) RecordingBadge(recordingState, { recording.stop() },
                Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(start = 20.dp, top = if (fullscreen) 8.dp else 70.dp))
            if (!recordingState.busy && recordingState.message != null) {
                Snackbar(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 68.dp),
                    action = { TextButton(onClick = recording::dismissMessage) { Text("Dismiss") } }) {
                    Text(recordingState.message!!)
                }
                LaunchedEffect(recordingState.message) { delay(6000); recording.dismissMessage() }
            }
        }
    }
}

@Composable private fun StatusPill(text: String, color: Color) {
    Row(Modifier.clip(CircleShape).background(Ink.copy(alpha = 0.55f)).border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(text, color = Color.White, fontSize = 9.sp, letterSpacing = 1.4.sp)
    }
}

@Composable private fun SceneCard(scene: Scene, selected: Boolean, click: () -> Unit) {
    Column(Modifier.width(112.dp).height(118.dp).clip(RoundedCornerShape(18.dp))
        .background(Brush.verticalGradient(listOf(scene.accent.copy(alpha = if (selected) 0.23f else 0.10f), Panel)))
        .border(1.dp, if (selected) scene.accent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.09f), RoundedCornerShape(18.dp))
        .selectable(selected, onClick = click, role = Role.RadioButton).padding(12.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { SceneMotif(scene, Modifier.fillMaxSize().padding(2.dp)) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(scene.title, fontSize = 12.sp, color = if (selected) Color.White else Color(0xFFC7C8DA), modifier = Modifier.weight(1f))
            if (selected) Box(Modifier.size(5.dp).background(scene.accent, CircleShape))
        }
    }
}

@Composable private fun SceneMotif(scene: Scene, modifier: Modifier) {
    Canvas(modifier) {
        val c = scene.accent
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) * 0.46f
        when (scene) {
            Scene.AURORA -> for (line in 0..6) {
                val path = Path()
                for (x in 0..50) {
                    val fx = x / 50f
                    val y = size.height * (0.18f + line * 0.1f) + sin(fx * 7f + line * 0.7f) * size.height * 0.12f
                    if (x == 0) path.moveTo(fx * size.width, y) else path.lineTo(fx * size.width, y)
                }
                drawPath(path, c.copy(alpha = 0.3f + line / 10f), style = Stroke(1.4.dp.toPx()))
            }
            Scene.WORMHOLE -> for (i in 1..6) drawCircle(c.copy(alpha = i / 7f), radius * i / 6f, center + Offset((6-i) * 1.8f, 0f), style = Stroke(1.2.dp.toPx()))
            else -> for (ring in 1..3) {
                val path = Path()
                for (i in 0..180) {
                    val a = i * 2 * PI / 180
                    val r = radius * ring / 3f * (0.76 + 0.24 * cos(a * 6))
                    val x = center.x + cos(a).toFloat() * r.toFloat()
                    val y = center.y + sin(a).toFloat() * r.toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, c.copy(alpha = 0.25f + ring * 0.22f), style = Stroke(1.3.dp.toPx()))
            }
        }
    }
}

@Composable private fun AudioPanel(source: AudioSource, status: CaptureStatus, connecting: Boolean, onSource: (AudioSource) -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit, captureLocked: Boolean) {
    var energy by remember { mutableFloatStateOf(0f) }
    var rhythm by remember { mutableStateOf(RhythmState()) }
    var timingNow by remember { mutableDoubleStateOf(0.0) }
    var silentSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(status.running) {
        var quietTicks = 0
        while (status.running) {
            energy = AudioEngine.levels.energy
            rhythm = AudioEngine.rhythm
            timingNow = System.nanoTime()/1e9
            quietTicks = if (energy < 0.015f) quietTicks + 1 else 0
            silentSeconds = quietTicks / 10
            delay(100)
        }
        energy = 0f; silentSeconds = 0; rhythm = RhythmState()
    }
    Column(Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(18.dp)).background(Panel.copy(alpha = 0.9f)).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp)).padding(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AudioSource.entries.forEach { item ->
                Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (source == item) Lilac.copy(alpha = 0.16f) else Color.Transparent)
                    .selectable(source == item, enabled = !captureLocked, onClick = { onSource(item) }, role = Role.RadioButton).padding(horizontal = 9.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Glyph(if (item == AudioSource.MICROPHONE) "mic" else "audio", Modifier.size(14.dp), if (source == item) Lilac else Muted)
                    Spacer(Modifier.width(7.dp))
                    Text(item.label, color = if (source == item) Lilac else Muted, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (source == AudioSource.SYSTEM) "Phone playback" else "Microphone input", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = if (status.running) onDisconnect else onConnect, enabled = !connecting && !captureLocked, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text(if (captureLocked) "Recording audio" else if (connecting) "Connecting…" else if (status.running) "Stop" else "Connect audio", fontSize = 11.sp)
            }
        }
        RhythmMonitor(status.running, rhythm, timingNow, energy, details = false)
        val feedback = RhythmFeedback.from(status.running, rhythm, timingNow)
        val helper = status.message ?: if (status.running && rhythm.signalPresent && !feedback.barReady) feedback.help else if (source == AudioSource.SYSTEM) {
            if (status.running && silentSeconds >= 5) "Play music in another app. If it stays quiet, that app may block capture; try the microphone."
            else "Follows phone playback, even with headphones. Some apps block capture."
        } else "Reacts to sound around you. Audio stays on your device."
        Text(helper, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable private fun SmallAction(label: String, glyph: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f)).border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
        .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) { Glyph(glyph, Modifier.size(18.dp), Color.White) }
}

@Composable private fun PrismMark(modifier: Modifier) {
    Canvas(modifier) {
        for (i in 0..2) {
            val inset = i * size.width * 0.17f
            val path = Path().apply { moveTo(size.width / 2, inset); lineTo(size.width - inset * 0.85f, size.height - inset * 0.6f); lineTo(inset * 0.85f, size.height - inset * 0.6f); close() }
            drawPath(path, listOf(Lilac, Color(0xFFFF8FCF), Mint)[i], style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable private fun Glyph(name: String, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = 1.6.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1*w, y1*h), Offset(x2*w, y2*h), stroke, StrokeCap.Round)
        when (name) {
            "pause" -> { line(.32f,.17f,.32f,.83f); line(.68f,.17f,.68f,.83f) }
            "play" -> drawPath(Path().apply { moveTo(.25f*w,.12f*h); lineTo(.83f*w,.5f*h); lineTo(.25f*w,.88f*h); close() }, color)
            "expand" -> { line(.05f,.35f,.05f,.05f); line(.05f,.05f,.35f,.05f); line(.65f,.05f,.95f,.05f); line(.95f,.05f,.95f,.35f); line(.05f,.65f,.05f,.95f); line(.05f,.95f,.35f,.95f); line(.65f,.95f,.95f,.95f); line(.95f,.95f,.95f,.65f) }
            "tune" -> { for (i in 0..2) { val x = .2f + i*.3f; val y = if (i == 1) .65f else .35f; line(x,.05f,x,.95f); drawCircle(color, w*.105f, Offset(x*w,y*h)) } }
            "audio" -> { for (i in 0..4) { val x = .1f + i*.2f; val half = if(i==2) .45f else if(i%2==1) .27f else .13f; line(x,.5f-half,x,.5f+half) } }
            "mic" -> { drawRoundRect(color, Offset(w*.34f,h*.03f), Size(w*.32f,h*.58f), androidx.compose.ui.geometry.CornerRadius(w*.16f), style=Stroke(stroke)); drawArc(color,0f,180f,false,Offset(w*.16f,h*.23f),Size(w*.68f,h*.53f),style=Stroke(stroke)); line(.5f,.78f,.5f,.98f); line(.3f,.98f,.7f,.98f) }
        }
    }
}
