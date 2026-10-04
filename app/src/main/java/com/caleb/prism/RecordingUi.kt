package com.caleb.prism

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun RecordingLauncher(state: RecordingState, source: AudioSource, record: (AudioSource) -> Unit,
                      open: (SavedRecording) -> Unit, share: (SavedRecording) -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    var chosen by remember(source) { mutableStateOf(source) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { chosen = source; dialog = true }, enabled = !state.busy) {
            Box(Modifier.size(9.dp).background(if (state.busy) Color.Gray else Color(0xFFFF778F), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("Record session", fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        if (!state.busy) state.saved?.let { saved ->
            TextButton(onClick = { open(saved) }, contentPadding = PaddingValues(8.dp)) { Text("Last video", fontSize = 12.sp) }
            TextButton(onClick = { share(saved) }, contentPadding = PaddingValues(8.dp)) { Text("Share", fontSize = 12.sp) }
        }
    }
    if (dialog) AlertDialog(
        onDismissRequest = { dialog = false },
        title = { Text("Record a session") },
        text = {
            Column {
                Text("Save the visuals with sound. Controls stay out of the video.", fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                AudioSource.entries.forEach { input ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .selectable(chosen == input, role = Role.RadioButton, onClick = { chosen = input })
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(chosen == input, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(input.label)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(if (chosen == AudioSource.SYSTEM) "Records phone playback. Apps that block audio capture will be silent."
                    else "Records sound around your phone.", fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Text("MP4 · 30 fps · Movies/Prism\nLeaving Prism saves the recording.", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { dialog = false; record(chosen) }) { Text("Start recording") } },
        dismissButton = { TextButton(onClick = { dialog = false }) { Text("Cancel") } }
    )
}

@Composable
fun RecordingBadge(state: RecordingState, stop: () -> Unit, modifier: Modifier = Modifier) {
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.phase, state.startedNs) {
        while (state.phase == RecordingPhase.RECORDING) {
            seconds = (System.nanoTime() - state.startedNs).coerceAtLeast(0) / 1_000_000_000L
            delay(250)
        }
    }
    Row(modifier.clip(CircleShape).background(Color(0xDF080A16)).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(Color(0xFFFF778F), CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(when (state.phase) {
            RecordingPhase.CONNECTING -> "Connecting audio…"
            RecordingPhase.PREPARING -> "Preparing…"
            RecordingPhase.SAVING -> "Saving video…"
            else -> "REC ${String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)}"
        }, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (state.phase != RecordingPhase.SAVING) TextButton(onClick = stop) {
            Text(if (state.phase == RecordingPhase.RECORDING) "Stop recording" else "Cancel", fontSize = 12.sp)
        } else Spacer(Modifier.width(14.dp).height(48.dp))
    }
}
