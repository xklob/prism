package com.caleb.prism

import kotlin.math.*

/** Shared causal frontend. A null frame means the clock should receive the uninformative prior. */
internal class RhythmInput(private val features: BeatFeatures) {
    private val resampler = BeatResampler()
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
    fun process(pcm: ShortArray, firstSampleTime: Double, sensitivity: Float,
                resetModel: () -> Unit, beginFrame: () -> Unit = {}, emit: (Double, FloatArray?, Boolean) -> Unit) {
        val captureOrigin=firstSampleTime-inputSamples/48000.0
        // Gradually acquire the hardware clock without moving beat timestamps backwards.
        origin=if (origin.isNaN()) captureOrigin else origin+(captureOrigin-origin).coerceIn(-0.001,0.001)
        inputSamples+=pcm.size
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
        if (quietSeconds > 1.5 && !cleared) { resetModel(); features.reset(); cleared=true }
        if (audible) cleared=false
        resampler.push(pcm,gain) { sample ->
            hop[filled++]=sample
            if (filled == hop.size) {
                filled=0; hops++
                rolling.copyInto(rolling,0,441,rolling.size)
                hop.copyInto(rolling,rolling.size-hop.size)
                val time=origin+(hops*441-970)/22050.0
                if (hops >= 6) {
                    beginFrame()
                    val frame=if (quietSeconds > 1.5) null else features.extract(rolling)
                    if (frame != null) {
                        var level=0.0; var novelty=0.0
                        for (band in 0 until 136) { level+=frame[band]; novelty+=frame[136+band] }
                        if (novelty > 0.005 && novelty/max(level,0.001) > 0.003) lastChange=time
                    }
                    // Recurrent models can invent periodic activations on a sustained tone.
                    // Require actual spectral changes, while coasting through short musical gaps.
                    val changing=frame != null && time-lastChange < 2.0
                    if (!changing && dynamicInput) { resetModel() }
                    dynamicInput=changing
                    emit(time, if (changing) frame else null, audible)
                }
            }
        }
    }
}
