package com.caleb.prism

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt

data class SavedRecording(val uri: Uri, val name: String)

/** One worker owns the codecs, muxer and pending MediaStore file. GL only uses [surface]. */
internal class SessionEncoder(
    private val context: Context, screenWidth: Int, screenHeight: Int, val channels: Int,
    private val ready: (SessionEncoder) -> Unit,
    private val failed: (String) -> Unit,
    private val completed: (SavedRecording?, String?) -> Unit
) : PcmSink {
    lateinit var surface: Surface; private set
    var width = 0; private set
    var height = 0; private set
    var originNs = 0L; private set
    @Volatile private var stopNs = 0L
    @Volatile private var failure: String? = null
    private val detached = CountDownLatch(1)
    private data class Input(val samples: ShortArray, val timeNs: Long)
    private val audioInput = ArrayBlockingQueue<Input>(100)
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var name = ""
    private var muxing = false
    private val tracks = intArrayOf(-1, -1)
    private val ended = booleanArrayOf(false, false)
    private val samplesWritten = intArrayOf(0, 0)
    private data class Encoded(val track: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)
    private val pending = ArrayDeque<Encoded>()
    private var pendingBytes = 0
    private val pcm = ArrayDeque<RecordingTimeline.Chunk>()

    init {
        thread(name = "Prism recording", isDaemon = true) { encode(screenWidth, screenHeight) }
    }

    override fun accept(samples: ShortArray, firstSampleNs: Long) {
        if (stopNs != 0L || failure != null) return
        if (!audioInput.offer(Input(samples.copyOf(), firstSampleNs))) {
            fail("Recording couldn't keep up with audio. The session was stopped.")
        }
    }

    fun finish(timeNs: Long = System.nanoTime()) {
        stopNs = timeNs
        detached.countDown()
    }

    fun fail(message: String) {
        if (failure == null) {
            failure = message
            failed(message)
        }
    }

    private fun configure(screenWidth: Int, screenHeight: Int) {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
            .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
        // Keep the display's aspect ratio and choose an encoder-supported size, up to 1920 px.
        outer@ for (edge in listOf(1920, 1280, 960, 640)) for (codec in codecs) {
            val caps = codec.getCapabilitiesForType(mime)
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) continue
            val sizes = caps.videoCapabilities ?: continue
            val scale = minOf(1.0, edge.toDouble() / maxOf(screenWidth, screenHeight))
            fun align(value: Int, alignment: Int) = ((value * scale / alignment).roundToInt() * alignment).coerceAtLeast(alignment)
            val w = align(screenWidth, sizes.widthAlignment)
            val h = align(screenHeight, sizes.heightAlignment)
            if (!sizes.areSizeAndRateSupported(w, h, 30.0)) continue
            var candidate: MediaCodec? = null
            var input: Surface? = null
            try {
                val format = MediaFormat.createVideoFormat(mime, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 5).coerceIn(2_000_000, 12_000_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                candidate = MediaCodec.createByCodecName(codec.name)
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                input = candidate.createInputSurface()
                candidate.start()
                width = w; height = h; surface = input; video = candidate
                break@outer
            } catch (_: Exception) {
                input?.release()
                runCatching { candidate?.release() }
            }
        }
        check(video != null) { "No compatible video encoder is available" }
        audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
            it.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000 * channels)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096 * channels)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
        name = "Prism_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.mp4"
        val resolver = context.contentResolver
        uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Prism")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        })) { "Couldn't create a video in Movies/Prism" }
        descriptor = checkNotNull(resolver.openFileDescriptor(uri!!, "rw"))
        muxer = MediaMuxer(descriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun encode(screenWidth: Int, screenHeight: Int) {
        var saved: SavedRecording? = null
        try {
            configure(screenWidth, screenHeight)
            originNs = System.nanoTime()
            val timeline = RecordingTimeline(channels, originNs)
            ready(this)
            var sentVideoEnd = false
            var sentAudioEnd = false
            var paddedEnd = false
            var finishDeadline = Long.MAX_VALUE
            while (!ended.all { it }) {
                if (stopNs != 0L && !sentVideoEnd) {
                    video!!.signalEndOfInputStream()
                    sentVideoEnd = true
                    finishDeadline = System.nanoTime() + 8_000_000_000L
                }
                if (pcm.isEmpty()) {
                    val input = audioInput.poll()
                    if (input != null) timeline.append(input.samples, input.timeNs, pcm::addLast)
                    else if (stopNs != 0L && !paddedEnd) {
                        timeline.finish(stopNs, pcm::addLast)
                        paddedEnd = true
                    }
                }
                if (!sentAudioEnd && (pcm.isNotEmpty() || paddedEnd)) {
                    val index = audio!!.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val buffer = audio!!.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.clear()
                        if (pcm.isEmpty()) {
                            audio!!.queueInputBuffer(index, 0, 0, timeline.frames * 1_000_000 / 48000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sentAudioEnd = true
                        } else {
                            val chunk = pcm.removeFirst()
                            val count = minOf(chunk.samples.size, buffer.capacity() / (2 * channels) * channels)
                            check(count > 0) { "Audio encoder input is too small" }
                            buffer.asShortBuffer().put(chunk.samples, 0, count)
                            audio!!.queueInputBuffer(index, 0, count * 2, chunk.frame * 1_000_000 / 48000, 0)
                            if (count < chunk.samples.size) pcm.addFirst(RecordingTimeline.Chunk(chunk.samples.copyOfRange(count, chunk.samples.size), chunk.frame + count / channels))
                        }
                    }
                }
                val output = drain(video!!, 0) or drain(audio!!, 1)
                check(System.nanoTime() < finishDeadline) { "The video encoder didn't finish" }
                if (!output) Thread.sleep(3)
            }
            check(muxing && samplesWritten.all { it > 0 }) { "Recording was too short to save. Try recording for a few seconds." }
            muxer!!.stop()
            muxing = false
            muxer!!.release(); muxer = null
            descriptor!!.close(); descriptor = null
            check(context.contentResolver.update(uri!!, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1)
            saved = SavedRecording(uri!!, name)
        } catch (error: Exception) {
            Log.e("PrismRecording", "Recording failed", error)
            fail(error.message ?: "Couldn't save this recording. Check free storage and try again.")
            // The UI detaches EGL before codecs/surfaces can be released.
            detached.await(5, TimeUnit.SECONDS)
        } finally {
            runCatching { video?.stop() }; runCatching { video?.release() }
            runCatching { audio?.stop() }; runCatching { audio?.release() }
            if (::surface.isInitialized) surface.release()
            runCatching { if (muxing) muxer?.stop() }; runCatching { muxer?.release() }
            runCatching { descriptor?.close() }
            if (saved == null) uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            completed(saved, failure)
        }
    }

    private fun drain(codec: MediaCodec, track: Int): Boolean {
        if (ended[track]) return false
        val info = MediaCodec.BufferInfo()
        var progress = false
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return progress
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(tracks[track] == -1)
                    tracks[track] = muxer!!.addTrack(codec.outputFormat)
                    if (tracks.all { it >= 0 }) {
                        muxer!!.start(); muxing = true
                        while (pending.isNotEmpty()) write(pending.removeFirst())
                        pendingBytes = 0
                    }
                    progress = true
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = codec.getOutputBuffer(index)!!
                            output.position(info.offset); output.limit(info.offset + info.size)
                            val copy = ByteBuffer.allocateDirect(info.size).put(output).apply { flip() }
                            val copiedInfo = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                            val encoded = Encoded(track, copy, copiedInfo)
                            if (muxing) write(encoded) else {
                                pending.add(encoded); pendingBytes += info.size
                                check(pendingBytes < 8_000_000) { "Audio and video couldn't start together" }
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended[track] = true
                    } finally { codec.releaseOutputBuffer(index, false) }
                    progress = true
                    if (ended[track]) return true
                }
            }
        }
    }

    private fun write(sample: Encoded) {
        muxer!!.writeSampleData(tracks[sample.track], sample.data, sample.info)
        samplesWritten[sample.track]++
    }
}
