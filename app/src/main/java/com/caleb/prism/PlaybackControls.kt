package com.caleb.prism

import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** A controller for the player's session, independent of capture and online song lookup. */
@Composable fun PlaybackControls(access: Boolean, modifier: Modifier = Modifier) {
    val player by NowPlaying.controls.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Surface(modifier.semantics { contentDescription = "System playback controls" },
        shape = RoundedCornerShape(18.dp), color = Color(0xE6080A16), contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = .22f))) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text(if (!access) "Playback controls" else player?.title ?: "No active player",
                modifier = Modifier.padding(horizontal = 8.dp), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!access) TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    NotificationListenerService.requestRebind(NowPlaying.component(context))
                }.onFailure { Toast.makeText(context, "Open Android Settings → Notification access to enable prism.", Toast.LENGTH_LONG).show() }
            }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("ENABLE PLAYBACK", color = Color.White, fontSize = 10.sp) }
            else Row(Modifier.fillMaxWidth()) {
                for (command in PlaybackCommand.entries) {
                    val enabled = when (command) {
                        PlaybackCommand.PREVIOUS -> player?.previous == true
                        PlaybackCommand.TOGGLE -> player?.toggle == true
                        PlaybackCommand.NEXT -> player?.next == true
                    }
                    val playing = player?.playing == true
                    val label = when (command) {
                        PlaybackCommand.PREVIOUS -> "Previous track"
                        PlaybackCommand.NEXT -> "Next track"
                        PlaybackCommand.TOGGLE -> if (playing) "Pause playback" else "Play playback"
                    }
                    IconButton(onClick = {
                        if (!NowPlaying.command(context, command))
                            Toast.makeText(context, "Player control is no longer available.", Toast.LENGTH_SHORT).show()
                    }, enabled = enabled, modifier = Modifier.weight(1f).height(48.dp)
                        .semantics { contentDescription = label }) {
                        PlaybackGlyph(command, playing, if (enabled) Color.White else Color.White.copy(alpha = .3f))
                    }
                }
            }
        }
    }
}

@Composable private fun PlaybackGlyph(command: PlaybackCommand, playing: Boolean, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        fun triangle(left: Float, right: Float, reverse: Boolean = false) {
            drawPath(Path().apply {
                moveTo(if (reverse) right else left, h * .16f)
                lineTo(if (reverse) left else right, h * .5f)
                lineTo(if (reverse) right else left, h * .84f); close()
            }, color)
        }
        when (command) {
            PlaybackCommand.TOGGLE -> if (playing) {
                drawRect(color, Offset(w * .2f, h * .16f), Size(w * .2f, h * .68f))
                drawRect(color, Offset(w * .6f, h * .16f), Size(w * .2f, h * .68f))
            } else triangle(w * .24f, w * .84f)
            PlaybackCommand.PREVIOUS -> {
                drawRect(color, Offset(w * .12f, h * .16f), Size(w * .12f, h * .68f))
                triangle(w * .28f, w * .86f, true)
            }
            PlaybackCommand.NEXT -> {
                triangle(w * .14f, w * .72f)
                drawRect(color, Offset(w * .76f, h * .16f), Size(w * .12f, h * .68f))
            }
        }
    }
}
