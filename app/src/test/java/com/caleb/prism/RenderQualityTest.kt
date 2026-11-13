package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test

class RenderQualityTest {
    private fun frames(q: RenderQuality, seconds: Double, fps: Int, target: Int = 60, thermal: Int = 0) {
        repeat((seconds * fps).toInt()) { q.sample(1.0 / fps, target, true, thermal, false) }
    }
    @Test fun sustainedOverloadReducesCostAndRecoveryIsSlow() {
        val q = RenderQuality()
        frames(q, 2.0, 25); assertEquals(1f, q.scale, 0f)
        frames(q, 2.0, 25); assertTrue(q.scale < 1f)
        val reduced = q.scale
        frames(q, 7.0, 60); assertTrue(q.scale <= reduced)
        frames(q, 30.0, 60); assertEquals(1f, q.scale, 0f)
    }
    @Test fun thirtyFpsTargetDoesNotMistakePacingForOverload() {
        val q = RenderQuality()
        frames(q, 30.0, 30, target = 30)
        assertEquals(1f, q.scale, 0f)
        repeat(20) { q.sample(8.0, 30, true, 0, false) }
        repeat(100) { q.sample(.1, 30, true, 0, true) }
        assertEquals("Background gaps and paused frames must not lower quality", 1f, q.scale, 0f)
    }
    @Test fun thermalLimitsApplyImmediatelyAndDoNotOscillate() {
        val q = RenderQuality()
        q.sample(.016, 60, true, 3, false)
        assertTrue(q.scale <= .55f)
        frames(q, 30.0, 60, thermal = 3); assertTrue(q.scale <= .55f)
        frames(q, 8.0, 60); assertTrue(q.scale <= .55f)
        frames(q, 60.0, 60); assertEquals(1f, q.scale, 0f)
        q.sample(.016, 60, true, 4, false); assertEquals(.45f, q.scale, 0f)
        q.sample(.016, 60, false, 4, false); assertEquals(1f, q.scale, 0f)
    }
}
