package com.caleb.prism

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val MonitorGood=Color(0xFF9AF0CB)
private val MonitorPending=Color(0xFFFFD38C)
private val MonitorMuted=Color(0xFFCBC9D4)
private val MonitorText=TextStyle(color=Color.White,shadow=Shadow(Color.Black,Offset(0f,2f),7f))

/** Draws directly over the scene in Tune, with the same status in the main audio controls. */
@Composable fun RhythmMonitor(running: Boolean, timing: RhythmState, now: Double, energy: Float, details: Boolean=true) {
    val feedback=RhythmFeedback.from(running,timing,now)
    Column(Modifier.fillMaxWidth().padding(vertical=4.dp), verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(feedback.inputLabel,style=MonitorText,color=MonitorMuted,fontSize=10.sp)
            Spacer(Modifier.width(10.dp))
            Canvas(Modifier.weight(1f).height(12.dp).semantics { contentDescription="Audio level ${(energy*100).roundToInt()} percent" }) {
                val y=size.height/2
                drawLine(Color.Black.copy(alpha=.7f),Offset(0f,y),Offset(size.width,y),5.dp.toPx(),StrokeCap.Round)
                drawLine(MonitorMuted.copy(alpha=.3f),Offset(0f,y),Offset(size.width,y),2.dp.toPx(),StrokeCap.Round)
                if (running && energy > 0f) drawLine(MonitorGood,Offset(0f,y),Offset(size.width*energy.coerceIn(0f,1f),y),2.dp.toPx(),StrokeCap.Round)
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(if (running && timing.bpm > 0f) "%.1f BPM".format(timing.bpm) else "··· BPM",style=MonitorText,
                fontSize=if (details) 20.sp else 16.sp,fontFamily=FontFamily.Monospace,modifier=Modifier.weight(1f))
            val active=if (feedback.beatReady) timing.beatInBar(now) else 0
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp),modifier=Modifier.semantics {
                contentDescription=if (feedback.barReady) "Beat $active of ${timing.beatsPerBar}" else "Bar not locked"
            }) {
                for (beat in 1..timing.beatsPerBar) {
                    val lit=beat == active
                    Box(Modifier.size(if (details) 25.dp else 21.dp)
                        .border(1.dp,if (feedback.barReady && beat == 1) MonitorGood else MonitorMuted.copy(alpha=.5f),CircleShape)
                        .background(if (lit) if (feedback.barReady) MonitorGood else MonitorPending else Color.Transparent,CircleShape),contentAlignment=Alignment.Center) {
                        Text(if (feedback.barReady) "$beat" else "·",color=if (lit) Color(0xFF060914) else Color.White,fontSize=11.sp)
                    }
                }
            }
        }
        Text(if (running) "${feedback.beatLabel} · ${feedback.barLabel}" else feedback.beatLabel,style=MonitorText,
            color=if (feedback.barReady) MonitorGood else if (running) MonitorPending else MonitorMuted,fontSize=10.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            ConfidenceMeter("Beat",feedback.beatConfidence,feedback.beatReady,timing.manualTempo && feedback.beatReady,Modifier.weight(1f))
            ConfidenceMeter("Bar",feedback.barConfidence,feedback.barReady,timing.manualBar && feedback.barReady,Modifier.weight(1f))
        }
        if (details) Text(feedback.help,style=MonitorText,color=MonitorMuted,fontSize=10.sp,lineHeight=14.sp)
        if (running) {
            if (timing.tempoAssisted) Text("Tempo assisted by song lookup · phase follows audio", style=MonitorText, color=MonitorMuted, fontSize=10.sp)
            PhraseMonitor(AudioEngine.phrase)
        }
    }
}

@Composable private fun ConfidenceMeter(label: String, value: Float, ready: Boolean, manual: Boolean, modifier: Modifier) {
    val description=if (manual) "manual" else "${(value*100).roundToInt()}%"
    val color=if (ready) MonitorGood else MonitorPending
    Column(modifier.semantics { contentDescription="$label confidence $description" },verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("$label confidence · $description",style=MonitorText,fontSize=9.sp,color=if (manual) MonitorMuted else color)
        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            val y=size.height/2
            drawLine(Color.Black.copy(alpha=.7f),Offset(0f,y),Offset(size.width,y),5.dp.toPx(),StrokeCap.Round)
            drawLine(MonitorMuted.copy(alpha=.3f),Offset(0f,y),Offset(size.width,y),2.dp.toPx(),StrokeCap.Round)
            if (value > 0f) drawLine(color,Offset(0f,y),Offset(size.width*value,y),2.dp.toPx(),StrokeCap.Round)
        }
    }
}
