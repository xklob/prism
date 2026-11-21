// The controller signs clock replies and shared cues with its per-show P-256 key.
const scenes = [1, 2, 7, 9];
const meters = [3, 4];
const phraseLengths = [8, 16, 32];
const emptyMotion = { beat: 0, bar: 0, flow: 0, texture: 0, flash: 0, inversion: 0 };
const darkFrame = {
  dark: true,
  diagnostic: true,
  time: 0,
  timeMs: 0,
  cue: null,
  motion: emptyMotion,
  transition: 1,
  phraseBar: 0,
};
const inRange = (value, low, high) => Number.isFinite(value) && value >= low && value <= high;
const clamp = (value) => Math.min(1, Math.max(0, value));
const mod = (value, period) => ((value % period) + period) % period;
const clockNow = () => performance.now();

export function inviteFromFragment(hash) {
  return new URLSearchParams(hash.replace(/^#/, '')).get('crowd') || '';
}

function decode64(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value))
    throw new Error('Invalid controller signature.');
  return Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/')), (c) => c.charCodeAt(0));
}

export function parseInvitation(input) {
  let text = String(input).trim();
  if (text.length > 4096) throw new Error('The show invitation is too long.');
  let url;
  try {
    url = new URL(text);
    if (url.protocol === 'https:' || url.protocol === 'http:') {
      text = inviteFromFragment(url.hash);
      url = new URL(text);
    }
  } catch {
    throw new Error('Paste a show invitation from the prism controller.');
  }
  if (url.protocol !== 'prism:' || url.host !== 'crowd' || url.username || url.password)
    throw new Error('Paste a prism crowd invitation.');
  const q = url.searchParams;
  const host = q.get('host') || '',
    octets = host.split('.').map(Number);
  const local =
    octets.length === 4 &&
    octets.map(String).join('.') === host &&
    octets.every((n) => Number.isInteger(n) && inRange(n, 0, 255)) &&
    (octets[0] === 10 ||
      octets[0] === 127 ||
      (octets[0] === 172 && inRange(octets[1], 16, 31)) ||
      (octets[0] === 192 && octets[1] === 168));
  const port = Number(q.get('port')),
    sid = q.get('sid'),
    token = q.get('token'),
    pin = q.get('key'),
    role = q.get('role');
  if (
    !local ||
    !Number.isInteger(port) ||
    !inRange(port, 1024, 65535) ||
    !/^[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(sid || '') ||
    !/^[A-Za-z0-9_-]{24,64}$/.test(token || '') ||
    !/^[A-Za-z0-9_-]{100,172}$/.test(pin || '') ||
    !['source', 'viewer'].includes(role)
  )
    throw new Error('Invalid address or credentials in the show invitation.');
  const protocol = q.get('web');
  if (!['http', 'https'].includes(protocol))
    throw new Error('This invitation needs prism controller 0.5.0 or newer.');
  return {
    endpoint: protocol + '://' + host + ':' + port + '/crowd/exchange',
    loopback: octets[0] === 127,
    token,
    sid,
    pin,
    source: role === 'source',
  };
}

function randomId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  // getRandomValues is also available in ordinary HTTP pages on a LAN.
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (n) => n.toString(16).padStart(2, '0')).join(
    ''
  );
}
async function controllerVerifier(pin) {
  const spki = decode64(pin);
  if (crypto.subtle) {
    const key = await crypto.subtle.importKey('spki', spki, { name: 'ECDSA', namedCurve: 'P-256' }, false, [
      'verify',
    ]);
    return (signature, payload) =>
      crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, signature, payload);
  }
  const { pinnedVerifier } = await import('../assets/crowd-p256.js');
  return pinnedVerifier(spki);
}
async function verify(envelope, verifySignature, sid, type) {
  if (
    !envelope ||
    typeof envelope.payload !== 'string' ||
    envelope.payload.length > 8192 ||
    typeof envelope.signature !== 'string' ||
    envelope.signature.length !== 86
  )
    throw new Error('Invalid signed show message.');
  const payload = decode64(envelope.payload);
  if (!(await verifySignature(decode64(envelope.signature), payload))) {
    throw new Error('Could not verify the controller signature. Check the invitation.');
  }
  const data = JSON.parse(new TextDecoder().decode(payload));
  if (data.v !== 1 || data.sid !== sid || data.type !== type)
    throw new Error('The controller reply belongs to a different show.');
  return data;
}

