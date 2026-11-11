package com.caleb.prism

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CrowdOverlay(client: CrowdClient, status: CrowdStatus, incoming: String,
    join: (String, Boolean) -> Unit, leave: () -> Unit, brightness: (Float) -> Unit, dismiss: () -> Unit) {
    var text by remember(incoming) { mutableStateOf(incoming) }
    var automaticWifi by remember { mutableStateOf(true) }
    var flashEnabled by remember { mutableStateOf(client.flashes) }
    var light by remember { mutableFloatStateOf(client.brightness) }
    var blank by remember { mutableStateOf(client.localBlackout) }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    ProvideTextStyle(TextStyle(color = Color.White, shadow = Shadow(Color.Black, Offset(0f, 2f), 7f))) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp)
                .heightIn(max = maxHeight * .68f).safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Crowd", fontSize = 24.sp, color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = dismiss) { Text("Done") }
                }
                if (status.connected) {
                    Text(status.phase + if (status.source) " · Audio input" else " · Following the VJ",
                        color = if (status.phase == "Ready") Color(0xFF82E8CF) else Color(0xFFF7C976))
                    Text(status.message, color = Color.White, fontSize = 13.sp)
                    Text("Clock estimate " + (status.clockMs?.let { "±%.1f ms".format(it) } ?: "waiting") +
                        " · %.0f FPS".format(status.fps), color = Color(0xFFBFC3D6), fontSize = 12.sp)
                    Text("Beat %.0f%% · Bar %.0f%% · Phrase %.0f%%".format(status.beatConfidence * 100,
                        status.barConfidence * 100, status.phraseConfidence * 100),
                        color = Color(0xFFBFC3D6), fontSize = 12.sp)
                    if (status.phraseConfidence > 0 && status.phraseBar > 0)
                        Text("Phrase bar " + status.phraseBar, color = Color.White, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Flashes and inversion", color = Color.White, modifier = Modifier.weight(1f))
                        Switch(flashEnabled, { flashEnabled = it; client.flashes = it })
                    }
                    Text("Screen brightness", color = Color.White, fontSize = 13.sp)
                    Slider(light, { light = it; brightness(it) }, valueRange = .1f..1f)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { blank = !blank; client.localBlackout = blank }) {
                            Text(if (blank) "Resume display" else "Blank my screen")
                        }
                        Button(onClick = leave) { Text("Leave show") }
                    }
                    if (status.source) Text("Use Connect audio on the main screen to share microphone or system-audio timing.",
                        color = Color(0xFFBFC3D6), fontSize = 12.sp)
                } else {
                    Text("Scan the invitation on the laptop with your camera, or paste it here.",
                        color = Color.White, fontSize = 13.sp)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(),
                        label = { Text("Show invitation") }, maxLines = 3)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(automaticWifi, { automaticWifi = it })
                        Text("Ask Android to join the hotspot", color = Color.White, fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { text = clipboard.getText()?.text.orEmpty() }) { Text("Paste") }
                        Button(onClick = { join(text, automaticWifi) }, enabled = text.isNotBlank()) { Text("Join show") }
                    }
                    if (status.message.isNotBlank()) Text(status.message, color = Color(0xFFF7C976), fontSize = 13.sp)
                }
            }
        }
    }
}
