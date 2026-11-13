package com.caleb.prism

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.*

private val FileInk = Color(0xED080A16)
private val BeatColor = Color(0xFFB6B8C8)
private val BarColor = Color(0xFF82E8CF)
private val PhraseColor = Color(0xFFCBB6FF)
internal fun trackTime(seconds: Double): String = "%d:%02d".format(java.util.Locale.ROOT, seconds.toInt().coerceAtLeast(0) / 60, seconds.toInt().coerceAtLeast(0) % 60)

@Composable fun LocalFileSettings(choose: () -> Unit, locked: Boolean) {
    val state by LocalAudio.state.collectAsStateWithLifecycle()
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(state.title, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!state.ready && !state.analyzing) Text("Mono/stereo // up to one hour", color = BeatColor, fontSize = 10.sp)
        Text(state.message, color = BeatColor, fontSize = 11.sp)
        if (state.analyzing) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Track analysis progress" })
            TextButton(onClick = LocalAudio::cancelAnalysis) { Text("CANCEL ANALYSIS", fontSize = 11.sp) }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = choose, enabled = !locked) { Text("CHOOSE FILE", fontSize = 11.sp) }
            TextButton(onClick = { LocalAudio.reanalyze() }, enabled = !locked && LocalAudio.hasPreviousFile) { Text("ANALYZE AGAIN", fontSize = 11.sp) }
            SettingHelp("Local file")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Track waveform", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            SettingHelp("Track waveform")
            Switch(state.waveform ?: tablet, LocalAudio::waveform, Modifier.semantics { contentDescription = "Track waveform" })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Meter", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f)); SettingHelp("Beats per bar")
            for (meter in listOf(0, 3, 4)) TextButton(onClick = { LocalAudio.configure(meter = meter) }, enabled = !locked && !state.analyzing, contentPadding = PaddingValues(4.dp)) {
                Text(if (meter == 0) "AUTO" else "$meter", color = if (state.meter == meter) BarColor else BeatColor, fontSize = 10.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Phrase length", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f)); SettingHelp("Phrase length")
            for (bars in listOf(8, 16, 32)) TextButton(onClick = { LocalAudio.configure(phraseBars = bars) }, enabled = !locked && !state.analyzing, contentPadding = PaddingValues(4.dp)) {
                Text("$bars", color = if (state.phraseBars == bars) PhraseColor else BeatColor, fontSize = 10.sp)
            }
        }
        Text("Markers are estimates. ANALYZE AGAIN applies meter and phrase changes.", color = BeatColor, fontSize = 10.sp)
    }
}

@Composable fun LocalPlaybackControls(choose: () -> Unit, locked: Boolean, modifier: Modifier = Modifier) {
    val state by LocalAudio.state.collectAsStateWithLifecycle()
    Surface(modifier.semantics { contentDescription = "Local playback controls" }, color = FileInk,
        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .22f))) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text(state.title, Modifier.padding(horizontal = 8.dp), color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!state.ready) TextButton(onClick = choose, enabled = !locked && !state.analyzing, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (state.analyzing) "ANALYZING ${(state.progress * 100).roundToInt()}%" else "CHOOSE FILE", color = Color.White, fontSize = 10.sp)
            } else Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { LocalAudio.seek(0.0) }, enabled = !locked,
                    modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = "Restart local track" }, contentPadding = PaddingValues(0.dp)) { Text("|‹", color = Color.White, fontSize = 18.sp) }
                TextButton(onClick = { if (state.playing) LocalAudio.pause() else LocalAudio.play() }, enabled = state.playing || !locked,
                    modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = if (state.playing) "Pause local playback" else "Play local playback" }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (state.playing) "Ⅱ" else "▶", color = Color.White, fontSize = 18.sp)
                }
                TextButton(onClick = { LocalAudio.seek(state.position + 10) }, enabled = !locked,
                    modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = "Forward ten seconds" }, contentPadding = PaddingValues(0.dp)) { Text("+10", color = Color.White, fontSize = 12.sp) }
            }
        }
    }
}

