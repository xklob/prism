package com.caleb.prism

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** BeatNet inference, with explicit recurrent state owned by one capture session. */
class BeatDetector(context: Context, asset: String = "beatnet.onnx") : AutoCloseable {
    private val environment=OrtEnvironment.getEnvironment()
    private val session=OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        options.setInterOpNumThreads(1)
        environment.createSession(context.assets.open("rhythm/$asset").use { it.readBytes() },options)
    }
    private var hidden=FloatArray(300)
    private var cell=FloatArray(300)
    fun reset() { hidden.fill(0f); cell.fill(0f) }
    fun predict(features: FloatArray): FloatArray {
        require(features.size == 272)
        OnnxTensor.createTensor(environment,FloatBuffer.wrap(features),longArrayOf(1,1,272)).use { input ->
            OnnxTensor.createTensor(environment,FloatBuffer.wrap(hidden),longArrayOf(2,1,150)).use { h ->
                OnnxTensor.createTensor(environment,FloatBuffer.wrap(cell),longArrayOf(2,1,150)).use { c ->
                    session.run(mapOf("features" to input,"hidden" to h,"cell" to c)).use { result ->
                        val output=FloatArray(3)
                        (result[0] as OnnxTensor).floatBuffer.get(output)
                        (result[1] as OnnxTensor).floatBuffer.get(hidden)
                        (result[2] as OnnxTensor).floatBuffer.get(cell)
                        check(output.all { it.isFinite() && it in 0f..1f })
                        return output
                    }
                }
            }
        }
    }
    override fun close() { session.close() }
}

/** Converts capture PCM into timestamped musical timing; never runs on the UI or GL thread. */
class RhythmAnalyzer(context: Context, private val trace: ((Double,FloatArray,FloatArray)->Unit)?=null) : AutoCloseable {
    private val detector=BeatDetector(context)
    private val generalDetector=BeatDetector(context,"beatnet-general.onnx")
    private val frontend = RhythmInput(BeatFeatures(context.assets.open("rhythm/filters.f32")))
    private val clock = RhythmClock()
    val tracker: RhythmTracker get() = clock.tracker
    var inferenceMs = 0f; private set

    fun automaticTiming(time: Double) = clock.automaticTiming(time)
    fun setTempoHint(hint: TempoHint?) = clock.setTempoHint(hint)
    fun discontinuity() = clock.discontinuity()

    fun process(pcm: ShortArray, firstSampleTime: Double, sensitivity: Float, meter: Int, emit: (RhythmState) -> Unit) {
        clock.setMeter(meter)
        var start = 0L
        frontend.process(pcm, firstSampleTime, sensitivity, { detector.reset(); generalDetector.reset() },
            { start = System.nanoTime() }) { time, frame, audible ->
            val probabilities = frame?.let(detector::predict) ?: floatArrayOf(0f, 0f, 1f)
            val general = frame?.let(generalDetector::predict) ?: floatArrayOf(0f, 0f, 1f)
            trace?.invoke(time, probabilities, general)
            val state = clock.observe(time, probabilities, general, audible)
            inferenceMs = (System.nanoTime() - start) / 1e6f
            emit(state)
        }
    }

    override fun close() { detector.close(); generalDetector.close() }
}
