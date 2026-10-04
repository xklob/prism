package com.caleb.prism

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in replay of local 48 kHz mono PCM through the shipped Android analysis pipeline.
 * Recordings stay outside the repository and both APKs. Push input.pcm to external files/review.
 */
@RunWith(AndroidJUnit4::class)
class RecordedAudioProbe {
    @Test fun replayLocalRecording() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("recordedAudio") == "true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.getExternalFilesDir(null),"review").apply { mkdirs() }
        val bytes=File(directory,"input.pcm").readBytes()
        require(bytes.size>=48000*2*3 && bytes.size%2==0) { "Expected at least 3 seconds of mono 48 kHz signed 16-bit PCM" }
        val pcm=ShortArray(bytes.size/2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val sensitivity=args.getString("probeGain")?.toFloat() ?: 1.4f
        val output=File(directory,"recorded-audio.csv")
        var frames=0
        val costs=mutableListOf<Float>()
        output.bufferedWriter().use { writer ->
            writer.appendLine("time,bpm,position,confidence,bar_confidence,meter,bar_offset,bar_locked,coasting,conflict,beat,downbeat")
            File(directory,"recorded-models.csv").bufferedWriter().use { models ->
                models.appendLine("time,model3_beat,model3_down,model1_beat,model1_down")
                RhythmAnalyzer(context) { time,a,b -> models.appendLine(listOf(time-1000,a[0],a[1],b[0],b[1]).joinToString(",")) }.use { analyzer ->
                    for (start in 0..pcm.size-960 step 960) {
                        analyzer.process(pcm.copyOfRange(start,start+960),1000.0+start/48000.0,sensitivity,0) { s ->
                            assertTrue(s.confidence.isFinite() && s.bpm.isFinite())
                            writer.appendLine(listOf(s.timestamp-1000,s.bpm,s.position,s.confidence,s.barConfidence,
                                s.beatsPerBar,s.barOffset,s.barLocked,s.coasting,s.conflict,s.beatProbability,s.downbeatProbability).joinToString(","))
                            frames++
                        }
                        if (start>48000*2) costs.add(analyzer.inferenceMs)
                    }
                }
            }
        }
        assertTrue(frames>0)
        costs.sort()
        File(directory,"recorded-audio-cost.txt").writeText("frames=$frames\np95_ms=${costs[(costs.size*.95).toInt()]}\naverage_ms=${costs.average()}\n")
    }
}