@Composable fun TrackWaveform(locked: Boolean, tablet: Boolean, modifier: Modifier = Modifier) {
    val state by LocalAudio.state.collectAsStateWithLifecycle()
    val track = LocalAudio.track ?: return
    val shape = RoundedCornerShape(16.dp)
    var scrub by remember(track) { mutableStateOf<Double?>(null) }
    val position = scrub ?: state.position
    val peaks = remember(track) {
        FloatArray(1024) { bin ->
            val frames = track.map.frames
            val first = bin * frames.size / 1024
            val end = ((bin + 1) * frames.size / 1024).coerceAtLeast(first + 1).coerceAtMost(frames.size)
            (first until end).maxOfOrNull { frames[it].peak } ?: 0f
        }
    }
    Column(modifier.clipPanel(shape).semantics { contentDescription = "Track waveform panel" }.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${trackTime(position)} / ${trackTime(state.duration)}", color = Color.White, fontSize = 11.sp)
            Text(if (state.bpm > 0) " // ≈${state.bpm.roundToInt()} BPM" else " // timing unresolved", color = BarColor, fontSize = 10.sp, modifier = Modifier.weight(1f))
            SettingHelp("Track waveform")
            TextButton(onClick = { LocalAudio.waveform(false) }, contentPadding = PaddingValues(4.dp)) { Text("HIDE", fontSize = 10.sp) }
        }
        Canvas(Modifier.fillMaxWidth().height(if (tablet) 48.dp else 36.dp)
            .semantics {
                contentDescription = "Track waveform seek"
                progressBarRangeInfo = ProgressBarRangeInfo(state.position.toFloat(), 0f..state.duration.toFloat())
                if (!locked) setProgress { LocalAudio.seek(it.toDouble()); true }
            }
            .pointerInput(track, locked) { detectTapGestures { if (!locked) LocalAudio.seek(it.x / size.width * track.duration) } }
            .pointerInput(track, locked) { detectDragGestures(
                onDragEnd = { scrub?.let(LocalAudio::seek); scrub = null }, onDragCancel = { scrub = null },
                onDrag = { change, _ -> change.consume(); if (!locked) scrub = (change.position.x / size.width * track.duration).coerceIn(0.0, track.duration) }
            ) }) {
            val bins = peaks.size
            for (bin in 0 until bins) {
                val peak = peaks[bin]
                val x = (bin + .5f) * size.width / bins
                val amplitude = (sqrt(peak) * size.height * .46f).coerceAtLeast(1f)
                drawLine(if (x / size.width < position / track.duration) BarColor else BeatColor.copy(alpha = .5f),
                    Offset(x, size.height / 2 - amplitude), Offset(x, size.height / 2 + amplitude), (size.width / bins).coerceAtLeast(1f))
            }
            var lastBeat = -10f; var lastBar = -10f
            for (marker in track.map.markers) {
                val x = (marker.time / track.duration * size.width).toFloat()
                if (marker.kind == 0 && x - lastBeat < 5) continue
                if (marker.kind == 1 && x - lastBar < 10) continue
                val color = if (marker.kind == 2) PhraseColor else if (marker.kind == 1) BarColor else BeatColor
                drawLine(color.copy(alpha = .8f), Offset(x, 0f), Offset(x, if (marker.kind > 0) size.height else size.height * .18f), if (marker.kind == 2) 2f else 1f)
                lastBeat = x; if (marker.kind > 0) lastBar = x
            }
            val playhead = (position / track.duration * size.width).toFloat()
            drawLine(Color.White, Offset(playhead, 0f), Offset(playhead, size.height), 2f)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Beat", color = BeatColor, fontSize = 9.sp); Text("Bar", color = BarColor, fontSize = 9.sp)
            Text("Phrase // estimated", color = PhraseColor, fontSize = 9.sp)
            Text(if (locked) "Seek locked while recording" else "Tap or drag to seek", color = BeatColor, fontSize = 9.sp)
        }
    }
}

private fun Modifier.clipPanel(shape: RoundedCornerShape) = this.background(FileInk, shape)
    .border(1.dp, Color.White.copy(alpha = .18f), shape)
