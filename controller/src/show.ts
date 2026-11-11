export type Cue = {
  revision: number; effective: number; anchor: number; beat: number; bpm: number;
  meter: number; phraseBars: number; phraseOrigin: number; running: boolean;
  mode: 'flash' | 'scene'; scene: number; previousScene: number; transitionAt: number; palette: number;
  speed: number; intensity: number; complexity: number; symmetry: number;
  distortion: number; zoom: number; hue: number; rotation: number; colorSpeed: number; morph: number;
  flashEvery: number; flashMs: number; invertMs: number;
};
export type Music = { source: 'manual' | 'audio'; confidence: number; barConfidence: number; phraseConfidence: number; ready: boolean };
export type AudioInput = {
  at: number; beat: number; bpm: number; meter: number; confidence: number;
  barConfidence: number; phraseConfidence: number; phraseBar: number; phraseBars: number;
  ready: boolean; clockMs: number;
};
export const beatAt = (cue: Cue, time: number): number => cue.beat + (time - cue.anchor) * cue.bpm / 60000;
export const boundaryAfter = (cue: Cue, time: number, beats = cue.meter): number =>
  cue.anchor + (Math.ceil((beatAt(cue, time) + 1e-8) / beats) * beats - cue.beat) * 60000 / cue.bpm;
const finite = (value: unknown, low: number, high: number): value is number =>
  typeof value === 'number' && Number.isFinite(value) && value >= low && value <= high;

export class Show {
  private current: Cue = {
    revision: 0, effective: 0, anchor: 0, beat: 0, bpm: 120, meter: 4,
    phraseBars: 16, phraseOrigin: 0, running: false, mode: 'flash', scene: 1, previousScene: 1, transitionAt: 0, palette: 0,
    speed: .7, intensity: .95, complexity: .72, symmetry: 8, distortion: .6,
    zoom: 1, hue: 0, rotation: .12, colorSpeed: .04, morph: .45,
    flashEvery: 4, flashMs: 50, invertMs: 180
  };
  private pending?: Cue;
  private revision = 0;
  private lastAudio = -Infinity;
  private audio?: AudioInput;
  private lastTap?: number;
  private taps: number[] = [];
  source: 'manual' | 'audio' = 'manual';
  blackout = true;

  active(now: number): Cue {
    if (this.pending && now >= this.pending.effective) {
      this.current = this.pending;
      this.pending = undefined;
    }
    return this.current;
  }

  snapshot(now: number): { cues: Cue[]; music: Music; blackout: boolean } {
    const current = this.active(now);
    const trusted = this.audio && now - this.lastAudio < 1000 && this.audio.ready;
    return {
      cues: this.pending ? [current, this.pending] : [current],
      music: this.source === 'manual'
        ? { source: 'manual', confidence: 1, barConfidence: 1, phraseConfidence: 1, ready: true }
        : { source: 'audio', confidence: this.audio?.confidence ?? 0, barConfidence: this.audio?.barConfidence ?? 0,
          phraseConfidence: this.audio?.phraseConfidence ?? 0, ready: !!trusted },
      blackout: this.blackout
    };
  }

