package com.caleb.prism

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

/** A persistent control outside the GL surface, including fullscreen and tuning. */
@Composable
fun SessionRecordControl(state: RecordingState, activeSource: AudioSource?, connecting: Boolean,
                         record: () -> Unit, stop: () -> Unit, modifier: Modifier = Modifier) {
    var seconds by remember(state.startedNs) { mutableLongStateOf(0L) }
    LaunchedEffect(state.phase, state.startedNs) {
        while (state.phase == RecordingPhase.RECORDING) {
            seconds = (System.nanoTime() - state.startedNs).coerceAtLeast(0) / 1_000_000_000L
            delay(250)
        }
    }
    val action = when (state.phase) {
        RecordingPhase.IDLE -> "Record session"
        RecordingPhase.RECORDING -> "Stop recording"
        RecordingPhase.SAVING -> "Saving video"
        else -> "Cancel recording"
    }
    val source = if (state.busy) state.source else activeSource
    OutlinedButton(onClick = if (state.busy) stop else record,
        enabled = !connecting && state.phase != RecordingPhase.SAVING,
        modifier = modifier.heightIn(min = 56.dp).semantics { contentDescription = action },
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .22f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xE6080A16), contentColor = Color.White,
            disabledContainerColor = Color(0xE6080A16), disabledContentColor = Color(0xFFA7A8BD)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        Box(Modifier.size(9.dp).background(Color(0xFFFF778F),
            if (state.phase == RecordingPhase.RECORDING) RoundedCornerShape(2.dp) else CircleShape))
        Spacer(Modifier.width(9.dp))
        Column {
            Text(when (state.phase) {
                RecordingPhase.RECORDING -> "STOP // ${String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)}"
                RecordingPhase.SAVING -> "SAVING VIDEO…"
                RecordingPhase.CONNECTING, RecordingPhase.PREPARING -> "CANCEL // PREPARING"
                else -> "RECORD SESSION"
            }, fontSize = 11.sp)
            if (state.busy && state.format != null) Text(state.format, fontSize = 9.sp)
            Text(if (connecting) "Connecting audio…" else source?.label ?: "Silent video", fontSize = 10.sp,
                color = Color(0xFFBFC1D4))
        }
    }
}

@Composable
fun RecordingLibrary(state: RecordingState, open: (SavedRecording) -> Unit, share: (SavedRecording) -> Unit) {
    if (!state.busy) state.saved?.let { saved ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { open(saved) }) { Text("Last video", fontSize = 12.sp) }
            TextButton(onClick = { share(saved) }) { Text("Share", fontSize = 12.sp) }
        }
    }
}
