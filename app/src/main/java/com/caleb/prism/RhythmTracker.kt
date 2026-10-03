package com.caleb.prism

import kotlin.math.*

data class RhythmState(
    val timestamp: Double = 0.0,
    val bpm: Float = 0f,
    val position: Double = 0.0,
    val confidence: Float = 0f,
    val barConfidence: Float = 0f,
    val beatsPerBar: Int = 4,
    val barOffset: Int = 0,
    val barLocked: Boolean = false,
    val manualTempo: Boolean = false,
    val manualBar: Boolean = false,
    val signalPresent: Boolean = false,
    val lastBeatTime: Double = Double.NEGATIVE_INFINITY,
    val beatProbability: Float = 0f,
    val downbeatProbability: Float = 0f
) {
    val locked: Boolean get() = bpm > 0f && confidence >= 0.5f
    fun positionAt(time: Double) = position + (time-timestamp).coerceIn(-0.5,0.5)*bpm/60.0
    fun beatInBar(time: Double): Int = Math.floorMod(floor(positionAt(time)+1e-7).toInt()-barOffset, beatsPerBar)+1
}

/** Causal tempo/phase estimation from learned beat probabilities, with independent bar hypotheses. */
class RhythmTracker {
    private data class Event(val time: Double, val beat: Double, val downbeat: Double) {
        val weight: Double get() = beat+downbeat
    }
    private data class BarEvidence(val index: Int, val probability: Double, val weight: Double)
    private val events=ArrayDeque<Event>()
    private val barEvidence=linkedMapOf<Int,BarEvidence>()
    private val taps=ArrayDeque<Double>()
    private var prior=Event(0.0,0.0,0.0)
    private var beforePrior=0.0
    private var lastBeat=Double.NEGATIVE_INFINITY
    private var lastAudible=Double.NEGATIVE_INFINITY
    private var lastFit=Double.NEGATIVE_INFINITY
    private var lastTime=0.0
    private var period=0.5
    private var origin=0.0
    private var fitted=false
    private var confidence=0.0
    private var barConfidence=0.0
    private var meter=4
    private var barOffset=0
    private var barLocked=false
    private var manualTempo=false
    private var manualBar=false
    private var forcedMeter=0

    fun setMeter(beats: Int) {
        require(beats in listOf(0,3,4))
        if (forcedMeter == beats) return
        forcedMeter=beats
        if (beats != 0) meter=beats
        barEvidence.clear(); barLocked=false; manualBar=false; barConfidence=0.0
    }

    fun observe(time: Double, beat: Float, downbeat: Float, audible: Boolean): RhythmState {
        require(time.isFinite() && beat.isFinite() && downbeat.isFinite())
        require(time >= lastTime)
        lastTime=time
        if (audible) lastAudible=time
        val current=Event(time, beat.toDouble().coerceIn(0.0,1.0), downbeat.toDouble().coerceIn(0.0,1.0))
        if (prior.weight >= 0.18 && prior.weight > beforePrior && prior.weight >= current.weight &&
            prior.time-lastBeat > 0.20 && time-lastAudible < 0.25) {
            accept(prior)
        }
        beforePrior=prior.weight; prior=current
        if (time-lastAudible > 1.5 && events.isNotEmpty()) {
            events.clear(); barEvidence.clear(); confidence=0.0; barConfidence=0.0
            if (!manualTempo) fitted=false
            if (!manualBar) barLocked=false
        }
        return state(time, beat, downbeat)
    }

    private fun accept(event: Event) {
        lastBeat=event.time
        events.addLast(event)
        while (events.isNotEmpty() && event.time-events.first().time > 10.0) events.removeFirst()
        if (!manualTempo && event.time-lastFit > 0.30 && events.size >= 5) {
            fit(event.time); lastFit=event.time
        }
        if (fitted) {
            val position=(event.time-origin)/period
            val error=round(position)-position
            if (!manualTempo && abs(error) < 0.23) origin-=error*period*0.22
            if (abs(error) < 0.23 && confidence >= 0.42) {
                val index=round(position).toInt()
                val evidence=BarEvidence(index, (event.downbeat/event.weight).coerceIn(0.025,0.975),event.weight.coerceAtMost(1.0))
                if ((barEvidence[index]?.weight ?: -1.0) < evidence.weight) barEvidence[index]=evidence
                barEvidence.keys.removeAll { index-it >= 32 || it > index+1 }
                estimateBar(index)
            }
        }
    }

