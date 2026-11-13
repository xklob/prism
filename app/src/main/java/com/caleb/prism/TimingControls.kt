package com.caleb.prism

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable fun TimingControls(s: VisualSettings, running: Boolean, timing: RhythmState,
                                change: (VisualSettings) -> Unit, phrases: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedButton(onClick = AudioEngine::tapTempo, enabled = running, modifier = Modifier.fillMaxWidth()) { Text("Tap tempo", fontSize = 12.sp) }
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = AudioEngine::alignBeat, enabled = running && timing.bpm > 0f, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Beat starts here", fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = AudioEngine::alignBar, enabled = running && timing.bpm > 0f, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Bar starts here", fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Tempo", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = AudioEngine::halfTempo, enabled = running && timing.bpm > 0f) { Text("½ BPM", fontSize = 12.sp) }
            TextButton(onClick = AudioEngine::doubleTempo, enabled = running && timing.bpm > 0f) { Text("2× BPM", fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Beats per bar", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            for ((meter,label) in listOf(0 to "Auto",3 to "3",4 to "4"))
                TextButton(onClick = { change(s.copy(beatsPerBar=meter)) }, modifier = Modifier.width(48.dp), contentPadding = PaddingValues(4.dp)) {
                    Text(label, fontSize = 12.sp, color = if (s.beatsPerBar==meter) MaterialTheme.colorScheme.primary else Color.White)
                }
        }
        if (phrases) Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = AudioEngine::alignPhrase, enabled = running && timing.locked, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                Text("Phrase starts here", fontSize = 12.sp)
            }
            TextButton(onClick = AudioEngine::automaticPhrase, enabled = running, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                Text("Auto phrase", fontSize = 12.sp)
            }
        }
        OutlinedButton(onClick = AudioEngine::automaticTiming, enabled = running, modifier = Modifier.fillMaxWidth()) { Text("Auto timing", fontSize = 12.sp) }
        Text("Auto starts from your alignment, then follows the music.", color = Color(0xFFCBC9D4), fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable fun TimingSidebar(s: VisualSettings, running: Boolean, change: (VisualSettings) -> Unit, modifier: Modifier = Modifier) {
    var timing by remember { mutableStateOf(RhythmState()) }
    LaunchedEffect(running) {
        while (true) { timing=AudioEngine.rhythm; delay(100) }
    }
    val shape=RoundedCornerShape(18.dp)
    Column(modifier.clip(shape).background(Color(0xD9060914)).border(1.dp,Color.White.copy(alpha=.14f),shape)
        .semantics { contentDescription="Manual timing panel" }.verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("TIMING", color=Color.White, fontSize=12.sp, letterSpacing=1.sp)
        Spacer(Modifier.height(8.dp))
        TimingControls(s,running,timing,change,phrases=true)
    }
}
