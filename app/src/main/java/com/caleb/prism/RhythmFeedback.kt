package com.caleb.prism

/** Capture health, beat timing and bar placement are independent signals. */
data class RhythmFeedback(
    val inputLabel: String,
    val beatLabel: String,
    val barLabel: String,
    val help: String,
    val beatReady: Boolean,
    val barReady: Boolean,
    val beatConfidence: Float,
    val barConfidence: Float
) {
    companion object {
        fun from(running: Boolean, timing: RhythmState, now: Double): RhythmFeedback {
            val fresh=running && now-timing.timestamp in -0.1..0.5
            val audible=fresh && timing.signalPresent
            val beatReady=audible && timing.locked && !timing.coasting && timing.conflict != RhythmConflict.TEMPO
            val barReady=beatReady && timing.barLocked && timing.conflict == RhythmConflict.NONE
            val beat=when {
                !running -> "Disconnected"
                !fresh -> "Input stalled"
                !audible -> "Waiting for sound"
                timing.manualTempo -> "Manual tempo"
                timing.conflict == RhythmConflict.TEMPO -> "Tempo uncertain"
                timing.coasting -> "Coasting"
                beatReady -> "Beat locked"
                timing.bpm > 0f -> "Beat uncertain"
                else -> "Finding beat"
            }
            val bar=when {
                barReady && timing.manualBar -> "Bar aligned"
                barReady -> "Bar locked · ${timing.beatsPerBar} beats"
                timing.conflict == RhythmConflict.BAR -> "Bar uncertain"
                else -> "Finding bar"
            }
            val help=when {
                !running -> "Connect audio to find beats and bars."
                !fresh -> "Audio analysis stopped updating. Reconnect audio."
                !audible -> "No sound received. Play music or try another input."
                timing.conflict == RhythmConflict.TEMPO -> "Tempo is ambiguous. Try Tap tempo or ½ / 2× BPM."
                timing.coasting -> "Beat evidence faded. Waiting for the next clear beat."
                !beatReady -> "Sound received; still finding a steady beat."
                !barReady -> "Beat found; bar start uncertain. Tap Bar starts here on beat 1."
                timing.manualTempo || timing.manualBar -> "Manual timing active. Auto timing resumes detection."
                else -> "Following the music. Beat and bar timing established."
            }
            return RhythmFeedback(if (!running) "Input off" else if (!fresh) "No input updates" else if (audible) "Sound received" else "Input quiet",
                beat,bar,help,beatReady,barReady,
                if (audible) timing.confidence.coerceIn(0f,1f) else 0f,
                if (audible) timing.barConfidence.coerceIn(0f,1f) else 0f)
        }
    }
}
