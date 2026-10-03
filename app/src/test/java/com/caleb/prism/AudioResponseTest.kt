package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test

class AudioResponseTest {
    private val signal = AudioLevels(bass = 0.75f, mid = 0.5f, high = 0.35f, energy = 0.7f, beat = 1f)
    private fun step(response: AudioResponse, enabled: Boolean = true, paused: Boolean = false,
                     amount: Float = 1f, bass: Float = 1f, mids: Float = 1f, treble: Float = 1f) =
        response.update(signal, enabled, paused, 1f / 60, amount, bass, mids, treble, 0.15f)

    @Test fun attackIsVisibleWithinTwoFramesAndStrengthHasHeadroom() {
        val response = AudioResponse()
        step(response)
        assertTrue(step(response).bass > 0.6f)
        repeat(10) { step(response, amount = 2f) }
        assertTrue("Increasing strength must not clip at one", response.motion.bass > 1.4f)
    }

    @Test fun offAndZeroStrengthRestoreAmbientEvenWhilePaused() {
        val response = AudioResponse()
        repeat(10) { step(response) }
        assertEquals(response.motion, step(response, paused = true))
        assertEquals(AudioMotion(), step(response, enabled = false, paused = true))
        repeat(10) { step(response) }
        assertEquals(AudioMotion(), step(response, amount = 0f, paused = true))
    }

    @Test fun bandGainsAreIndependentAndSilenceSettles() {
        val response = AudioResponse()
        repeat(15) { step(response, bass = 0f, treble = 0f) }
        assertEquals(0f, response.motion.bass, 0f)
        assertEquals(0f, response.motion.beat, 0f)
        assertEquals(0f, response.motion.treble, 0f)
        assertTrue(response.motion.mid > 0.45f)
        repeat(120) { response.update(AudioLevels(), true, false, 1f/60, 1f, 1f, 1f, 1f, 0.15f) }
        assertTrue(response.motion.energy < 0.00001f)
    }
}
