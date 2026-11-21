package com.caleb.prism

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Load the shared BeatNet filter bank from its little-endian asset. */
fun BeatFeatures(filters: InputStream): BeatFeatures {
    val bytes = filters.use { it.readBytes() }
    require(bytes.size == 705 * 136 * 4)
    val weights = FloatArray(705 * 136)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(weights)
    return BeatFeatures(weights)
}
