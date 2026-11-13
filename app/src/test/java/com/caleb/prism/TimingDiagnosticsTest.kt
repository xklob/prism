package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TimingDiagnosticsTest {
    private fun sample(time: Double) = TimingSample(RhythmState(timestamp = time, bpm = 120f, position = time * 2,
        confidence = .9f, lastMatchedBeatTime = time - .02), PhraseState(bars = 8, bar = 2, confidence = .8f))
    @Test fun historyIsBoundedAndRejectsOutOfOrderSamples() {
        val history = TimingDiagnostics(8)
        for (i in 0..20) history.add(sample(i.toDouble()))
        history.add(sample(10.0)); history.add(sample(Double.NaN))
        assertEquals((13..20).map { it.toDouble() }, history.snapshot().map { it.rhythm.timestamp })
        val snapshot = history.snapshot()
        history.clear()
        assertTrue(history.snapshot().isEmpty()); assertEquals(8, snapshot.size)
    }
    @Test fun expiresHistoryOlderThanOneMinute() {
        val history = TimingDiagnostics()
        for (i in 0..80) history.add(sample(i.toDouble()))
        assertEquals(20.0, history.snapshot().first().rhythm.timestamp, 0.0)
    }
    @Test fun phaseErrorIsSignedAndStaleMatchesAreAbsent() {
        assertEquals(-20.0, TimingDiagnostics.phaseErrorMs(sample(10.0).rhythm)!!, .001)
        assertNull(TimingDiagnostics.phaseErrorMs(sample(10.0).rhythm.copy(lastMatchedBeatTime = 0.0)))
        assertNull(TimingDiagnostics.phaseErrorMs(RhythmState()))
    }
    @Test fun csvUsesRelativeTimesAndLocaleIndependentColumns() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val csv = TimingDiagnostics.csv(listOf(sample(90000.0), sample(90000.02)))
            val rows = csv.trim().lines().map { it.split(',') }
            assertEquals(3, rows.size); assertEquals(rows[0].size, rows[1].size); assertEquals(rows[0].size, rows[2].size)
            assertEquals("0.00000", rows[1][0]); assertEquals("0.02000", rows[2][0])
            assertFalse(csv.contains("title")); assertFalse(csv.contains("source"))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun disabledEffectsFadeWithoutAnAbruptToggle() {
        val off = PostEffects(enabled = false)
        val on = PostEffects(enabled = true, strength = .8f, bloom = 1f, blur = .5f)
        assertEquals(off, blendEffects(off, on, 0f)); assertEquals(on, blendEffects(off, on, 1f))
        val middle = blendEffects(off, on, .5f)
        assertTrue(middle.active); assertEquals(.4f, middle.bloom, .0001f); assertEquals(.2f, middle.blur, .0001f)
    }
    @Test fun looksKeepTheCurrentCaptureAndTiming() {
        val current = VisualSettings(source = AudioSource.SYSTEM, audioEnabled = false, sensitivity = 2f,
            beatsPerBar = 3, syncOffsetMs = 100f, targetFps = 30, adaptiveQuality = false, paused = true)
        val look = VisualSettings.defaults(Scene.KALEIDOSCOPE).copy(kaleidoscopeShape = 2, effects = PostEffects(enabled = true))
        val result = look.keepingSession(current)
        assertEquals(Scene.KALEIDOSCOPE, result.scene); assertEquals(2, result.kaleidoscopeShape); assertTrue(result.effects.enabled)
        assertEquals(current.source, result.source); assertFalse(result.audioEnabled); assertTrue(result.paused)
        assertEquals(3, result.beatsPerBar); assertEquals(100f, result.syncOffsetMs, 0f)
        assertEquals(30, result.targetFps); assertFalse(result.adaptiveQuality)
    }
}