function validateState(data) {
  const ranges = {
    speed: [0.05, 2],
    intensity: [0.25, 1.6],
    complexity: [0, 1],
    symmetry: [3, 20],
    distortion: [0, 1.5],
    zoom: [0.45, 3],
    hue: [0, 1],
    rotation: [-1, 1],
    colorSpeed: [0, 0.2],
    morph: [0, 1.5],
    beat: [-1e9, 1e9],
    anchor: [-1e14, 1e14],
    bpm: [40, 240],
    flashMs: [16, 100],
    invertMs: [60, 400],
  };
  const music = data.music;
  const valid =
    Number.isSafeInteger(data.seq) &&
    data.seq >= 0 &&
    inRange(data.sent, 0, 1e14) &&
    inRange(data.validUntil, data.sent, data.sent + 3000) &&
    typeof data.blackout === 'boolean' &&
    music &&
    typeof music.ready === 'boolean' &&
    ['confidence', 'barConfidence', 'phraseConfidence'].every((k) => inRange(music[k], 0, 1)) &&
    Array.isArray(data.cues) &&
    inRange(data.cues.length, 1, 2) &&
    data.cues.every(
      (cue, index, cues) =>
        cue &&
        Object.entries(ranges).every(([k, [low, high]]) => inRange(cue[k], low, high)) &&
        Number.isInteger(cue.symmetry) &&
        Number.isSafeInteger(cue.phraseOrigin) &&
        Number.isSafeInteger(cue.revision) &&
        inRange(cue.effective, 0, data.sent + 12000) &&
        inRange(cue.transitionAt, 0, data.sent + 12000) &&
        scenes.includes(cue.scene) &&
        scenes.includes(cue.previousScene) &&
        [0, 1, 2, 3].includes(cue.palette) &&
        meters.includes(cue.meter) &&
        phraseLengths.includes(cue.phraseBars) &&
        [0.25, 0.5, 1, 3, 4].includes(cue.flashEvery) &&
        ['flash', 'scene'].includes(cue.mode) &&
        typeof cue.running === 'boolean' &&
        (!index || (cues[index - 1].effective <= cue.effective && cues[index - 1].revision < cue.revision))
    );
  if (!valid) throw new Error('Invalid show schedule.');
  // The native client constructs the shared cue with VisualSettings' common defaults.
  for (const cue of data.cues) {
    cue.audioEnabled = true;
    cue.lineWidth = 1.1;
  }
}

class ShowClock {
  samples = [];
  estimate = null;
  observe(t1, t2, t3, t4) {
    const delay = t4 - t1 - (t3 - t2);
    if (
      ![t1, t2, t3, t4].every(Number.isFinite) ||
      t4 < t1 ||
      !inRange(t3 - t2, 0, 100) ||
      !inRange(delay, 0, 250)
    )
      return;
    this.samples.push({ local: (t1 + t4) / 2, offset: (t2 - t1 + (t3 - t4)) / 2, delay });
    this.samples = this.samples.filter((s) => t4 - s.local <= 30000).slice(-80);
    const best = [...this.samples]
      .sort((a, b) => a.delay - b.delay)
      .slice(0, Math.max(3, Math.floor(this.samples.length / 3)));
    const avg = (key) => best.reduce((sum, s) => sum + s[key], 0) / best.length;
    const origin = avg('local'),
      offset = avg('offset');
    const locals = best.map((s) => s.local),
      variance = best.reduce((sum, s) => sum + (s.local - origin) ** 2, 0);
    const slope = best.reduce((sum, s) => sum + (s.local - origin) * (s.offset - offset), 0) / variance;
    const drift =
      variance && Math.max(...locals) - Math.min(...locals) >= 5000
        ? Math.min(0.0005, Math.max(-0.0005, slope))
        : 0;
    const residual = Math.max(...best.map((s) => Math.abs(s.offset - offset - (s.local - origin) * drift)));
    this.estimate = {
      origin,
      offset,
      drift,
      uncertainty: Math.max(...best.map((s) => s.delay)) / 2 + residual + 0.25,
      at: t4,
    };
  }
  time(local) {
    const e = this.estimate;
    return local + e.offset + (local - e.origin) * e.drift;
  }
  error(local) {
    return this.estimate
      ? this.estimate.uncertainty + Math.max(0, local - this.estimate.at) * 0.0001
      : Infinity;
  }
  ready(local) {
    return this.samples.length >= 6 && inRange(local - this.estimate.at, 0, 5000) && this.error(local) <= 15;
  }
}

