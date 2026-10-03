package com.caleb.prism

import kotlin.math.*

data class MusicalMotion(val beat: Float=0f, val bar: Float=0f, val flow: Float=0f, val texture: Float=0f)

/** Time-based envelopes follow the beat grid at display refresh rate, not audio-buffer arrival rate. */
class BeatMotion {
    var motion=MusicalMotion(); private set
    fun update(timing: RhythmState, levels: AudioLevels, now: Double, settings: VisualSettings): MusicalMotion {
        if (!settings.audioEnabled || settings.audioAmount <= 0f) {
            motion=MusicalMotion(); return motion
        }
        if (settings.paused) return motion
        if (!timing.signalPresent || now-timing.timestamp > 0.5) {
            motion=MusicalMotion(); return motion
        }
        val position=timing.positionAt(now+settings.syncOffsetMs/1000.0)+1e-7
        val phase=position-floor(position)
        val beat=if (timing.locked) exp(-phase/settings.pulseLength)*timing.confidence
            else exp(-max(0.0,now-timing.lastBeatTime)/0.13)*0.65
        val barPosition=position-timing.barOffset
        val barPhase=barPosition-floor(barPosition/timing.beatsPerBar)*timing.beatsPerBar
        val bar=if (timing.barLocked) exp(-barPhase/(settings.pulseLength*2.5))*timing.barConfidence else 0.0
        val flow=if (timing.locked) (0.5-0.5*cos(2*PI*(if (timing.barLocked) barPhase/timing.beatsPerBar else phase)))*timing.confidence else 0.0
        motion=MusicalMotion(
            (beat*settings.beatImpact*settings.audioAmount).toFloat().coerceIn(0f,3f),
            (bar*settings.barImpact*settings.audioAmount).toFloat().coerceIn(0f,3f),
            (flow*settings.flowImpact*settings.audioAmount).toFloat().coerceIn(0f,3f),
            (levels.high*settings.textureAmount*settings.audioAmount).coerceIn(0f,2f))
        return motion
    }
}
