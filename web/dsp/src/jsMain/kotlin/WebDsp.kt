@file:OptIn(ExperimentalJsExport::class)
package com.caleb.prism

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val block = 960
private const val rate = 48000
private const val waveformBins = 1024

private fun asJs(r: RhythmState): dynamic {
    val result = js("({})")
    result.timestamp = r.timestamp
    result.bpm = r.bpm
    result.position = r.position
    result.locked = r.locked
    result.confidence = r.confidence
    result.barConfidence = r.barConfidence
    result.phaseConfidence = r.phaseConfidence
    result.beatsPerBar = r.beatsPerBar
    result.barOffset = r.barOffset
    result.barLocked = r.barLocked
    result.manualTempo = r.manualTempo
    result.manualBar = r.manualBar
    result.signalPresent = r.signalPresent
    result.lastBeatTime = r.lastBeatTime
    result.beatProbability = r.beatProbability
    result.downbeatProbability = r.downbeatProbability
    result.lastMatchedBeatTime = r.lastMatchedBeatTime
    result.coasting = r.coasting
    result.conflict = r.conflict.name
    result.tempoAssisted = r.tempoAssisted
    result.holding = r.holding
    result.guided = r.guided
    return result
}

private fun asJs(p: PhraseState): dynamic {
    val result = js("({})")
    result.bars = p.bars
    result.bar = p.bar
    result.confidence = p.confidence
    result.manual = p.manual
    result.guided = p.guided
    result.message = p.message
    return result
}

private fun asJs(l: AudioLevels): dynamic {
    val result = js("({})")
    result.bass = l.bass
    result.mid = l.mid
    result.high = l.high
    result.energy = l.energy
    result.bands = l.bands
    result.spectrum = l.spectrum
    return result
}

/** Runs in a dedicated worker. Only ONNX inference is asynchronous between prepare and finish. */
@JsExport
class WebDsp(filters: FloatArray, private val digital: Boolean, private val file: Boolean) {
    private val frontend = RhythmInput(BeatFeatures(filters))
    private val clock = RhythmClock()
    private val phraseTracker = PhraseTracker()
    private val spectrum = SpectrumAnalyzer(rate, 2048, block)
    private val playback = PlaybackAnalysis()
    private val history = TimingHistory()
    private val window = ShortArray(2048)
    private val mono = ShortArray(block)
    private val stereo = ShortArray(2 * block)
    private val frames = ArrayList<LocalFrame>()
    private val spectra = ArrayList<ByteArray>()
    private var map: LocalTimingMap? = null
    private var rhythm = RhythmState()
    private var phrase = PhraseState()
    private var levels = AudioLevels()
    private var peak = 0f
    private var peakWindow = 0f
    private var rms = 0f
    private var pendingTime = Double.NaN
    private var pendingAudible = false
    private var alignAt: Double? = null
    private var sampleCount = 0
    private var blockCount = 0
    private var meter = 0
    private var phraseBars = 16
    private var sensitivity = 1.4f
    private var revision = 0L
    private val cue = PhraseCue()
    private var armed = false
    private var cueOffset = 0f

    fun configure(meter: Int, phraseBars: Int, sensitivity: Float) {
        require(meter in listOf(0, 3, 4) && phraseBars in listOf(8, 16, 32) && sensitivity.isFinite())
        this.meter = meter
        this.phraseBars = phraseBars
        this.sensitivity = sensitivity.coerceIn(.4f, 4f)
        clock.setMeter(meter)
    }

    fun hint(bpm: Double, key: String) {
        clock.setTempoHint(if (bpm in 30.0..300.0 && key.isNotEmpty()) TempoHint(bpm, key) else null)
    }

    fun command(action: String, time: Double) {
        if (file || !time.isFinite()) return
        when (action) {
            "tap" -> clock.tracker.tap(time)
            "beat" -> clock.tracker.alignBeat(time)
            "bar" -> clock.tracker.alignBar(time)
            "half" -> clock.tracker.scaleTempo(.5, time)
            "double" -> clock.tracker.scaleTempo(2.0, time)
            "phrase" -> { clock.tracker.alignBar(time); alignAt = time }
            "auto" -> { clock.automaticTiming(time); phraseTracker.automatic() }
            "autoPhrase" -> phraseTracker.automatic()
            else -> return
        }
        revision++
    }

    fun armCue(enabled: Boolean, offsetMs: Float) {
        armed = enabled
        cueOffset = offsetMs
        cue.reset()
    }

