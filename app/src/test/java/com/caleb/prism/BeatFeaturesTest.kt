package com.caleb.prism

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class BeatFeaturesTest {
    private fun fixture(name: String): FloatArray {
        val bytes=requireNotNull(javaClass.getResourceAsStream("/rhythm/$name.f32")).use { it.readBytes() }
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
    @Test fun featuresMatchPublishedFrontend() {
        val features=BeatFeatures(File("src/main/assets/rhythm/filters.f32").inputStream()).extract(fixture("window"))
        val expected=fixture("features")
        assertEquals(272, features.size)
        for (i in features.indices) assertEquals("Feature $i",expected[i],features[i],0.00002f)
    }
    @Test fun resamplingPreservesChunkBoundariesAndRejectsAliasedTreble() {
        val pcm=ShortArray(48000) { (sin(2*PI*1000*it/48000)*10000).toInt().toShort() }
        fun convert(block: Int, samples: ShortArray): FloatArray {
            val resampler=BeatResampler(); val result=mutableListOf<Float>()
            for (offset in samples.indices step block) resampler.push(samples.copyOfRange(offset,min(offset+block,samples.size))) { result.add(it) }
            return result.toFloatArray()
        }
        val a=convert(960,pcm); val b=convert(137,pcm)
        assertArrayEquals(a,b,0f)
        assertTrue(a.size in 22035..22050)
        val high=convert(960,ShortArray(48000) { (sin(2*PI*16000*it/48000)*10000).toInt().toShort() })
        fun power(x: FloatArray)=x.drop(100).sumOf { it.toDouble()*it }/(x.size-100)
        assertTrue("Passband preserved",power(a)>0.035)
        assertTrue("Out-of-band content must not alias into beat features",power(high)<power(a)*0.001)
    }
}
