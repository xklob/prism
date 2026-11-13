package com.caleb.prism

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Conditions the analysis copy only. Recordings keep the original stereo PCM and volume. */
internal class PlaybackAnalysis {
    private var peakRms = 0.0
    var rawRms = 0f; private set
    var cancelingStereo = false; private set

    fun process(input: ShortArray, channels: Int, output: ShortArray) {
        require(channels in 1..2 && input.size == output.size * channels)
        var leftPower = 0.0; var rightPower = 0.0; var mixedPower = 0.0
        for (i in output.indices) {
            val left = input[i * channels].toDouble()
            val right = if (channels == 2) input[i * channels + 1].toDouble() else left
            leftPower += left * left; rightPower += right * right
            val mixed = (left + right) * .5
            mixedPower += mixed * mixed
        }
        val strongest = max(leftPower, rightPower)
        cancelingStereo = channels == 2 && strongest > 0 && mixedPower < strongest * .10
        val selected = if (rightPower > leftPower) 1 else 0
        val power = if (cancelingStereo) strongest else mixedPower
        rawRms = (sqrt(power / output.size) / 32768.0).toFloat()
        // Digital playback can arrive below the analyzer's microphone noise gate.
        // A slow peak envelope preserves musical dynamics, including drumless bridges.
        peakRms = max(rawRms.toDouble(), peakRms * exp(-output.size / 48000.0 / 2.5))
        val gain = if (rawRms < .00006f) 1.0 else (.08 / max(.00006, peakRms)).coerceIn(1.0, 128.0)
        for (i in output.indices) {
            val value = if (channels == 1) input[i].toInt()
                else if (cancelingStereo) input[i * 2 + selected].toInt()
                else (input[i * 2].toInt() + input[i * 2 + 1].toInt()) / 2
            output[i] = (value * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
