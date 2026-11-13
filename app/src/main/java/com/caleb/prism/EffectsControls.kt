package com.caleb.prism

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable internal fun EffectsControls(s: VisualSettings, change: (VisualSettings) -> Unit,
                                         active: String?, editing: (String?) -> Unit) {
    val fx = s.effects
    fun set(next: PostEffects) = change(s.copy(effects = next))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Global effects", style = ReadableText, modifier = Modifier.weight(1f))
        SettingHelp("Global effects")
        Switch(fx.enabled, { set(fx.copy(enabled = it)) }, Modifier.semantics { contentDescription = "Global effects" })
    }
    Text(if (fx.enabled) "All patterns // included in recordings" else "Bypassed // settings are retained",
        style = ReadableText, fontSize = 12.sp)
    @Composable fun slider(label: String, value: Float, high: Float = 1f, set: (Float) -> Unit) =
        OverlaySlider(label, value, 0f..high, "${(value * 100).roundToInt()}%", 0, active, editing, set)
    slider("Effect strength", fx.strength) { set(fx.copy(strength = it)) }
    slider("Bloom", fx.bloom, 2f) { set(fx.copy(bloom = it)) }
    slider("Bloom radius", fx.bloomRadius) { set(fx.copy(bloomRadius = it)) }
    slider("Bloom threshold", fx.threshold) { set(fx.copy(threshold = it)) }
    slider("Blur", fx.blur) { set(fx.copy(blur = it)) }
    slider("Blur radius", fx.blurRadius) { set(fx.copy(blurRadius = it)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Effect quality", style = ReadableText, modifier = Modifier.weight(1f), fontSize = 13.sp)
        SettingHelp("Effect quality")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Economy", "Balanced", "High").forEachIndexed { index, label ->
            FilterChip(fx.quality == index, { set(fx.copy(quality = index)) }, { Text(label, fontSize = 12.sp) })
        }
    }
}

@Composable internal fun RenderQualityControls(s: VisualSettings, report: RenderQualityReport, change: (VisualSettings) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Adaptive quality", style = ReadableText, modifier = Modifier.weight(1f), fontSize = 13.sp)
        SettingHelp("Adaptive quality")
        Switch(s.adaptiveQuality, { change(s.copy(adaptiveQuality = it)) }, Modifier.semantics { contentDescription = "Adaptive quality" })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Target frame rate", style = ReadableText, fontSize = 13.sp)
        SettingHelp("Target frame rate")
        for (target in listOf(30, 60)) FilterChip(s.targetFps == target,
            { change(s.copy(targetFps = target)) }, { Text("$target", fontSize = 12.sp) }, enabled = !s.batterySaver)
    }
    val heat = when (report.thermal) { 0, 1 -> "Normal"; 2 -> "Warm"; 3 -> "Hot"; else -> "Very hot" }
    Text("${(report.scale * 100).roundToInt()}% render scale // ${report.width} × ${report.height}\nThermal: $heat // target ${report.targetFps} fps",
        style = ReadableText, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp)
            .semantics { contentDescription = "Render quality status" })
}
