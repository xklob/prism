package com.caleb.prism

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun LookControls(library: LookLibrary, scene: Scene) {
    var name by rememberSaveable { mutableStateOf("") }
    var dialog by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<LookPreset?>(null) }
    var deleting by remember { mutableStateOf<LookPreset?>(null) }
    var favorites by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Saved looks", style = ReadableText, modifier = Modifier.weight(1f))
        SettingHelp("Look library")
        TextButton(onClick = { rename = null; name = scene.title; dialog = true }, enabled = library.looks.size < 32) { Text("SAVE NEW") }
    }
    Text("Pattern, tuning, and effects // audio and timing stay connected", style = ReadableText, fontSize = 11.sp)
    OverlaySlider("Transition time", library.seconds, 0f..8f, if (library.seconds == 0f) "Cut" else "%.1f s".format(library.seconds),
        0, null, {}, library::duration)
    library.queued?.let { queued ->
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .8f), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text("Queued // ${queued.name}", style = ReadableText)
                Text(library.message, style = ReadableText, fontSize = 11.sp)
                Row {
                    TextButton(onClick = { library.applyNow(queued) }) { Text("APPLY NOW") }
                    TextButton(onClick = library::cancel) { Text("CANCEL") }
                    SettingHelp("Phrase cue")
                }
            }
        }
    }
    if (library.queued == null && library.message.isNotBlank()) Text(library.message, style = ReadableText, fontSize = 11.sp)
    FilterChip(favorites, { favorites = !favorites }, { Text("Favorites") })
    val visible = library.looks.filter { !favorites || it.favorite }
    if (visible.isEmpty()) Text(if (favorites) "Star a look to add a favorite." else "Save a look to start your library. Up to 32 looks.", style = ReadableText, fontSize = 12.sp)
    visible.forEach { look ->
        key(look.id) {
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { library.favorite(look) }, modifier = Modifier.semantics { contentDescription = "${if (look.favorite) "Unfavorite" else "Favorite"} ${look.name}" }) {
                    Text(if (look.favorite) "★" else "☆", fontSize = 23.sp,
                        color = if (look.favorite) MaterialTheme.colorScheme.primary else Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(look.name, style = ReadableText, fontSize = 13.sp)
                    Text(look.settings.scene.title, style = ReadableText, fontSize = 10.sp)
                }
                TextButton(onClick = { library.applyNow(look) }, modifier = Modifier.semantics { contentDescription = "Apply ${look.name}" }) { Text("PLAY", fontSize = 11.sp) }
                TextButton(onClick = { library.queue(look) }, modifier = Modifier.semantics { contentDescription = "Queue ${look.name} on next phrase" }) { Text("CUE", fontSize = 11.sp) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Options for ${look.name}" }) { Text("⋮", fontSize = 23.sp, color = Color.White) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Rename") }, { menu = false; rename = look; name = look.name; dialog = true })
                        DropdownMenuItem({ Text("Delete") }, { menu = false; deleting = look })
                    }
                }
            }
        }
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text(if (rename == null) "Save look" else "Rename look") },
        text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text("Look name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (rename == null) library.save(name) else library.rename(rename!!, name); dialog = false }, enabled = name.isNotBlank()) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = { dialog = false }) { Text("CANCEL") } })
    deleting?.let { look -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${look.name}?") },
        text = { Text("This removes the saved look from this device.") },
        confirmButton = { TextButton(onClick = { library.delete(look); deleting = null }) { Text("DELETE") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("CANCEL") } }) }
}