  command(command: Record<string, unknown>, now: number): void {
    const active = this.active(now);
    if (command.action === 'blackout' || command.action === 'stop') {
      this.blackout = true; this.pending = undefined;
      this.current = { ...active, running: false, effective: now, revision: ++this.revision };
      return;
    }
    if (command.action === 'source') {
      if (command.source !== 'manual' && command.source !== 'audio') throw new Error('Choose manual or audio timing.');
      this.source = command.source;
      this.audio = undefined; this.lastAudio = -Infinity;
      return;
    }
    if (command.action === 'tap') {
      if (this.lastTap !== undefined && now - this.lastTap > 240 && now - this.lastTap < 1500) {
        this.taps.push(now - this.lastTap); this.taps = this.taps.slice(-6);
      } else this.taps = [];
      this.lastTap = now;
      if (this.taps.length < 2) return;
      const sorted = [...this.taps].sort((a, b) => a - b);
      command = { action: 'settings', bpm: Math.round(600000 / sorted[Math.floor(sorted.length / 2)]!) / 10 };
    }
    if (!['start', 'align', 'settings'].includes(String(command.action))) throw new Error('Unknown show command.');
    const next = { ...(this.pending ?? active) };
    const bounds: Partial<Record<keyof Cue, [number, number]>> = {
      bpm: [40, 240], speed: [.05, 2], intensity: [.25, 1.6], complexity: [0, 1],
      symmetry: [3, 20], distortion: [0, 1.5], zoom: [.45, 3], hue: [0, 1],
      rotation: [-1, 1], colorSpeed: [0, .2], morph: [0, 1.5],
      flashMs: [16, 100], invertMs: [60, 400]
    };
    for (const [key, limits] of Object.entries(bounds)) {
      const value = command[key];
      if (value === undefined) continue;
      if (!finite(value, limits[0], limits[1])) throw new Error('Invalid ' + key);
      Object.assign(next, { [key]: key === 'symmetry' ? Math.round(value) : value });
    }
    for (const [key, choices] of Object.entries({
      scene: [0, 1, 2, 6], palette: [0, 1, 2, 3], meter: [3, 4],
      phraseBars: [8, 16, 32], flashEvery: [.25, .5, 1, 3, 4]
    })) {
      if (command[key] === undefined) continue;
      if (!choices.includes(command[key] as number)) throw new Error('Invalid ' + key);
      Object.assign(next, { [key]: command[key] });
    }
    if (command.mode !== undefined) {
      if (command.mode !== 'flash' && command.mode !== 'scene') throw new Error('Invalid scene mode.');
      next.mode = command.mode;
    }
    // Keep a pending boundary fixed as sliders move; otherwise frequent edits can
    // postpone activation forever. Freeze it during the final 250 ms of delivery.
    if (this.pending && this.pending.effective - now < 250) throw new Error('Cue is starting. Try again in a moment.');
    next.effective = this.pending?.effective ?? (active.running ? boundaryAfter(active, now + 1000) : now + 1000);
    next.anchor = next.effective;
    next.beat = active.running ? beatAt(active, next.effective) : 0;
    if (next.scene !== active.scene) {
      next.previousScene = active.scene; next.transitionAt = next.effective;
    }
    if (command.action === 'start' || command.action === 'align') {
      next.running = true; this.blackout = false;
      if (command.action === 'align') {
        // The click means bar one NOW. Activate the corrected grid on a future bar.
        next.anchor = now; next.beat = 0; next.phraseOrigin = 0;
        next.effective = boundaryAfter(next, now + 1000);
      }
    }
    next.revision = ++this.revision;
    this.pending = next;
  }

  receiveAudio(input: AudioInput, now: number): void {
    if (!finite(input.at, now - 1000, now + 100) || !finite(input.beat, -1e9, 1e9) ||
      !finite(input.bpm, 40, 240) || ![3, 4].includes(input.meter) ||
      ![8, 16, 32].includes(input.phraseBars) || !finite(input.phraseBar, 0, input.phraseBars) ||
      !finite(input.confidence, 0, 1) || !finite(input.barConfidence, 0, 1) ||
      !finite(input.phraseConfidence, 0, 1) || !finite(input.clockMs, 0, 15) ||
      typeof input.ready !== 'boolean') throw new Error('Invalid or stale audio timeline.');
    if (this.audio && input.at <= this.audio.at) return;
    this.audio = input; this.lastAudio = now;
    if (this.source !== 'audio' || !input.ready || input.confidence < .5 || input.barConfidence < .5 || this.blackout) return;
    const active = this.active(now);
    if (this.pending && this.pending.effective - now < 250) return;
    const effective = this.pending?.effective ?? now + 1000;
    this.pending = { ...(this.pending ?? active), revision: ++this.revision, effective,
      anchor: input.at, beat: input.beat, bpm: input.bpm, meter: input.meter, phraseBars: input.phraseBars,
      phraseOrigin: input.phraseBar > 0 ? Math.floor(input.beat / input.meter) - input.phraseBar + 1 : 0,
      running: true };
  }
}