    private fun fit(now: Double) {
        val span=now-events.first().time
        if (span < 2.0) return
        var bestScore=-1.0; var bestBpm=120.0; var bestAngle=0.0
        val scores=mutableListOf<Pair<Double,Double>>()
        val downbeats=events.filter { it.downbeat > 0.20 && it.downbeat/it.weight > 0.55 }
        for (candidate in 120..400) {
            val bpm=candidate/2.0 // 60–200 BPM; eighth notes are not automatically called beats.
            var real=0.0; var imaginary=0.0; var total=0.0
            for (event in events) {
                val weight=event.weight.pow(1.5)*exp(-(now-event.time)/7.0)
                val phase=(event.time-now)*bpm/60*2*PI
                real+=cos(phase)*weight; imaginary+=sin(phase)*weight; total+=weight
            }
            val coherence=hypot(real,imaginary)/max(total,1e-8)
            val coverage=(events.size/(span*bpm/60+1)).coerceAtMost(1.0)
            val continuity=if (fitted) 0.035*exp(-abs(ln(bpm*period/60))*8) else 0.0
            // Downbeat spacing resolves common half/double-tempo ambiguity jointly with meter.
            var barCoherence=0.0
            if (downbeats.size >= 3) for (beats in if (forcedMeter == 0) listOf(3,4) else listOf(forcedMeter)) {
                var r=0.0; var im=0.0; var sum=0.0
                for (event in downbeats) {
                    val phase=(event.time-now)*bpm/60/beats*2*PI
                    val weight=event.downbeat*exp(-(now-event.time)/7)
                    r+=cos(phase)*weight; im+=sin(phase)*weight; sum+=weight
                }
                barCoherence=max(barCoherence,hypot(r,im)/max(sum,1e-8))
            }
            val score=coherence*(0.60+0.40*coverage)+continuity+barCoherence*0.32
            scores.add(bpm to score)
            if (score > bestScore) { bestScore=score; bestBpm=bpm; bestAngle=atan2(imaginary,real) }
        }
        val competitor=scores.filter { abs(ln(it.first/bestBpm)) > 0.09 }.maxOfOrNull { it.second } ?: 0.0
        val separation=((bestScore-competitor)/0.15).coerceIn(0.0,1.0)
        val support=((events.size-3)/5.0).coerceIn(0.0,1.0)*min(1.0,span/3.0)
        confidence=((bestScore-0.40)/0.55).coerceIn(0.0,1.0)*support*(0.65+0.35*separation)
        if (confidence < 0.38) return
        val newPeriod=60/bestBpm
        if (!fitted || abs(ln(newPeriod/period)) > 0.18 && confidence > 0.75) {
            period=newPeriod; origin=now+bestAngle/(2*PI)*period; fitted=true
            barEvidence.clear(); if (!manualBar) barLocked=false
        } else {
            val position=(now-origin)/period
            period+=(newPeriod-period)*0.25
            origin=now-position*period
        }
    }

    private fun estimateBar(index: Int) {
        if (manualBar) return
        data class Candidate(val meter: Int,val offset: Int,val score: Double,val repeated: Boolean)
        val candidates=mutableListOf<Candidate>()
        for (beats in if (forcedMeter == 0) listOf(3,4) else listOf(forcedMeter)) {
            for (offset in 0 until beats) {
                var score=0.0
                val support=mutableListOf<Int>()
                for (event in barEvidence.values) {
                    val first=Math.floorMod(event.index-offset,beats)==0
                    val weight=event.weight*exp(-(index-event.index)/24.0)
                    score+=weight*ln(if (first) event.probability else 1-event.probability)
                    if (first && event.probability > 0.55 && event.weight > 0.25) support.add(event.index)
                }
                val repeated=support.zipWithNext().any { (a,b) -> b-a == beats } && index-(support.lastOrNull() ?: -1000) <= beats+1
                candidates.add(Candidate(beats,offset,score,repeated && barEvidence.size >= beats*2))
            }
        }
        val ranked=candidates.sortedByDescending { it.score }
        val winner=ranked.first()
        val certainty=if (winner.repeated) 1-exp(-(winner.score-ranked[1].score)/2.5) else 0.0
        barConfidence=certainty
        if (certainty >= 0.68) { meter=winner.meter; barOffset=winner.offset; barLocked=true }
        else if (certainty < 0.38 || winner.meter != meter || winner.offset != barOffset) barLocked=false
    }

    fun tap(time: Double) {
        if (taps.isNotEmpty() && time-taps.last() > 2.0) taps.clear()
        if (taps.isNotEmpty() && time-taps.last() < 0.20) return
        taps.addLast(time)
        while (taps.size > 7) taps.removeFirst()
        if (taps.size >= 3) {
            val gaps=taps.zipWithNext { a,b -> b-a }.sorted()
            period=gaps[gaps.size/2].coerceIn(0.25,1.5)
            origin=time; fitted=true; manualTempo=true; confidence=1.0
            barEvidence.clear(); manualBar=false; barLocked=false; barConfidence=0.0
        }
    }

    fun alignBar(time: Double) {
        if (!fitted) return
        val index=round((time-origin)/period).toInt()
        origin=time-index*period
        barOffset=Math.floorMod(index,meter); manualBar=true; barLocked=true; barConfidence=1.0
    }

    fun scaleTempo(factor: Double, time: Double) {
        if (!fitted) return
        require(factor == 0.5 || factor == 2.0)
        require(time.isFinite())
        period=(period/factor).coerceIn(0.25,1.5); manualTempo=true; confidence=1.0
        barEvidence.clear(); manualBar=false; barLocked=false; barConfidence=0.0
    }

    fun automatic() {
        manualTempo=false; manualBar=false; taps.clear(); barEvidence.clear(); barLocked=false; barConfidence=0.0
        lastFit=Double.NEGATIVE_INFINITY
    }

    fun state(time: Double, beat: Float = 0f, downbeat: Float = 0f): RhythmState {
        val present=time-lastAudible < 0.35
        val freshness=exp(-max(0.0,time-lastBeat-max(1.3,period*2.5))/1.2)
        val certainty=if (manualTempo) 1.0 else confidence*freshness
        val validBar=barLocked && (manualBar || certainty >= 0.5)
        return RhythmState(timestamp=time, bpm=if (fitted) (60/period).toFloat() else 0f,
            position=if (fitted) (time-origin)/period else 0.0,
            confidence=if (present) certainty.toFloat() else 0f,
            barConfidence=if (present) if (manualBar) 1f else (barConfidence*freshness).toFloat() else 0f,
            beatsPerBar=meter, barOffset=barOffset, barLocked=validBar && present,
            manualTempo=manualTempo, manualBar=manualBar, signalPresent=present,
            lastBeatTime=lastBeat, beatProbability=beat, downbeatProbability=downbeat)
    }
}
