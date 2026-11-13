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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
    onConnect: () -> Unit, onDisconnect: () -> Unit, onChooseFile: () -> Unit, onImmersive: (Boolean) -> Unit,
    recording: SessionRecorder, onRecord: () -> Unit,
    onOpenRecording: (SavedRecording) -> Unit, onShareRecording: (SavedRecording) -> Unit,
    songAssistant: SongAssistant, looks: LookLibrary, crowd: CrowdClient, crowdInvitation: String,
    onCrowdJoin: (String, Boolean) -> Unit, onCrowdLeave: () -> Unit, onCrowdBrightness: (Float) -> Unit
) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var tuning by rememberSaveable { mutableStateOf(false) }
    var crowdOpen by rememberSaveable { mutableStateOf(false) }
    var tuningTab by rememberSaveable { mutableStateOf("Geometry") }
    var appSettings by rememberSaveable { mutableStateOf(false) }
    val controlsScroll = rememberScrollState()
    val sceneScroll = rememberLazyListState(initialFirstVisibleItemIndex = Scene.entries.indexOf(settings.scene))
    LaunchedEffect(settings.scene) { sceneScroll.animateScrollToItem(Scene.entries.indexOf(settings.scene)) }
    val density = LocalDensity.current
    var topControlsHeight by remember { mutableStateOf(100.dp) }
    var settingsMinimized by rememberSaveable { mutableStateOf(false) }
    var timingMinimized by rememberSaveable { mutableStateOf(false) }
    var surface by remember { mutableStateOf<PrismSurface?>(null) }
    var measuredFps by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { measuredFps = surface?.engine?.measuredFps ?: 0f; delay(500) } }
    val status by AudioEngine.status.collectAsStateWithLifecycle()
    val recordingState by recording.state.collectAsStateWithLifecycle()
    val crowdStatus by crowd.status.collectAsStateWithLifecycle()
    val songState by songAssistant.state.collectAsStateWithLifecycle()
    val fileState by LocalAudio.state.collectAsStateWithLifecycle()
    var waveformHeight by remember { mutableStateOf(120.dp) }
    LaunchedEffect(fullscreen) { onImmersive(fullscreen) }
    LaunchedEffect(crowdInvitation) { if (crowdInvitation.isNotBlank()) { crowdOpen = true; fullscreen = false; tuning = false } }
    BackHandler(fullscreen || tuning || crowdOpen) {
        if (crowdOpen) crowdOpen = false else if (tuning) tuning = false else fullscreen = false
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Lilac, secondary = Mint, background = Ink, surface = Panel, onSurface = Color.White)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
            val landscape = maxWidth > maxHeight
            val tablet = minOf(maxWidth, maxHeight) >= 600.dp && maxWidth >= 760.dp
            val compact = landscape && !tablet
            val showWaveform = settings.source == AudioSource.LOCAL && fileState.ready &&
                (fileState.waveform ?: tablet) && !crowdOpen && !crowdStatus.connected
            val bottomInset = if (showWaveform) waveformHeight + 12.dp else 0.dp
            val showPlayback = !crowdOpen && !crowdStatus.connected
            val hudSlotWidth = ((maxWidth - 48.dp) / 2).coerceAtMost(260.dp)
            AndroidView(
                factory = { createSurface().also { surface = it } },
                update = { it.update(settings); it.updateCrowd(crowd) },
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
            AnimatedVisibility(!fullscreen && !tuning && !crowdOpen, enter = fadeIn(), exit = fadeOut()) {
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = bottomInset).padding(16.dp)
                    .padding(top = topControlsHeight + 12.dp)) {
                    val sidePanel = landscape || tablet
                    val panelWidth = if (sidePanel) Modifier.width(if (tablet) 420.dp else 340.dp) else Modifier.fillMaxWidth()
                    val panelHeight = if (sidePanel) maxHeight else minOf(maxHeight, (maxHeight + topControlsHeight + 12.dp) * .68f)
                    if (!tablet || !settingsMinimized || crowdStatus.connected) Column(
                        Modifier.align(if (sidePanel) Alignment.BottomEnd else Alignment.BottomCenter)
                            .then(panelWidth).heightIn(max = panelHeight)
                            .clip(RoundedCornerShape(24.dp)).background(Ink.copy(alpha = .92f))
                            .border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(24.dp))
                            .semantics { contentDescription = "Pattern controls panel" }.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (!compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("prism", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text("//", color = Muted, fontSize = 11.sp)
                                    Text(if (crowdStatus.connected) "CROWD" else if (settings.paused) "PAUSED" else if (!settings.audioEnabled) "AMBIENT" else if (status.running) "AUDIO ON" else "AUDIO READY",
                                        color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(if (crowdStatus.connected) crowdStatus.phase else settings.scene.title,
                                    color = Color.White, fontSize = if (landscape && !tablet) 16.sp else 20.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (tablet && !crowdStatus.connected) TextButton(onClick = { settingsMinimized = true },
                                modifier = Modifier.semantics { contentDescription = "Minimize settings" }) {
                                Text("Minimize ›", color = Color.White, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Column(Modifier.weight(1f, fill = false).verticalScroll(controlsScroll)
                            .semantics { contentDescription = "Pattern controls content" }) {
                            if (!crowdStatus.connected) {
                                if (!landscape) Text(settings.scene.subtitle, color = Color(0xFFCDD0E0), fontSize = 12.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val selectedIndex = Scene.entries.indexOf(settings.scene)
                                if (!compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("PATTERNS", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                                    Text("${selectedIndex + 1} / ${Scene.entries.size}", color = Muted, fontSize = 11.sp)
                                    IconButton(onClick = { onChange(settings.copy(scene = Scene.entries[selectedIndex - 1], paused = false)) },
                                        enabled = selectedIndex > 0, modifier = Modifier.semantics { contentDescription = "Previous pattern" }) {
                                        Text("‹", color = if (selectedIndex > 0) Color.White else Muted.copy(alpha = .4f), fontSize = 25.sp)
                                    }
                                    IconButton(onClick = { onChange(settings.copy(scene = Scene.entries[selectedIndex + 1], paused = false)) },
                                        enabled = selectedIndex < Scene.entries.lastIndex, modifier = Modifier.semantics { contentDescription = "Next pattern" }) {
                                        Text("›", color = if (selectedIndex < Scene.entries.lastIndex) Color.White else Muted.copy(alpha = .4f), fontSize = 25.sp)
                                    }
                                }
                                LazyRow(state = sceneScroll, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(Scene.entries, key = { it.id }) { scene ->
                                        SceneCard(scene, scene == settings.scene, compact) { onChange(settings.copy(scene = scene, paused = false)) }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("React to audio", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    SettingHelp("React to audio")
                                    Switch(settings.audioEnabled, { onChange(settings.copy(audioEnabled = it)) },
                                        Modifier.semantics { contentDescription = "React to audio" })
                                }
                            } else Text(crowdStatus.message, color = Color.White, fontSize = 13.sp)
                            if (settings.audioEnabled && (!crowdStatus.connected || crowdStatus.source)) {
                                AudioPanel(settings.source, status, connecting, { onChange(settings.copy(source = it)) }, onConnect, onDisconnect, recordingState.busy)
                                if (settings.source != AudioSource.LOCAL) SongMonitor(songAssistant) { appSettings = true; tuningTab = "Song"; tuning = true }
                            }
                            if (!crowdStatus.connected) RecordingLibrary(recordingState, onOpenRecording, onShareRecording)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = .12f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!crowdStatus.connected) SmallAction(if (settings.paused) "Play" else "Pause", if (settings.paused) "play" else "pause") { onChange(settings.copy(paused = !settings.paused)) }
                            OutlinedButton(onClick = { if (crowdStatus.connected) crowdOpen = true else { appSettings = false; tuningTab = settings.scene.shapeTab; tuning = true } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .18f)), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Glyph("tune", Modifier.size(18.dp), Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text(if (crowdStatus.connected) "Crowd" else "Tune", color = Color.White, fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = { fullscreen = true }, modifier = Modifier.weight(1.2f).heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Lilac.copy(alpha = .7f)), contentPadding = PaddingValues(horizontal = 8.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = Lilac.copy(alpha = .2f), contentColor = Color.White)) {
                                Glyph("expand", Modifier.size(17.dp), Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text("Immerse", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (compact && !crowdStatus.connected) {
                                var more by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { more = true }, modifier = Modifier.semantics { contentDescription = "More app controls" }) { Text("⋮", color = Color.White, fontSize = 22.sp) }
                                    DropdownMenu(more, { more = false }) {
                                        DropdownMenuItem({ Text("App settings") }, { more = false; appSettings = true; tuningTab = "Audio"; tuning = true })
                                        DropdownMenuItem({ Text("Join crowd show") }, { more = false; crowdOpen = true })
                                    }
                                }
                            }
                        }
                        if (!compact && !crowdStatus.connected) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { appSettings = true; tuningTab = "Audio"; tuning = true }, modifier = Modifier.weight(1f)) { Text("App settings", fontSize = 12.sp) }
                            TextButton(onClick = { crowdOpen = true }, modifier = Modifier.weight(1f)) { Text("Join crowd show", fontSize = 12.sp) }
                        }
                    }
                    if (tablet && settingsMinimized && !crowdStatus.connected) SidebarHandle("Settings", false,
                        { settingsMinimized = false }, Modifier.align(Alignment.CenterEnd))
                }
            }
            if (fullscreen) {
                if (crowdStatus.connected) Text(crowdStatus.phase, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),
                    color = Color.White.copy(alpha = .6f), fontSize = 10.sp)
                var hintVisible by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { delay(2400); hintVisible = false }
                if (hintVisible) Text("Tap anywhere to return", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).clip(CircleShape).background(Ink.copy(alpha = 0.7f)).padding(horizontal = 18.dp, vertical = 10.dp), color = Color.White, fontSize = 12.sp)
            }
            if (renderError != null) Text(renderError, Modifier.align(Alignment.Center).padding(30.dp).background(Panel).padding(20.dp), color = Color.White)
            if (crowdOpen) CrowdOverlay(crowd, crowdStatus, crowdInvitation, onCrowdJoin, onCrowdLeave, onCrowdBrightness) { crowdOpen = false }
            if (tuning) TuningOverlay(
                settings, status, connecting, onChange, onConnect, onDisconnect,
                fps = { surface?.engine?.measuredFps ?: 0f }, dismiss = { tuning = false }, captureLocked = recordingState.busy,
                songAssistant = songAssistant, initialTab = tuningTab, tablet = tablet,
                appSettings = appSettings, switchScope = { appSettings = !appSettings; tuningTab = if (appSettings) "Audio" else settings.scene.shapeTab },
                minimized = settingsMinimized, onMinimize = { settingsMinimized = !settingsMinimized },
                timingMinimized = timingMinimized, onTimingMinimize = { timingMinimized = !timingMinimized },
                performance = { surface?.engine?.qualityReport ?: RenderQualityReport() }, looks = looks, topControlsHeight = topControlsHeight,
                bottomInset = bottomInset, chooseFile = onChooseFile
            )
            if (tablet && settings.tabletMonitor && !crowdOpen) TabletMonitor(songAssistant,
                Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(bottom = bottomInset).padding(16.dp).width(300.dp), local = settings.source == AudioSource.LOCAL)
            if (tablet && settings.audioEnabled && settings.source != AudioSource.LOCAL && !fullscreen && !tuning && !crowdOpen && !crowdStatus.connected)
                TimingSidebar(settings,status.running,onChange,
                    Modifier.align(Alignment.TopStart).safeDrawingPadding()
                        .padding(start=16.dp,top=topControlsHeight + 28.dp)
                        .width(if (timingMinimized) 56.dp else 300.dp)
                        .heightIn(max=(maxHeight-topControlsHeight-300.dp).coerceAtLeast(120.dp)),
                    minimized = timingMinimized, onMinimize = { timingMinimized = !timingMinimized })
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().onSizeChanged { topControlsHeight = with(density) { it.height.toDp() } },
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    if (showPlayback && settings.source == AudioSource.LOCAL) LocalPlaybackControls(onChooseFile, recordingState.busy, Modifier.width(hudSlotWidth))
                    else if (showPlayback) PlaybackControls(songState.notificationAccess, Modifier.width(hudSlotWidth))
                    else Spacer(Modifier.weight(1f))
                    if (compact && !crowdStatus.connected && !crowdOpen) Row(Modifier.width(hudSlotWidth),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        SessionRecordControl(recordingState, status.source.takeIf { status.running }, connecting, onRecord,
                            { recording.stop() }, Modifier.weight(1f))
                        FrameRateBadge(measuredFps.roundToInt())
                    } else Column(Modifier.widthIn(max = hudSlotWidth), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!crowdStatus.connected && !crowdOpen) SessionRecordControl(recordingState,
                            status.source.takeIf { status.running }, connecting, onRecord, { recording.stop() }, Modifier.fillMaxWidth())
                        FrameRateBadge(measuredFps.roundToInt())
                    }
                }
            }
            if (showWaveform) TrackWaveform(recordingState.busy, tablet,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                    .onSizeChanged { waveformHeight = with(density) { it.height.toDp() } })
            if (!recordingState.busy && recordingState.message != null) {
                Snackbar(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(horizontal = 16.dp).padding(top = topControlsHeight + 24.dp),
                    action = { TextButton(onClick = recording::dismissMessage) { Text("Dismiss") } }) {
                    Text(recordingState.message!!)
                }
                LaunchedEffect(recordingState.message) { delay(6000); recording.dismissMessage() }
            }
        }
    }
}

