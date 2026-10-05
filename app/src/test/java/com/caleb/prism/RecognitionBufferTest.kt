package com.caleb.prism

import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecognitionBufferTest {
    @Test fun clipHasCorrectDurationStereoDownmixAndWavHeader() {
        val buffer = RecognitionBuffer()
        val stereo = ShortArray(1920) { if (it % 2 == 0) 1000 else 3000 }
        val origin = 100_000_000_000L
        repeat(600) { buffer.append(stereo, origin + it * 20_000_000L, 2) }
        val clip = buffer.clip(origin + 12_000_000_000L)!!
        assertEquals(160000, clip.pcm.size)
        assertTrue(clip.pcm.all { it == 2000.toShort() })
        assertEquals(origin + 2_000_000_000L, clip.startNs)
        assertEquals(origin + 12_000_000_000L, clip.endNs)
        val wav = ByteBuffer.wrap(clip.wav()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, wav.getInt(24)); assertEquals(320000, wav.getInt(40)); assertEquals(2000.toShort(), wav.getShort(44))
    }

    @Test fun neverUploadsShortStaleSilentOrDiscontinuousAudio() {
        val buffer = RecognitionBuffer()
        val origin = 100_000_000_000L
        repeat(399) { buffer.append(ShortArray(960) { 1000 }, origin + it * 20_000_000L, 1) }
        assertNull(buffer.clip(origin + 7_980_000_000L))
        buffer.append(ShortArray(960) { 1000 }, origin + 7_980_000_000L, 1)
        assertNotNull(buffer.clip(origin + 8_000_000_000L))
        assertNull(buffer.clip(origin + 10_000_000_000L))
        buffer.append(ShortArray(960) { 1000 }, origin + 11_000_000_000L, 1)
        assertNull(buffer.clip(origin + 11_020_000_000L))
        buffer.clear()
        repeat(500) { buffer.append(ShortArray(960), origin + it * 20_000_000L, 1) }
        assertNull(buffer.clip(origin + 10_000_000_000L))
    }
}