class CrowdTimeline {
  armedAfter = Infinity;
  ready = false;
  previousTime = NaN;
  previousRevision = -1;
  lastFlash = -Infinity;
  missed = 0;
  frames = 0;
  phraseBar = 0;
  sample(s, time, ready, flashes, blackout) {
    const cue = s.cues.findLast((c) => c.effective <= time) || s.cues[0];
    const fresh = ready && inRange(time, s.sent - 100, s.validUntil);
    if ((fresh && !this.ready) || !s.music.ready || s.music.confidence < 0.5 || s.music.barConfidence < 0.5)
      this.armedAfter = time;
    if (time < this.previousTime - 1) this.armedAfter = Math.max(this.armedAfter, this.previousTime);
    this.ready = fresh;
    const positionAt = (t) => cue.beat + ((t - cue.anchor) * cue.bpm) / 60000;
    const timeAt = (beat) => cue.anchor + ((beat - cue.beat) * 60000) / cue.bpm;
    const position = positionAt(time),
      barPhase = mod(position, cue.meter);
    const step = cue.flashEvery >= 3 ? cue.meter : cue.flashEvery,
      period = (step * 60000) / cue.bpm;
    const eventIndex = Math.floor((position + 1e-8) / step),
      eventAt = timeAt(eventIndex * step);
    const age = time - eventAt,
      width = Math.min(cue.flashMs, period * 0.8);
    const moving = fresh && s.music.ready && cue.running;
    const trusted = moving && s.music.confidence >= 0.5 && s.music.barConfidence >= 0.5 && !s.blackout;
    if (trusted && this.previousRevision === cue.revision && time >= this.previousTime) {
      const first = Math.floor((positionAt(this.previousTime) + 1e-8) / step) + 1;
      if (eventIndex >= first) this.missed += eventIndex - first + Number(age >= width);
    }
    const sameOrNew = Math.abs(eventAt - this.lastFlash) < 0.001 || eventAt > this.lastFlash + period * 0.5;
    const flash = Number(
      trusted && flashes && !blackout && eventAt > this.armedAfter && age >= 0 && age < width && sameOrNew
    );
    if (flash) this.lastFlash = eventAt;
    const barAt = timeAt(Math.floor(position / cue.meter) * cue.meter),
      fade = clamp((time - barAt) / cue.invertMs);
    const motion = {
      beat: moving ? Math.exp(-mod(position, 1) / 0.28) * s.music.confidence : 0,
      bar: moving ? 0.9 * Math.exp(-barPhase / 0.56) * s.music.barConfidence : 0,
      flow: trusted ? (0.5 - 0.5 * Math.cos((2 * Math.PI * barPhase) / cue.meter)) * s.music.confidence : 0,
      texture: 0,
      flash,
      inversion:
        trusted && flashes && !blackout && barAt > this.armedAfter ? 1 - fade * fade * (3 - 2 * fade) : 0,
    };
    this.previousRevision = cue.revision;
    this.previousTime = time;
    this.frames++;
    this.phraseBar = mod(Math.floor(position / cue.meter) - cue.phraseOrigin, cue.phraseBars) + 1;
    const heldTime = Math.min(time, s.validUntil),
      diagnostic = cue.mode === 'flash';
    return {
      time: heldTime,
      timeMs: heldTime,
      cue,
      settings: cue,
      previousScene: cue.previousScene,
      motion,
      diagnostic,
      dark: blackout || s.blackout || !cue.running || (diagnostic && !trusted),
      transition: clamp((heldTime - cue.transitionAt) / 600),
      phraseBar: this.phraseBar,
    };
  }
}