@Composable private fun FrameRateBadge(fps: Int) {
    Text("$fps FPS", modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Ink.copy(alpha = .94f))
        .border(1.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
        .semantics { contentDescription = "Current frame rate $fps FPS" },
        color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, maxLines = 1)
}

@Composable private fun SceneCard(scene: Scene, selected: Boolean, compact: Boolean = false, click: () -> Unit) {
    val labelScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(Modifier.width((if (scene == Scene.KALEIDOSCOPE) 128.dp else 112.dp) * labelScale)
        .height(if (compact) 48.dp else 90.dp).clip(RoundedCornerShape(16.dp))
        .background(Brush.verticalGradient(listOf(scene.accent.copy(alpha = if (selected) 0.23f else 0.10f), Panel)))
        .border(1.dp, if (selected) scene.accent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.09f), RoundedCornerShape(16.dp))
        .selectable(selected, onClick = click, role = Role.RadioButton)
        .semantics { contentDescription = "Pattern ${scene.title}" }.padding(10.dp)) {
        if (!compact) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { SceneMotif(scene, Modifier.fillMaxSize().padding(2.dp)) }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.then(if (compact) Modifier.fillMaxHeight() else Modifier), verticalAlignment = Alignment.CenterVertically) {
            Text(scene.title, fontSize = 12.sp, color = if (selected) Color.White else Color(0xFFC7C8DA),
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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

            Scene.WORMHOLE -> for (i in 1..6) drawCircle(c.copy(alpha = i / 7f), radius * i / 6f, center + Offset((6-i) * 1.8f, 0f), style = Stroke(1.2.dp.toPx()))

            Scene.KALEIDOSCOPE -> for (depth in 0..3) {
                val r = radius * (1f - depth * .23f)
                drawRect(c.copy(alpha = .9f - depth * .16f), center - Offset(r, r * .7f),
                    Size(r * 2, r * 1.4f), style = Stroke(1.2.dp.toPx()))
                for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) {
                    val corner = center + Offset(x * r, y * r * .7f)
                    drawLine(c.copy(alpha = .4f), corner, center, 1.dp.toPx())
                }
            }
            Scene.MANDELBROT -> {
                val path=Path()
                for (i in 0..120) {
                    val a=i*2*PI/120
                    val x=center.x+(cos(a)*.5-cos(2*a)*.25).toFloat()*radius*1.7f
                    val y=center.y+(sin(a)*.5-sin(2*a)*.25).toFloat()*radius*1.7f
                    if (i==0) path.moveTo(x,y) else path.lineTo(x,y)
                }
                path.close(); drawPath(path,c,style=Stroke(1.5.dp.toPx()))
                drawCircle(c.copy(alpha=.7f),radius*.33f,center-Offset(radius,0f),style=Stroke(1.2.dp.toPx()))
            }
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
    val connectRow = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; connectRow.bringIntoView() }
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
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel.copy(alpha = 0.9f)).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp)).padding(14.dp)) {
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
        if (source == AudioSource.LOCAL) {
            LocalFileSettings(onConnect, captureLocked)
            return@Column
        }
        if (source == AudioSource.SYSTEM) SpotifyRelayControls(captureLocked || connecting || status.running)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.bringIntoViewRequester(connectRow), verticalAlignment = Alignment.CenterVertically) {
            Text(if (source == AudioSource.SYSTEM) "Device playback" else "Microphone input", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            SettingHelp("Input source")
            TextButton(onClick = if (status.running) onDisconnect else onConnect, enabled = !connecting && !captureLocked, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text(if (captureLocked) "Input locked" else if (connecting) "Connecting…" else if (status.running) "Stop" else "Connect audio", fontSize = 11.sp)
            }
        }
        RhythmMonitor(status.running, rhythm, timingNow, energy, details = false)
        val feedback = RhythmFeedback.from(status.running, rhythm, timingNow)
        val helper = status.message ?: if (status.running && rhythm.signalPresent && !feedback.barReady) feedback.help else if (source == AudioSource.SYSTEM) {
            if (status.running && silentSeconds >= 5) {
                if (AudioEngine.inputRms < .000001f) "Connected, but Android is sending silence. Pause and resume playback. If it stays silent, use Microphone; the player may block capture."
                else "Playback is reaching prism at a very low level. Raise media volume or input gain."
            }
            else "Follows device playback, even with headphones. Some apps block capture."
        } else "Reacts to sound around you. Online song identification is optional."
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
