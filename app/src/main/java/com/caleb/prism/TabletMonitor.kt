package com.caleb.prism

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** Compose-only status stays visible in fullscreen and never enters the video renderer. */
@Composable fun TabletMonitor(assistant: SongAssistant, modifier: Modifier = Modifier) {
    val capture by AudioEngine.status.collectAsStateWithLifecycle()
    var rhythm by remember { mutableStateOf(RhythmState()) }
    var phrase by remember { mutableStateOf(PhraseState()) }
    var energy by remember { mutableFloatStateOf(0f) }
    var now by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(capture.running) {
        while (true) {
            rhythm = AudioEngine.rhythm; phrase = AudioEngine.phrase
            energy = AudioEngine.levels.energy; now = System.nanoTime() / 1e9
            delay(100)
        }
    }
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.clip(shape).background(Color(0xD9060914))
        .border(1.dp, Color.White.copy(alpha = .14f), shape)
        .semantics { contentDescription = "Tablet song and timing monitor" }
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SongMonitor(assistant)
        HorizontalDivider(color = Color.White.copy(alpha = .12f))
        RhythmMonitor(capture.running, rhythm, now, energy, details = false, showPhrase = false)
        if (!capture.running) capture.message?.let { Text(it, color = Color(0xFFCBC9D4), fontSize = 11.sp) }
        val fresh = capture.running && rhythm.signalPresent && now - rhythm.timestamp in -.1.. .5
        ConfidenceMeter("Phrase", if (fresh) phrase.confidence else 0f,
            fresh && phrase.bar != null, fresh && phrase.manual, Modifier.fillMaxWidth())
        PhraseMonitor(if (fresh) phrase else PhraseState())
    }
}