const reachability =
  'Could not reach the show. Join its Wi-Fi, allow local network access, or use a controller with trusted HTTPS.';
export class CrowdClient {
  connected = false;
  source = false;
  visible = true;
  flashes = true;
  blackout = false;
  session = null;
  pending = null;
  timer = null;
  problem = '';
  snapshot = null;
  clock = new ShowClock();
  timeline = new CrowdTimeline();
  constructor({ onStatus = () => {}, getAudio = () => null, getFps = () => 0 } = {}) {
    this.onStatus = onStatus;
    this.getAudio = getAudio;
    this.getFps = getFps;
  }
  get status() {
    const local = clockNow(),
      s = this.snapshot,
      clockMs = this.clock.error(local);
    const age = s ? local - s.received : Infinity;
    let phase = 'Offline',
      message = '';
    if (this.connected) {
      if (!this.visible) {
        phase = 'Suspended';
        message = 'Return to prism to follow the show.';
      } else if (!s || !this.clock.estimate) {
        phase = 'Syncing';
        message = 'Waiting for the show. Allow local network access if prompted.';
      } else if (!this.clock.ready(local) || age > 2000 || this.clock.time(local) > s.validUntil) {
        phase = this.clock.samples.length < 6 ? 'Syncing' : 'Unsynced';
        message = 'Waiting for a reliable clock.';
      } else {
        phase = age > 750 ? 'Coasting' : 'Ready';
        message = s.blackout
          ? 'VJ blackout.'
          : s.music.ready
            ? 'Following the show.'
            : 'Waiting for the beat and bar.';
      }
    }
    const time = this.clock.estimate ? this.clock.time(local) : 0;
    const cue = s?.cues.findLast((c) => c.effective <= time) || s?.cues[0];
    return {
      connected: this.connected,
      source: this.source,
      phase,
      message: this.problem || message,
      clockMs: Number.isFinite(clockMs) ? clockMs : null,
      fps: this.getFps(),
      bpm: cue?.bpm || 0,
      beatConfidence: s?.music.confidence || 0,
      barConfidence: s?.music.barConfidence || 0,
      phraseConfidence: s?.music.phraseConfidence || 0,
      phraseBar: this.timeline.phraseBar,
      missed: this.timeline.missed,
    };
  }
  report() {
    this.onStatus(this.status);
  }
  clearTiming() {
    this.snapshot = null;
    this.clock = new ShowClock();
    this.timeline = new CrowdTimeline();
  }
  async join(input) {
    const invite = parseInvitation(input);
    if (!globalThis.crypto?.getRandomValues)
      throw new Error('This browser cannot generate secure show identifiers.');
    this.leave();
    const session = { ...invite, id: randomId(), verify: null };
    this.session = session;
    this.connected = true;
    this.source = invite.source;
    this.blackout = false;
    this.report();
    try {
      session.verify = await controllerVerifier(invite.pin);
      if (this.session !== session || !this.visible) return;
      await this.poll(session, true);
    } catch (error) {
      if (this.session !== session) return;
      this.leave();
      throw error;
    }
  }
  sourceTiming(local) {
    if (!this.source || !this.clock.ready(local)) return;
    const a = this.getAudio();
    if (
      !a ||
      !inRange(local - a.at, -1, 500) ||
      !inRange(a.bpm, 40, 240) ||
      !Number.isFinite(a.beat) ||
      !meters.includes(a.meter) ||
      !phraseLengths.includes(a.phraseBars) ||
      !inRange(a.phraseBar, 0, a.phraseBars) ||
      ![a.confidence, a.barConfidence, a.phraseConfidence].every((c) => inRange(c, 0, 1))
    )
      return;
    return {
      at: this.clock.time(a.at),
      beat: a.beat,
      bpm: a.bpm,
      meter: a.meter,
      confidence: a.confidence,
      barConfidence: a.barConfidence,
      phraseConfidence: a.phraseConfidence,
      phraseBar: a.phraseBar,
      phraseBars: a.phraseBars,
      ready: a.ready === true,
      clockMs: this.clock.error(local),
    };
  }
  request(session, body, init = {}) {
    return fetch(session.endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      mode: 'cors',
      credentials: 'omit',
      redirect: 'error',
      referrerPolicy: 'no-referrer',
      targetAddressSpace: session.loopback ? 'loopback' : 'local',
      ...init,
      body: JSON.stringify({
        v: 1,
        sid: session.sid,
        id: session.id,
        token: session.token,
        name: 'prism web',
        ...body,
      }),
    });
  }
  async poll(session, first = false) {
    const abort = new AbortController();
    this.pending = abort;
    // The first connection can pause while the participant answers the browser's LAN permission prompt.
    const timeout = setTimeout(() => abort.abort(), first ? 60000 : 2500);
    let delay = 100;
    try {
      const health = this.status,
        nonce = randomId(),
        audio = this.sourceTiming(clockNow()),
        t1 = clockNow();
      const response = await this.request(
        session,
        {
          type: 'exchange',
          nonce,
          t1,
          audio,
          health: {
            state: health.phase,
            fps: health.fps,
            clockMs: health.clockMs,
            missed: health.missed,
            frames: this.timeline.frames,
            phraseBar: health.phraseBar,
            beatConfidence: health.beatConfidence,
          },
        },
        { signal: abort.signal }
      );
      if (!response.ok) {
        if (response.status === 403)
          throw new Error(
            'The controller rejected this invitation. Get a current link and check the configured web app URL.'
          );
        if (response.status === 503) throw new Error('This show already has 50 participants.');
        throw new Error('The controller could not accept the timing request.');
      }
      const data = await response.text(),
        t4 = clockNow();
      if (data.length > 16384) throw new Error('The controller response was too large.');
      const envelopes = JSON.parse(data);
      const [sync, state, error] = await Promise.all([
        verify(envelopes.sync, session.verify, session.sid, 'sync'),
        verify(envelopes.state, session.verify, session.sid, 'state'),
        envelopes.error ? verify(envelopes.error, session.verify, session.sid, 'error') : null,
      ]);
      if (sync.id !== session.id || sync.nonce !== nonce || sync.t1 !== t1)
        throw new Error('The controller clock reply did not match.');
      validateState(state);
      if (this.pending !== abort) return;
      this.clock.observe(t1, sync.t2, sync.t3, t4);
      if (
        (!this.snapshot || state.seq > this.snapshot.seq) &&
        (!this.clock.ready(t4) || inRange(this.clock.time(t4), state.sent - 100, state.validUntil))
      )
        this.snapshot = { ...state, received: t4 };
      this.problem = error ? String(error.message).slice(0, 160) : '';
    } catch (error) {
      if (this.pending !== abort) return;
      const message =
        error instanceof TypeError || error.name === 'AbortError' ? reachability : error.message;
      if (first) throw new Error(message);
      this.problem = message;
      delay = 500;
    } finally {
      clearTimeout(timeout);
    }
    if (this.pending !== abort) return;
    this.pending = null;
    this.report();
    if (this.session === session && this.visible) this.timer = setTimeout(() => this.poll(session), delay);
  }
  setVisible(visible) {
    if (this.visible === visible) return;
    this.visible = visible;
    clearTimeout(this.timer);
    this.pending?.abort();
    this.pending = null;
    this.clearTiming();
    this.problem = '';
    if (this.session?.verify && visible) void this.poll(this.session);
    this.report();
  }
  frame(local = clockNow()) {
    if (!this.connected) return null;
    if (!this.visible || !this.snapshot || !this.clock.estimate) return darkFrame;
    return this.timeline.sample(
      this.snapshot,
      this.clock.time(local),
      this.clock.ready(clockNow()),
      this.flashes,
      this.blackout
    );
  }
  leave() {
    const session = this.session;
    this.session = null;
    this.connected = false;
    this.source = false;
    clearTimeout(this.timer);
    this.pending?.abort();
    this.pending = null;
    this.clearTiming();
    this.problem = '';
    if (session?.verify) {
      this.request(session, { type: 'leave' }, { keepalive: true }).catch(() => {});
    }
    if (session) this.report();
  }
}
