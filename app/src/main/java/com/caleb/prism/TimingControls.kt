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
        TimingAction("Tap tempo", AudioEngine::tapTempo, running, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth()) {
            TimingAction("Beat starts here", AudioEngine::alignBeat, running && timing.bpm > 0f, Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            TimingAction("Bar starts here", AudioEngine::alignBar, running && timing.bpm > 0f, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Tempo", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            SettingHelp("Tempo")
            TextButton(onClick = AudioEngine::halfTempo, enabled = running && timing.bpm > 0f) { Text("½ BPM", fontSize = 12.sp) }
            TextButton(onClick = AudioEngine::doubleTempo, enabled = running && timing.bpm > 0f) { Text("2× BPM", fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Beats per bar", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            SettingHelp("Beats per bar")
            for ((meter,label) in listOf(0 to "Auto",3 to "3",4 to "4"))
                TextButton(onClick = { change(s.copy(beatsPerBar=meter)) }, modifier = Modifier.width(48.dp), contentPadding = PaddingValues(4.dp)) {
                    Text(label, fontSize = 12.sp, color = if (s.beatsPerBar==meter) MaterialTheme.colorScheme.primary else Color.White)
                }
        }
        if (phrases) {
            TimingAction("Phrase starts here", AudioEngine::alignPhrase, running && timing.locked, Modifier.fillMaxWidth())
            TimingAction("Auto phrase", AudioEngine::automaticPhrase, running, Modifier.fillMaxWidth())
        }
        TimingAction("Auto timing", AudioEngine::automaticTiming, running, Modifier.fillMaxWidth())
        Text("Auto starts from your alignment, then follows the music.", color = Color(0xFFCBC9D4), fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable internal fun TimingAction(label: String, action: () -> Unit, enabled: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(label, fontSize = 12.sp)
        }
        SettingHelp(label)
    }
}

@Composable fun SidebarHandle(label: String, left: Boolean, expand: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.width(56.dp), shape = RoundedCornerShape(16.dp), color = Color(0xE6080A16),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .2f))) {
        TextButton(onClick = expand, modifier = Modifier.height(96.dp).semantics { contentDescription = "Expand ${label.lowercase()}" },
            contentPadding = PaddingValues(2.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (left) "›" else "‹", color = Color.White, fontSize = 25.sp)
                Text(label, color = Color.White, fontSize = 9.sp)
            }
        }
    }
}

@Composable fun TimingSidebar(s: VisualSettings, running: Boolean, change: (VisualSettings) -> Unit,
                             modifier: Modifier = Modifier, minimized: Boolean = false, onMinimize: () -> Unit = {}) {
    val scroll = rememberScrollState()
    if (minimized) {
        SidebarHandle("Timing", true, onMinimize, modifier)
        return
    }
    var timing by remember { mutableStateOf(RhythmState()) }
    LaunchedEffect(running) {
        while (true) { timing=AudioEngine.rhythm; delay(100) }
    }
    val shape=RoundedCornerShape(18.dp)
    Column(modifier.clip(shape).background(Color(0xD9060914)).border(1.dp,Color.White.copy(alpha=.14f),shape)
        .semantics { contentDescription="Manual timing panel" }.padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("TIMING", color=Color.White, fontSize=12.sp, letterSpacing=1.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onMinimize, modifier = Modifier.semantics { contentDescription = "Minimize timing" }) {
                Text("‹ Minimize", color = Color.White, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
            TimingControls(s,running,timing,change,phrases=true)
        }
    }
}
