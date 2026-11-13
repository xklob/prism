package com.caleb.prism

import android.content.Context
import android.media.*
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

internal data class AnalyzedTrack(val pcm: File, val title: String, val channels: Int, val duration: Double, val map: LocalTimingMap)

/** Fully decodes and analyzes before playback. Partial/canceled exports are always removed. */
internal object LocalFileAnalyzer {
    suspend fun analyze(context: Context, uri: Uri, title: String, meter: Int, phraseBars: Int,
                        progress: (String, Float) -> Unit): AnalyzedTrack {
        val directory = File(context.cacheDir, "local-audio").apply { mkdirs() }
        val file = File.createTempFile("track-", ".pcm", directory)
        try {
            val coroutine = currentCoroutineContext()
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            var channels = 0
            var lastProgress = System.nanoTime()
            try {
                extractor.setDataSource(context, uri, null)
                val index = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("This file has no supported audio track.")
                val format = extractor.getTrackFormat(index)
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
                require(durationUs <= 3_600_000_000L) { "Choose a track shorter than one hour." }
                extractor.selectTrack(index)
                val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
                decoder = codec
                codec.configure(format, null, null, 0); codec.start()
                val info = MediaCodec.BufferInfo()
                var ended = false; var inputEnded = false
                var resampler: FilePcmResampler? = null
                var rate = 0; var written = 0L
                val allowance = minOf(750_000_000L, (directory.usableSpace - 100_000_000).coerceAtLeast(0))
                var lastOutput = System.nanoTime()
                BufferedOutputStream(FileOutputStream(file), 128 * 1024).use { out ->
                    fun append(pcm: ShortArray) {
                        coroutine.ensureActive()
                        written += pcm.size * 2L
                        check(written <= allowance) { "Not enough temporary storage for this track. Choose a shorter file or free space." }
                        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        bytes.asShortBuffer().put(pcm); out.write(bytes.array())
                    }
                    while (!ended) {
                        coroutine.ensureActive()
                        if (!inputEnded) {
                            val input = codec.dequeueInputBuffer(10_000)
                            if (input >= 0) {
                                val buffer = checkNotNull(codec.getInputBuffer(input)); buffer.clear()
                                val size = extractor.readSampleData(buffer, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                                } else {
                                    check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Protected audio files are not supported." }
                                    codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0); extractor.advance()
                                }
                            }
                        }
                        when (val output = codec.dequeueOutputBuffer(info, 10_000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val decoded = codec.outputFormat
                                val nextRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                val nextChannels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                require(nextChannels in 1..2) { "Choose a mono or stereo audio file." }
                                require(rate == 0 || (rate == nextRate && channels == nextChannels)) { "Changing audio formats within a file are not supported." }
                                rate = nextRate; channels = nextChannels
                                if (resampler == null) resampler = FilePcmResampler(rate, channels, ::append)
                            }
                            else -> if (output >= 0) {
                                try {
                                    if (info.size > 0) {
                                        lastOutput = System.nanoTime()
                                        val decoded = codec.outputFormat
                                        if (resampler == null) {
                                            rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                            resampler = FilePcmResampler(rate, channels, ::append)
                                        }
                                        val buffer = checkNotNull(codec.getOutputBuffer(output)).duplicate().order(ByteOrder.nativeOrder())
                                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                        val encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                                        val samples = when (encoding) {
                                            AudioFormat.ENCODING_PCM_16BIT -> ShortArray(info.size / 2).also { buffer.asShortBuffer().get(it) }
                                            AudioFormat.ENCODING_PCM_FLOAT -> ShortArray(info.size / 4) { (buffer.float.coerceIn(-1f, 1f) * 32767).roundToInt().toShort() }
                                            else -> error("The decoder returned an unsupported audio format.")
                                        }
                                        resampler.push(samples)
                                        if (System.nanoTime() - lastProgress > 100_000_000L) {
                                            progress("Decoding track", if (durationUs > 0) (.25f * info.presentationTimeUs / durationUs).coerceIn(0f, .25f) else .1f)
                                            lastProgress = System.nanoTime()
                                        }
                                    }
                                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                } finally { codec.releaseOutputBuffer(output, false) }
                            }
                        }
                        check(System.nanoTime() - lastOutput < 20_000_000_000L) { "The audio decoder stopped responding." }
                    }
                    resampler?.finish()
                }
            } finally { runCatching { decoder?.stop() }; decoder?.release(); extractor.release() }
            check(channels in 1..2 && file.length() >= channels * 960 * 2) { "This audio file is empty or too short." }
            val duration = file.length() / (48000.0 * channels * 2)
            check(duration <= 3600) { "Choose a track shorter than one hour." }
            val frames = ArrayList<LocalFrame>()
            RhythmAnalyzer(context).use { timing ->
                val spectrum = SpectrumAnalyzer(48000, 2048, 960)
                val phrase = PhraseTracker()
                val analysis = PlaybackAnalysis()
                val window = ShortArray(2048)
                val mono = ShortArray(960)
                val bytes = ByteArray(960 * channels * 2)
                var sample = 0L
                var rhythm = RhythmState()
                var peak = 0f
                BufferedInputStream(FileInputStream(file), 128 * 1024).use { input ->
                    while (true) {
                        coroutine.ensureActive()
                        var count = 0
                        while (count < bytes.size) { val read = input.read(bytes, count, bytes.size - count); if (read < 0) break; count += read }
                        if (count == 0) break
                        bytes.fill(0, count, bytes.size)
                        val pcm = ShortArray(960 * channels)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                        analysis.process(pcm, channels, mono)
                        timing.process(mono, sample / 48000.0, 1.4f, meter) { rhythm = it }
                        window.copyInto(window, 0, 960, window.size); mono.copyInto(window, window.size - 960)
                        val levels = spectrum.analyze(window)
                        val p = phrase.update(rhythm, levels, phraseBars)
                        peak = maxOf(peak, pcm.maxOf { abs(it.toInt()) } / 32768f)
                        if (sample % 1920 == 960L || count < bytes.size) {
                            frames.add(LocalFrame((sample + 960) / 48000.0, rhythm, p,
                                levels.bass, levels.mid, levels.high, levels.energy, analysis.rawRms, peak))
                            peak = 0f
                        }
                        sample += 960
                        if (System.nanoTime() - lastProgress > 150_000_000L) {
                            progress("Analyzing beat, bar and phrase", .25f + .7f * (sample / 48000.0 / duration).toFloat().coerceIn(0f, 1f))
                            lastProgress = System.nanoTime()
                        }
                    }
                }
            }
            coroutine.ensureActive(); progress("Building timing map", .98f)
            val map = LocalTimingMap(frames, duration)
            // Resolve lazy lookups off the playback/UI threads.
            map.sample(0.0); map.markers
            return AnalyzedTrack(file, title, channels, duration, map)
        } catch (error: Throwable) { file.delete(); throw error }
    }
}
