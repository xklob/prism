package com.caleb.prism

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Exact 1,411-point DFT via Bluestein, with reusable 4,096-point radix-2 buffers. */
internal class BeatFft {
    private val n = 1411
    private val size = 4096
    private val cosine = DoubleArray(n) { cos(PI * (it.toLong() * it % (2*n)) / n) }
    private val sine = DoubleArray(n) { sin(PI * (it.toLong() * it % (2*n)) / n) }
    private val kernelR = DoubleArray(size)
    private val kernelI = DoubleArray(size)
    private val real = DoubleArray(size)
    private val imaginary = DoubleArray(size)
    init {
        for (i in 0 until n) {
            kernelR[i] = cosine[i]; kernelI[i] = sine[i]
            if (i > 0) { kernelR[size-i] = cosine[i]; kernelI[size-i] = sine[i] }
        }
        fft(kernelR, kernelI)
    }
    fun magnitude(samples: FloatArray, offset: Int): DoubleArray {
        real.fill(0.0); imaginary.fill(0.0)
        for (i in 0 until n) {
            val x = samples[offset+i] * (0.5 - 0.5*cos(2*PI*i/(n-1)))
            real[i] = x*cosine[i]; imaginary[i] = -x*sine[i]
        }
        fft(real, imaginary)
        for (i in 0 until size) {
            val r = real[i]*kernelR[i] - imaginary[i]*kernelI[i]
            imaginary[i] = -(real[i]*kernelI[i] + imaginary[i]*kernelR[i])
            real[i] = r
        }
        fft(real, imaginary)
        // Chirp rotation has unit magnitude, so there is no need to rotate back.
        return DoubleArray(705) { hypot(real[it], imaginary[it])/size }
    }
    private fun fft(r: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var v=r[i]; r[i]=r[j]; r[j]=v; v=im[i]; im[i]=im[j]; im[j]=v }
        }
        var length = 2
        while (length <= size) {
            val sr=cos(-2*PI/length); val si=sin(-2*PI/length)
            for (start in 0 until size step length) {
                var wr=1.0; var wi=0.0
                for (k in 0 until length/2) {
                    val a=start+k; val b=a+length/2
                    val br=r[b]*wr-im[b]*wi; val bi=r[b]*wi+im[b]*wr
                    r[b]=r[a]-br; im[b]=im[a]-bi; r[a]+=br; im[a]+=bi
                    val next=wr*sr-wi*si; wi=wr*si+wi*sr; wr=next
                }
            }
            length = length shl 1
        }
    }
}

/** BeatNet's log-filterbank + positive differences, matching its published streaming frontend. */
class BeatFeatures(filters: InputStream) {
    private val fft = BeatFft()
    private val weights: Array<List<Pair<Int, Float>>>
    private var previous: FloatArray? = null
    init {
        val bytes=filters.use { it.readBytes() }
        require(bytes.size == 705*136*4)
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val columns=Array(136) { mutableListOf<Pair<Int,Float>>() }
        for (bin in 0 until 705) for (band in 0 until 136) {
            val weight=buffer.float
            if (weight != 0f) columns[band].add(bin to weight)
        }
        weights=Array(136) { columns[it].toList() }
    }
    private fun spectrum(window: FloatArray, offset: Int): FloatArray {
        val magnitude=fft.magnitude(window, offset)
        return FloatArray(136) { band ->
            var sum=0.0
            for ((bin,weight) in weights[band]) sum+=magnitude[bin]*weight
            log10(1.0+sum).toFloat()
        }
    }
    fun reset() { previous=null }
    fun extract(window: FloatArray): FloatArray {
        require(window.size == 2293)
        val current=spectrum(window, 618)
        val old=previous ?: spectrum(window, 177)
        previous=current
        return FloatArray(272) { if (it < 136) current[it] else max(0f,current[it-136]-old[it-136]) }
    }
}

/** Fixed-ratio low-pass resampler. State and sample positions survive arbitrary input chunk boundaries. */
class BeatResampler {
    private val ring=FloatArray(128)
    private var written=0L
    private var output=0L
    private val radius=22
    private fun bessel0(x: Double): Double {
        var sum=1.0; var term=1.0
        for (k in 1..20) { term*=x*x/(4*k*k); sum+=term }
        return sum
    }
    private val kernels=Array(147) { phase ->
        val fraction=phase/147.0
        val taps=DoubleArray(radius*2) { i ->
            val x=i-radius+1-fraction
            // Match the reference polyphase passband and Kaiser window. Cutting at 9.6 kHz
            // removes several trained feature bands and changes live-music predictions.
            val cutoff=22050.0/48000/2
            val width=3200.0/147
            val sinc=if (abs(x) < 1e-12) 2*cutoff else sin(2*PI*cutoff*x)/(PI*x)
            if (abs(x)>width) 0.0 else sinc*bessel0(5*sqrt(1-(x/width).pow(2)))/bessel0(5.0)
        }
        val total=taps.sum()
        FloatArray(taps.size) { (taps[it]/total).toFloat() }
    }
    fun push(samples: ShortArray, gain: Float = 1f, emit: (Float) -> Unit) {
        for (sample in samples) {
            ring[(written%ring.size).toInt()]=(sample/32768f*gain).coerceIn(-1f,1f)
            written++
            while (true) {
                val numerator=output*320
                val center=numerator/147
                if (center+radius >= written) break
                val kernel=kernels[(numerator%147).toInt()]
                var value=0f
                for (i in kernel.indices) {
                    val index=center+i-radius+1
                    if (index >= 0) value+=ring[(index%ring.size).toInt()]*kernel[i]
                }
                emit(value)
                output++
            }
        }
    }
}
