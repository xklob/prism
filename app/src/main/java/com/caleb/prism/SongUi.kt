package com.caleb.prism

import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val SongText = TextStyle(color = Color.White, shadow = Shadow(Color.Black, Offset(0f, 2f), 7f))
private val SongMuted = Color(0xFFCBC9D4)
private val SongAccent = Color(0xFFD9C6FF)

@Composable fun SongMonitor(assistant: SongAssistant, onSettings: (() -> Unit)? = null) {
    val state by assistant.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.song?.title ?: if (state.options.enabled) "Finding the song" else "Song assist is off",
                    style = SongText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.song?.artist?.takeIf { it.isNotBlank() }?.let { Text(it, style = SongText, color = SongMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (onSettings != null) TextButton(onClick = onSettings) { Text("Song", color = SongAccent) }
        }
        if (state.options.enabled) {
            val position = state.positionMs?.let { "${if (state.approximatePosition) "≈ " else ""}${songTime(it)} / ${state.song?.durationMs?.let(::songTime) ?: "?:??"}" }
            Text(listOfNotNull(state.matchLabel.takeIf { it.isNotBlank() }, position,
                state.catalog?.bpm?.let { "Catalog %.1f BPM".format(it) }).joinToString(" · "), style = SongText, color = SongMuted, fontSize = 10.sp)
            val timing = AudioEngine.rhythm
            val hint = AudioEngine.tempoHint
            val message = if (timing.locked && !timing.manualTempo && hint != null && !hint.agrees(timing.bpm.toDouble()))
                "Catalog differs from live tempo; following the audio" else state.message
            Text(message, style = SongText, color = SongMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable fun SongControls(assistant: SongAssistant) {
    val state by assistant.state.collectAsStateWithLifecycle()
    val capture by AudioEngine.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingKey by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }
    var phrase by remember { mutableStateOf(AudioEngine.phrase) }
    var timing by remember { mutableStateOf(AudioEngine.rhythm) }
    LaunchedEffect(Unit) { while (true) { phrase = AudioEngine.phrase; timing = AudioEngine.rhythm; delay(100) } }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Song assist", style = SongText, modifier = Modifier.weight(1f))
            Switch(state.options.enabled, { assistant.setOptions(state.options.copy(enabled = it)) },
                Modifier.semantics { contentDescription = "Song assist" })
        }
        Text("Look up song length and BPM with ReccoBeats. Song titles or IDs are sent online; live timing keeps working offline.", style = SongText, color = SongMuted, fontSize = 11.sp)
        SongMonitor(assistant)
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                NotificationListenerService.requestRebind(NowPlaying.component(context))
            }
        }) { Text(if (state.notificationAccess) "Playback info access enabled" else "Allow playback info access", style = SongText, color = SongAccent) }
        Text("For Spotify and YouTube, enable Prism in Android’s Notification access settings. Prism reads media-session metadata, not notification messages.", style = SongText, color = SongMuted, fontSize = 11.sp)
        HorizontalDivider(color = Color.White.copy(alpha = .16f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Identify audio with AudD", style = SongText, modifier = Modifier.weight(1f), fontSize = 13.sp)
            Switch(state.options.recognition, { assistant.setOptions(state.options.copy(recognition = it)) },
                Modifier.semantics { contentDescription = "Identify audio with AudD" }, enabled = state.options.enabled && state.options.hasKey)
        }
        Text("Optional: sends 8–10 seconds of microphone or captured system audio to AudD. Uses your API key and may incur charges. Checks normally run every 90 seconds, with a 30-second minimum and a 40-request hourly limit.", style = SongText, color = SongMuted, fontSize = 11.sp)
        if (editingKey) {
            OutlinedTextField(token, { token = it.take(256) }, label = { Text("AudD API key") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Stored encrypted on this device. Get a trial key at dashboard.audd.io.", style = SongText, color = SongMuted, fontSize = 11.sp)
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://dashboard.audd.io/"))) } }) {
                Text("Get trial key", style = SongText, color = SongAccent)
            }
            Row {
                TextButton(onClick = {
                    keyError = assistant.saveToken(token)
                    if (keyError == null) { editingKey = false; token = "" }
                }, enabled = token.isNotBlank()) { Text("Save key", style = SongText, color = SongAccent) }
                TextButton(onClick = { editingKey = false; token = ""; keyError = null }) { Text("Cancel", style = SongText) }
            }
        } else Row {
            TextButton(onClick = { editingKey = true }) { Text(if (state.options.hasKey) "Replace AudD key" else "Add AudD key", style = SongText, color = SongAccent) }
            if (state.options.hasKey) TextButton(onClick = { keyError = assistant.saveToken("") }) { Text("Remove key", style = SongText) }
        }
        keyError?.let { Text(it, style = SongText, color = Color(0xFFFFD38C), fontSize = 11.sp) }
        TextButton(onClick = assistant::identifyNow, enabled = capture.running && state.options.enabled && state.options.recognition && state.options.hasKey && !state.busy) {
            Text("Identify now", color = if (capture.running && state.options.recognition) SongAccent else SongMuted)
        }
        if (state.options.recognition) Text("${state.requestsRemaining} requests left in the current hourly limit", style = SongText, color = SongMuted, fontSize = 10.sp)
        HorizontalDivider(color = Color.White.copy(alpha = .16f))
        Text("Phrase length", style = SongText, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(8, 16, 32).forEach { bars -> FilterChip(state.options.phraseBars == bars,
                { assistant.setOptions(state.options.copy(phraseBars = bars)) }, label = { Text("$bars bars") }) }
        }
        PhraseMonitor(phrase)
        Row {
            TextButton(onClick = AudioEngine::alignPhrase, enabled = capture.running && timing.locked) { Text("Phrase starts here", color = SongAccent) }
            TextButton(onClick = AudioEngine::automaticPhrase, enabled = capture.running) { Text("Auto phrase", color = SongAccent) }
        }
        Text("Phrase estimates use bar-level sound changes and may arrive one bar late. Song position and duration do not establish the first downbeat. Tap Phrase starts here on a known boundary to align it yourself.", style = SongText, color = SongMuted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable fun PhraseMonitor(phrase: PhraseState) {
    Text(if (phrase.bar == null) phrase.message else "Phrase ${if (phrase.manual) "manual" else "estimated"} · bar ${phrase.bar}/${phrase.bars} · ${(phrase.confidence * 100).toInt()}% evidence",
        style = SongText, color = if (phrase.manual) Color(0xFF9AF0CB) else Color(0xFFFFD38C), fontSize = 10.sp,
        modifier = Modifier.semantics { contentDescription = if (phrase.bar == null) "Phrase not aligned" else "Phrase bar ${phrase.bar} of ${phrase.bars}, ${if (phrase.manual) "manual" else "estimated"}" })
}

private fun songTime(ms: Long): String = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