    fun prepare(left: FloatArray, right: FloatArray?, firstSampleTime: Double): dynamic {
        require(left.size in 1..block && (right == null || right.size == left.size) && firstSampleTime.isFinite())
        val channels = if (right == null) 1 else 2
        val interleaved = if (channels == 1) mono else stereo
        var squares = 0.0
        peak = 0f
        for (i in 0 until block) {
            val l = if (i < left.size) left[i].coerceIn(-1f, 1f) else 0f
            val r = if (i < left.size && right != null) right[i].coerceIn(-1f, 1f) else l
            fun pcm(v: Float) = (v * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
            interleaved[i * channels] = pcm(l)
            if (right != null) interleaved[i * channels + 1] = pcm(r)
            peak = max(peak, max(abs(l), abs(r)))
            val mix = (l + r) * .5
            squares += mix * mix
        }
        if (digital) {
            playback.process(interleaved, channels, mono)
            rms = playback.rawRms
        } else {
            if (channels == 2) for (i in mono.indices) mono[i] = ((stereo[2 * i].toInt() + stereo[2 * i + 1]) / 2).toShort()
            rms = sqrt(squares / block).toFloat()
        }
        val result = js("({})")
        result.reset = false
        result.features = null
        pendingTime = Double.NaN
        frontend.process(mono, firstSampleTime, if (file) 1.4f else sensitivity, { result.reset = true }) { time, features, audible ->
            pendingTime = time
            pendingAudible = audible
            result.features = features
        }
        window.copyInto(window, 0, block)
        mono.copyInto(window, window.size - block)
        levels = spectrum.analyze(window, if (file) 1.4f else sensitivity)
        sampleCount += left.size
        blockCount++
        return result
    }

    fun finish(primary: FloatArray, general: FloatArray, now: Double, last: Boolean): dynamic {
        if (pendingTime.isFinite()) rhythm = clock.observe(pendingTime, primary, general, pendingAudible)
        phrase = phraseTracker.update(rhythm, levels, phraseBars)
        alignAt?.let {
            phraseTracker.align(rhythm, it)
            phrase = phraseTracker.update(rhythm, levels, phraseBars)
            alignAt = null
        }
        if (!file) return frame(now)
        peakWindow = max(peakWindow, peak)
        if (blockCount % 2 == 0 || last) {
            frames.add(LocalFrame(sampleCount.toDouble() / rate, rhythm, phrase, levels.bass, levels.mid,
                levels.high, levels.energy, rms, peakWindow))
            // Preserve the measured spectrum for seeking without retaining another decoded song.
            spectra.add(ByteArray(96) {
                val level = if (it < 32) levels.bands[it] else levels.spectrum[it - 32]
                (level.coerceIn(0f, 1f) * 255).roundToInt().toByte()
            })
            peakWindow = 0f
        }
        return null
    }

    fun finishFile(): dynamic {
        require(file && frames.isNotEmpty())
        val timing = LocalTimingMap(frames, sampleCount.toDouble() / rate).also { map = it }
        val result = js("({})")
        result.duration = timing.duration
        result.bpm = timing.bpm
        result.peaks = FloatArray(waveformBins) { bin ->
            val begin = bin * frames.size / waveformBins
            val end = ((bin + 1) * frames.size / waveformBins).coerceIn(begin + 1, frames.size)
            var value = 0f
            for (i in begin until end) value = max(value, frames[i].peak)
            value
        }
        result.markers = timing.markers.map {
            val marker = js("({})")
            marker.time = it.time
            marker.kind = it.kind
            marker
        }.toTypedArray()
        return result
    }

    fun sample(position: Double, now: Double): dynamic {
        require(position.isFinite() && now.isFinite())
        val grid = requireNotNull(map)
        val time = position.coerceIn(0.0, grid.duration)
        val timing = grid.sample(time)
        val index = frames.binarySearchBy(time) { it.time }.let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
        val f = frames[index]
        fun wall(stamp: Double) = if (stamp.isFinite()) now + stamp - time else stamp
        rhythm = timing.rhythm.copy(timestamp = now, lastBeatTime = wall(timing.rhythm.lastBeatTime),
            lastMatchedBeatTime = wall(timing.rhythm.lastMatchedBeatTime), signalPresent = timing.rhythm.signalPresent || f.rms > .0012f)
        phrase = timing.phrase
        val gain = sensitivity / 1.4f
        fun scaled(number: Float) = (number * gain).coerceIn(0f, 1f)
        val bins = spectra[index]
        fun bin(i: Int) = scaled((bins[i].toInt() and 255) / 255f)
        levels = AudioLevels(scaled(f.bass), scaled(f.mid), scaled(f.high), scaled(f.energy),
            FloatArray(32) { bin(it) }, FloatArray(64) { bin(it + 32) })
        rms = f.rms
        peak = f.peak
        return frame(now)
    }

    private fun frame(now: Double): dynamic {
        history.add(TimingSample(rhythm, phrase, levels.energy))
        val result = js("({})")
        result.rhythm = asJs(rhythm)
        result.phrase = asJs(phrase)
        result.levels = asJs(levels)
        result.rms = rms
        result.peak = peak
        result.cancelingStereo = digital && playback.cancelingStereo
        result.matchErrorMs = TimingHistory.phaseErrorMs(rhythm)
        val fired = armed && cue.update(rhythm, phrase, now, true, revision, cueOffset)
        result.cueFired = fired
        result.cueMessage = if (armed) cue.message else ""
        if (fired) armed = false
        return result
    }

    fun clearDiagnostics() = history.clear()

    fun diagnostics(): dynamic {
        val list = history.snapshot()
        val start = list.firstOrNull()?.rhythm?.timestamp ?: 0.0
        val result = js("({})")
        result.csv = TimingHistory.csv(list) { it.asDynamic().toFixed(5) as String }
        result.samples = list.map { sample ->
            val row = js("({})")
            val r = sample.rhythm
            row.seconds = r.timestamp - start
            row.bpm = r.bpm
            row.beatConfidence = r.confidence
            row.barConfidence = r.barConfidence
            row.phaseConfidence = r.phaseConfidence
            row.beatProbability = r.beatProbability
            row.downbeatProbability = r.downbeatProbability
            row.energy = sample.energy
            row.matchErrorMs = TimingHistory.phaseErrorMs(r)
            row
        }.toTypedArray()
        return result
    }
}
