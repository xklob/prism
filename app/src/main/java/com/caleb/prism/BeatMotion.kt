package com.caleb.prism

import kotlin.math.*

data class MusicalMotion(val beat: Float=0f, val bar: Float=0f, val flow: Float=0f, val texture: Float=0f,
    val flash: Float=0f, val inversion: Float=0f)

/** Time-based envelopes follow the beat grid at display refresh rate, not audio-buffer arrival rate. */
class BeatMotion {
    var motion=MusicalMotion(); private set
    private var previousPosition=Double.NaN
    private var previousNow=0.0
    private var previousBar=0L
    private var previousMeter=0
    private var previousOffset=0
    private var effectAt=Double.NEGATIVE_INFINITY
    private var lastFlashAt=Double.NEGATIVE_INFINITY
    private fun clearEffects() { previousPosition=Double.NaN; effectAt=Double.NEGATIVE_INFINITY }
    fun update(timing: RhythmState, levels: AudioLevels, now: Double, settings: VisualSettings): MusicalMotion {
        if (!settings.audioEnabled || settings.audioAmount <= 0f) {
            clearEffects(); motion=MusicalMotion(); return motion
        }
        if (settings.paused) { clearEffects(); motion=motion.copy(flash=0f,inversion=0f); return motion }
        if (!timing.signalPresent || now-timing.timestamp > 0.5) {
            clearEffects(); motion=MusicalMotion(); return motion
        }
        val position=timing.positionAt(now+settings.syncOffsetMs/1000.0)+1e-7
        val phase=position-floor(position)
        val beat=if (timing.locked) exp(-phase/settings.pulseLength)*timing.confidence
            else exp(-max(0.0,now-timing.lastBeatTime)/0.13)*0.65
        val barPosition=position-timing.barOffset
        val barPhase=barPosition-floor(barPosition/timing.beatsPerBar)*timing.beatsPerBar
        val bar=if (timing.barLocked) exp(-barPhase/(settings.pulseLength*2.5))*timing.barConfidence else 0.0
        val flow=if (timing.locked) (0.5-0.5*cos(2*PI*(if (timing.barLocked) barPhase/timing.beatsPerBar else phase)))*timing.confidence else 0.0
        val trusted=timing.locked && timing.barLocked && !timing.coasting && timing.conflict == RhythmConflict.NONE
        if (trusted) {
            val index=floor(barPosition/timing.beatsPerBar).toLong()
            val dt=now-previousNow
            val expectedAdvance=dt*timing.bpm/60
            val continuous=previousPosition.isFinite() && dt in 0.0..0.25 &&
                abs(position-previousPosition-expectedAdvance) < 0.20 &&
                previousMeter == timing.beatsPerBar && previousOffset == timing.barOffset
            val barDuration=timing.beatsPerBar*60.0/timing.bpm
            // Trigger on a real boundary crossing, not on acquiring lock halfway into a bar.
            // Cooldown also suppresses a second flash if phase correction crosses the boundary twice.
            if (continuous && index == previousBar+1 && now-lastFlashAt > barDuration*0.65) {
                effectAt=now // Give even a 30 fps display one full-strength flash frame.
                lastFlashAt=now
            }
            if (!continuous) effectAt=Double.NEGATIVE_INFINITY
            previousPosition=position; previousBar=index; previousNow=now
            previousMeter=timing.beatsPerBar; previousOffset=timing.barOffset
        } else clearEffects()
        val age=max(0.0,now-effectAt)
        val flash=(1-age/0.035).coerceIn(0.0,1.0)
        val fade=(age/(settings.invertFadeMs/1000.0)).coerceIn(0.0,1.0)
        val inversion=1-fade*fade*(3-2*fade)
        motion=MusicalMotion(
            (beat*settings.beatImpact*settings.audioAmount).toFloat().coerceIn(0f,3f),
            (bar*settings.barImpact*settings.audioAmount).toFloat().coerceIn(0f,3f),
            (flow*settings.flowImpact*settings.audioAmount).toFloat().coerceIn(0f,3f),
            (levels.high*settings.textureAmount*settings.audioAmount).coerceIn(0f,2f),
            (flash*settings.downbeatFlash*settings.audioAmount).toFloat().coerceIn(0f,1f),
            (inversion*settings.downbeatInvert*settings.audioAmount).toFloat().coerceIn(0f,1f))
        return motion
    }
}
