package com.caleb.prism

import androidx.compose.runtime.*

data class LookPreset(val id: String, val name: String, val favorite: Boolean, val settings: VisualSettings)

class LookLibrary(private val store: SettingsStore, private val current: () -> VisualSettings,
                  private val apply: (VisualSettings, Float) -> Unit) {
    var looks by mutableStateOf(store.presets()); private set
    var queued by mutableStateOf<LookPreset?>(null); private set
    var message by mutableStateOf(""); private set
    var seconds by mutableFloatStateOf(store.transitionSeconds); private set
    private val cue = PhraseCue()
    fun duration(value: Float) { seconds = value.coerceIn(0f, 8f); store.transitionSeconds = seconds }
    fun save(name: String) = runCatching { store.savePreset(name, current()); refresh(); message = "Look saved" }
        .onFailure { message = it.message ?: "Couldn't save this look" }
    fun rename(look: LookPreset, name: String) = runCatching {
        store.renamePreset(look.id, name); refresh(); queued = queued?.let { q -> looks.firstOrNull { it.id == q.id } }
    }.onFailure { message = it.message ?: "Couldn't rename this look" }
    fun favorite(look: LookPreset) { store.favoritePreset(look.id, !look.favorite); refresh() }
    fun delete(look: LookPreset) { store.deletePreset(look.id); if (queued?.id == look.id) cancel(); refresh() }
    fun applyNow(look: LookPreset) { cancel(); apply(look.settings.keepingSession(current()), seconds); message = "Applied ${look.name}" }
    fun queue(look: LookPreset) { queued = look; cue.reset(); message = cue.message }
    fun cancel() { queued = null; cue.reset(); message = "" }
    fun tick(sample: TimingSample, now: Double, running: Boolean, revision: Long) {
        val look = queued ?: return
        if (cue.update(sample.rhythm, sample.phrase, now, running && !current().paused, revision, current().syncOffsetMs)) applyNow(look)
        else message = cue.message
    }
    private fun refresh() { looks = store.presets() }
}
