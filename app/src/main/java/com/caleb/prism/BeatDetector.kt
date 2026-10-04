package com.caleb.prism

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.*

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
    private val features=BeatFeatures(context.assets.open("rhythm/filters.f32"))
    private val resampler=BeatResampler()
    private val trackers=listOf(RhythmTracker(),RhythmTracker())
    private val recoveryTrackers=listOf(RhythmTracker(true),RhythmTracker(true))
    private val selector=RhythmSelector()
    private val recoverySelector=RhythmSelector()
    private val recovery=RhythmRecovery()
    val tracker: RhythmTracker get() = if (recovery.usingRecovery) recoveryTrackers[recoverySelector.selected] else trackers[selector.selected]
    fun automaticTiming() { (trackers+recoveryTrackers).forEach { it.automatic() } }
    fun setTempoHint(hint: TempoHint?) { (trackers+recoveryTrackers).forEach { it.setTempoHint(hint) } }
    fun discontinuity() { (trackers+recoveryTrackers).forEach { it.discontinuity() } }
    private val rolling=FloatArray(2293)
    private val hop=FloatArray(441)
    private var filled=0
    private var hops=0L
    private var origin=Double.NaN
    private var inputSamples=0L
    private var peakRms=0.05
    private var automaticGain=1.0
    private var quietSeconds=0.0
    private var cleared=false
    private var lastChange=Double.NEGATIVE_INFINITY
    private var dynamicInput=false
    var inferenceMs=0f; private set

    fun process(pcm: ShortArray, firstSampleTime: Double, sensitivity: Float, meter: Int, emit: (RhythmState) -> Unit) {
        val captureOrigin=firstSampleTime-inputSamples/48000.0
        // Gradually acquire the hardware clock without moving beat timestamps backwards.
        origin=if (origin.isNaN()) captureOrigin else origin+(captureOrigin-origin).coerceIn(-0.001,0.001)
        inputSamples+=pcm.size
        (trackers+recoveryTrackers).forEach { it.setMeter(meter) }
        var squares=0.0
        for (sample in pcm) { val value=sample/32768.0; squares+=value*value }
        val rms=sqrt(squares/pcm.size)
        val dt=pcm.size/48000.0
        peakRms=max(rms,peakRms*exp(-dt/3.0))
        // The model was trained on mastered music. Boost quiet inputs without attenuating normal playback.
        val targetGain=(0.28/max(0.018,peakRms)).coerceIn(1.0,8.0)
        // Large level increases need an immediate gain reduction. Smooth smaller changes in
        // both directions: block RMS flutter must not become artificial beat transients.
        automaticGain=if (targetGain<automaticGain*.8) targetGain
            else automaticGain+(targetGain-automaticGain)*(1-exp(-dt/.5))
        val gain=(automaticGain*sensitivity/1.4).coerceIn(0.25,12.0).toFloat()
        val audible=rms > 0.0012
        quietSeconds=if (audible) 0.0 else quietSeconds+dt
        if (quietSeconds > 1.5 && !cleared) { detector.reset(); generalDetector.reset(); features.reset(); cleared=true }
        if (audible) cleared=false
        resampler.push(pcm,gain) { sample ->
            hop[filled++]=sample
            if (filled == hop.size) {
                filled=0; hops++
                rolling.copyInto(rolling,0,441,rolling.size)
                hop.copyInto(rolling,rolling.size-hop.size)
                val time=origin+(hops*441-970)/22050.0
                if (hops >= 6) {
                    val started=System.nanoTime()
                    val frame=if (quietSeconds > 1.5) null else features.extract(rolling)
                    if (frame != null) {
                        var level=0.0; var novelty=0.0
                        for (band in 0 until 136) { level+=frame[band]; novelty+=frame[136+band] }
                        if (novelty > 0.005 && novelty/max(level,0.001) > 0.003) lastChange=time
                    }
                    // Recurrent models can invent periodic activations on a sustained tone.
                    // Require actual spectral changes, while coasting through short musical gaps.
                    val changing=frame != null && time-lastChange < 2.0
                    if (!changing && dynamicInput) { detector.reset(); generalDetector.reset() }
                    dynamicInput=changing
                    val probabilities=if (!changing) floatArrayOf(0f,0f,1f) else detector.predict(requireNotNull(frame))
                    val general=if (!changing) floatArrayOf(0f,0f,1f) else generalDetector.predict(requireNotNull(frame))
                    trace?.invoke(time,probabilities,general)
                    val states=listOf(trackers[0].observe(time,probabilities[0],probabilities[1],audible),
                        trackers[1].observe(time,general[0],general[1],audible))
                    val recovered=listOf(recoveryTrackers[0].observe(time,probabilities[0],probabilities[1],audible),
                        recoveryTrackers[1].observe(time,general[0],general[1],audible))
                    inferenceMs=(System.nanoTime()-started)/1e6f
                    emit(recovery.choose(selector.choose(states,time),recoverySelector.choose(recovered,time),time))
                }
            }
        }
    }
    override fun close() { detector.close(); generalDetector.close() }
}
