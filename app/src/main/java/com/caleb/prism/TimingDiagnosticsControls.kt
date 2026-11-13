package com.caleb.prism

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

private val BeatColor = Color(0xFF80E4F4)
private val BarColor = Color(0xFFFFA5CD)
private val PhraseColor = Color(0xFFB8F4BA)

@Composable internal fun TimingDiagnosticsControls(running: Boolean) {
    var samples by remember { mutableStateOf(AudioEngine.diagnostics.snapshot()) }
    var frozen by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(8) }
    var message by remember { mutableStateOf("") }
    val feedback = remember { BringIntoViewRequester() }
    LaunchedEffect(message) { if (message.isNotBlank()) feedback.bringIntoView() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val snapshot = samples
            scope.launch {
                message = withContext(Dispatchers.IO) {
                    runCatching {
                        require(snapshot.isNotEmpty()) { "No timing samples to export." }
                        requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(TimingDiagnostics.csv(snapshot)) }
                        "Timing CSV saved"
                    }.getOrElse { "Couldn't save timing CSV. Try another location." }
                }
            }
        }
    }
    LaunchedEffect(frozen) { while (!frozen) { samples = AudioEngine.diagnostics.snapshot(); delay(100) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Beat grid", style = ReadableText, modifier = Modifier.weight(1f))
        SettingHelp("Beat grid diagnostics")
        Text(if (frozen) "FROZEN" else if (running) "LIVE" else "STOPPED", style = ReadableText, fontSize = 10.sp)
    }
    Text("Detector evidence against the predicted grid", style = ReadableText, fontSize = 11.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (duration in listOf(8, 16)) FilterChip(seconds == duration, { seconds = duration }, { Text("${duration}s") })
        TextButton(onClick = { frozen = !frozen }) { Text(if (frozen) "LIVE VIEW" else "FREEZE") }
        SettingHelp("Freeze graph")
    }
    val tail = samples.lastOrNull()
    val points = if (tail == null) emptyList() else samples.filter { it.rhythm.timestamp >= tail.rhythm.timestamp - seconds }
    if (points.isEmpty()) Text("Connect audio to collect timing evidence. The last 60 seconds stay available after capture stops.", style = ReadableText, fontSize = 12.sp)
    Text("Cyan: beat prediction // pink: downbeat prediction", style = ReadableText, fontSize = 10.sp)
    TimingGraph(points, seconds, confidence = false)
    Text("Vertical lines: tracked beats // green: bar starts", style = ReadableText, fontSize = 10.sp)
    Spacer(Modifier.height(10.dp))
    Text("Confidence // beat, bar, phrase", style = ReadableText, fontSize = 12.sp)
    TimingGraph(points, seconds, confidence = true)
    Text("Cyan: beat // pink: bar // green: phrase", style = ReadableText, fontSize = 10.sp)
    tail?.let { sample ->
        val r = sample.rhythm; val p = sample.phrase
        val error = TimingDiagnostics.phaseErrorMs(r)?.let { "%+.0f ms".format(it) } ?: "No recent match"
        val flags = listOfNotNull(if (r.manualTempo || r.manualBar || p.manual) "Manual" else null,
            if (r.guided || p.guided) "Guided" else null, if (r.tempoAssisted) "Song BPM assisted" else null,
            if (r.holding) "Holding tempo" else null, if (r.coasting) "Coasting" else null,
            if (!r.signalPresent) "No signal" else null,
            if (r.conflict != RhythmConflict.NONE) "${r.conflict.name.lowercase()} conflict" else null).joinToString(" // ")
        Text("${"%.1f".format(r.bpm)} BPM // beat ${r.beatInBar(r.timestamp)} of ${r.beatsPerBar}\nPhrase ${p.bar ?: "?"} of ${p.bars} // ${if (p.manual) "manual" else "estimated"}",
            style = ReadableText, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        Text("Matched-beat offset: $error", style = ReadableText, fontSize = 11.sp)
        Text("Beat ${(r.confidence*100).roundToInt()}% // bar ${(r.barConfidence*100).roundToInt()}% // phrase ${(p.confidence*100).roundToInt()}%",
            style = ReadableText, fontSize = 11.sp)
        if (flags.isNotBlank()) Text(flags, style = ReadableText, fontSize = 11.sp)
    }
    Text("Confidence is evidence strength, not accuracy. Beat offsets compare the detector to its own grid; they do not measure speaker or screen latency.",
        style = ReadableText, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { export.launch("prism-timing.csv") }, enabled = samples.isNotEmpty()) { Text("EXPORT CSV") }
        SettingHelp("Export timing CSV")
        TextButton(onClick = { AudioEngine.diagnostics.clear(); samples = emptyList() }) { Text("CLEAR") }
    }
    if (message.isNotBlank()) Text(message, style = ReadableText, fontSize = 11.sp, modifier = Modifier.bringIntoViewRequester(feedback))
}

@Composable private fun TimingGraph(samples: List<TimingSample>, seconds: Int, confidence: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(if (confidence) 85.dp else 130.dp).background(Color.Black.copy(alpha = .65f))
        .semantics { contentDescription = if (confidence) "Beat, bar, and phrase confidence history" else "Beat predictions and tracked grid" }) {
        val end = samples.lastOrNull()?.rhythm?.timestamp ?: return@Canvas
        fun x(time: Double) = ((time - end + seconds) / seconds * size.width).toFloat()
        for (i in 0..seconds) {
            val at = size.width * i / seconds
            drawLine(Color.White.copy(alpha = .08f), Offset(at, 0f), Offset(at, size.height))
        }
        for (fraction in listOf(.25f, .5f, .75f)) drawLine(Color.White.copy(alpha = .08f), Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
        if (!confidence) samples.zipWithNext().forEach { (before, after) ->
            val a = before.rhythm; val b = after.rhythm
            val beat = floor(b.position)
            if (a.locked && b.locked && b.timestamp - a.timestamp in 0.0.. .15 &&
                b.position - a.position in 0.0.. .4 && floor(a.position) < beat) {
                val t = a.timestamp + (b.timestamp-a.timestamp) * (beat-a.position) / (b.position-a.position)
                val bar = b.barLocked && Math.floorMod(beat.toInt()-b.barOffset, b.beatsPerBar) == 0
                drawLine(if (bar) PhraseColor.copy(alpha = .7f) else Color.White.copy(alpha = .25f), Offset(x(t), 0f), Offset(x(t), size.height), if (bar) 2f else 1f)
            }
        }
        fun line(color: Color, value: (TimingSample) -> Float) {
            val path = Path()
            samples.forEachIndexed { index, sample ->
                val px = x(sample.rhythm.timestamp); val py = (1f-value(sample).coerceIn(0f, 1f)) * (size.height-4f) + 2f
                if (index == 0 || sample.rhythm.timestamp - samples[index-1].rhythm.timestamp > .15) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, color, style = Stroke(2f))
        }
        line(BeatColor) { if (confidence) it.rhythm.confidence else it.rhythm.beatProbability }
        line(BarColor) { if (confidence) it.rhythm.barConfidence else it.rhythm.downbeatProbability }
        if (confidence) line(PhraseColor) { it.phrase.confidence }
    }
}
