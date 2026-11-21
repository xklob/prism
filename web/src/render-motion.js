// Display-rate interpolation of the tracker's timestamped samples, as in BeatMotion.kt.
const clamp = (value, lo, hi) => Math.max(lo, Math.min(hi, value));
const smooth = (t) => t * t * (3 - 2 * t);
const silence = Object.freeze({ beat: 0, bar: 0, flow: 0, texture: 0, flash: 0, inversion: 0 });

function pulse(phase, cycle, length, bpm) {
  const attack = clamp((bpm / 60) * 0.045, 0.025, 0.18);
  const rise = smooth(clamp((phase - cycle + attack) / attack, 0, 1));
  return Math.exp(-((phase / (length * 0.8)) ** 2)) * (1 - rise) + rise;
}

function shape(s, motion) {
  const amount = s.audioAmount;
  return {
    beat: clamp((motion.beat || 0) * s.beatImpact * amount, 0, 3),
    bar: clamp((motion.bar || 0) * s.barImpact * amount, 0, 3),
    flow: clamp((motion.flow || 0) * s.flowImpact * amount, 0, 3),
    texture: clamp((motion.texture || 0) * s.textureAmount * amount, 0, 2),
    flash: clamp((motion.flash || 0) * s.downbeatFlash * amount, 0, 1),
    inversion: clamp((motion.inversion || 0) * s.downbeatInvert * amount, 0, 1),
  };
}

export class BeatMotion {
  previous = null;
  effectAt = -Infinity;
  lastFlash = -Infinity;
  motion = silence;

  disarm() {
    this.previous = null;
    this.effectAt = -Infinity;
  }

  at(now, s, audio) {
    if (!audio || !s.audioEnabled || s.audioAmount <= 0) {
      this.disarm();
      return (this.motion = silence);
    }
    if (s.paused) {
      this.disarm();
      return { ...this.motion, flash: 0, inversion: 0 };
    }
    const r = audio.rhythm;
    if (!r) {
      this.disarm();
      return (this.motion = shape(s, { ...audio, texture: audio.texture ?? audio.high ?? 0 }));
    }
    if (!r.signalPresent || now - r.timestamp > 0.5) {
      this.disarm();
      return (this.motion = silence);
    }
    const position =
      r.position + (clamp(now + s.syncOffsetMs / 1000 - r.timestamp, -0.5, 0.5) * r.bpm) / 60 + 1e-7;
    const phase = position - Math.floor(position);
    const barPosition = position - r.barOffset;
    const barIndex = Math.floor(barPosition / r.beatsPerBar);
    const barPhase = barPosition - barIndex * r.beatsPerBar;
    const age = Math.max(0, now - r.lastBeatTime);
    const beat = r.locked
      ? pulse(phase, 1, s.pulseLength, r.bpm) * r.confidence
      : 0.65 * smooth(clamp(age / 0.025, 0, 1)) * Math.exp(-((Math.max(0, age - 0.025) / 0.13) ** 2));
    const bar = r.barLocked
      ? pulse(barPhase, r.beatsPerBar, s.pulseLength * 2.5, r.bpm) * r.barConfidence
      : 0;
    const flowPhase = r.barLocked ? barPhase / r.beatsPerBar : phase;
    const flow = r.locked ? (0.5 - 0.5 * Math.cos(2 * Math.PI * flowPhase)) * r.confidence : 0;
    if (r.locked && r.barLocked && !r.coasting && r.conflict === 'NONE') {
      const prev = this.previous;
      const dt = prev ? now - prev.now : Infinity;
      const continuous =
        prev &&
        dt >= 0 &&
        dt <= 0.25 &&
        Math.abs(position - prev.position - (dt * r.bpm) / 60) < 0.2 &&
        prev.meter === r.beatsPerBar &&
        prev.offset === r.barOffset &&
        prev.epoch === audio.epoch;
      if (!continuous) this.effectAt = -Infinity;
      else if (
        barIndex === prev.barIndex + 1 &&
        now - this.lastFlash > ((r.beatsPerBar * 60) / r.bpm) * 0.65
      ) {
        this.effectAt = this.lastFlash = now;
      }
      this.previous = {
        now,
        position,
        barIndex,
        meter: r.beatsPerBar,
        offset: r.barOffset,
        epoch: audio.epoch,
      };
    } else this.disarm();
    const elapsed = Math.max(0, now - this.effectAt);
    return (this.motion = shape(s, {
      beat,
      bar,
      flow,
      texture: audio.levels?.high,
      flash: clamp(1 - elapsed / 0.035, 0, 1),
      inversion: 1 - smooth(clamp(elapsed / (s.invertFadeMs / 1000), 0, 1)),
    }));
  }
}

const scales = [1, 0.85, 0.7, 0.55, 0.45];

export class RenderQuality {
  level = 0;
  seconds = 0;
  frames = 0;
  healthy = 0;
  previousTarget = 0;

  sample(dt, target, enabled, paused) {
    if (!enabled) this.level = 0;
    if (!enabled || paused || dt <= 0 || dt > 2 || target !== this.previousTarget) {
      this.seconds = this.frames = this.healthy = 0;
      this.previousTarget = target;
    } else {
      this.seconds += dt;
      this.frames++;
      if (this.seconds >= 3) {
        const fps = this.frames / this.seconds;
        if (fps < target * 0.88) this.level = Math.min(this.level + 1, scales.length - 1);
        this.healthy = fps >= target * 0.96 && this.level > 0 ? this.healthy + this.seconds : 0;
        if (this.healthy >= 12) {
          this.level--;
          this.healthy = 0;
        }
        this.seconds = this.frames = 0;
      }
    }
    return scales[this.level];
  }
}
