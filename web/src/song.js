const api = 'https://api.reccobeats.com/v1/';
const endpoint = 'https://api.audd.io/';
const normalize = (s) =>
  String(s || '')
    .normalize('NFKD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim();
const spotifyId = (s) =>
  /^(?:spotify:track:|https:\/\/open\.spotify\.com\/(?:intl-[^/]+\/)?track\/)?([a-zA-Z0-9]{22})(?:[?#/].*)?$/.exec(
    s
  )?.[1];
export function matchTrack(query, rows) {
  const id = spotifyId(query.id || '');
  if (id) return rows.find((row) => spotifyId(row.href) === id);
  if (!query.artist?.trim()) return;
  const matches = rows.filter(
    (row) =>
      normalize(row.trackTitle) === normalize(query.title) &&
      row.artists?.some((a) => normalize(a.name) === normalize(query.artist)) &&
      (!row.durationMs || !query.duration || Math.abs(row.durationMs - query.duration) <= 3000)
  );
  if (matches.length === 1 || (matches[0]?.isrc && matches.every((row) => row.isrc === matches[0].isrc)))
    return matches[0];
}
export function recognitionWait(attempts, time) {
  const recent = attempts.filter((t) => t > time - 3600);
  return Math.max(
    0,
    recent.length ? recent.at(-1) + 30 - time : 0,
    recent.length >= 40 ? recent[0] + 3600 - time : 0
  );
}
class ClipBuffer {
  constructor() {
    this.samples = new Int16Array(160000);
    this.clear();
  }
  clear() {
    this.index = 0;
    this.count = 0;
  }
  append(channels) {
    const input = channels[0];
    for (let i = 0; i + 2 < input.length; i += 3) {
      const value = (input[i] + input[i + 1] + input[i + 2]) / 3;
      this.samples[this.index] = Math.round(Math.max(-1, Math.min(1, value)) * 32767);
      this.index = (this.index + 1) % this.samples.length;
      this.count = Math.min(this.count + 1, this.samples.length);
    }
  }
  wav() {
    if (this.count < 128000) throw new Error('Listen for at least eight seconds before identifying.');
    const data = new ArrayBuffer(44 + this.count * 2);
    const view = new DataView(data);
    const str = (offset, s) => [...s].forEach((c, i) => view.setUint8(offset + i, c.charCodeAt(0)));
    str(0, 'RIFF');
    view.setUint32(4, data.byteLength - 8, true);
    str(8, 'WAVEfmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
    view.setUint32(24, 16000, true);
    view.setUint32(28, 32000, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    str(36, 'data');
    view.setUint32(40, this.count * 2, true);
    let energy = 0;
    for (let i = 0; i < this.count; i++) {
      const value = this.samples[(this.index - this.count + this.samples.length + i) % this.samples.length];
      view.setInt16(44 + i * 2, value, true);
      energy += value * value;
    }
    if (Math.sqrt(energy / this.count) < 12) throw new Error('The recent input is silent.');
    return new Blob([data], { type: 'audio/wav' });
  }
}
async function request(url, signal, body) {
  const response = await fetch(url, {
    signal,
    method: body ? 'POST' : 'GET',
    body,
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    redirect: 'error',
  });
  if (!response.ok)
    throw new Error(
      response.status === 429
        ? 'Service rate limit reached. Try again later.'
        : 'Song service is unavailable.'
    );
  return response.json();
}
export class SongAssist {
  constructor(onChange, hint) {
    this.onChange = onChange;
    this.hint = hint;
    this.clips = new ClipBuffer();
    this.cache = new Map();
    this.enabled = false;
    this.key = '';
    this.identify = false;
    this.message = '';
    this.trackTitle = '';
    this.next = 0;
  }
  reset() {
    this.abort?.abort();
    this.abort = null;
    this.clips.clear();
    this.trackTitle = '';
    this.message = '';
    this.next = 0;
    this.hint(null);
    this.onChange();
  }
  allow(value) {
    this.enabled = value;
    if (!value) {
      this.key = '';
      this.identify = false;
    }
    this.reset();
  }
  setKey(value) {
    if (value && !/^[\w-]{1,256}$/.test(value)) throw new Error('Enter a valid AudD token.');
    this.key = value;
    this.identify = !!value;
    this.reset();
  }
  pcm(channels) {
    if (this.enabled && this.identify && this.key) {
      this.clips.append(channels);
      if (this.clips.count === this.clips.samples.length && !this.abort && Date.now() >= this.next) {
        this.next = Date.now() + 90000;
        void this.recognize();
      }
    }
  }
  start() {
    this.abort?.abort();
    this.abort = new AbortController();
    return this.abort;
  }
  async run(operation) {
    if (!this.enabled) return;
    const task = this.start();
    const timeout = setTimeout(() => task.abort(new Error('Song request timed out.')), 20000);
    try {
      await operation(task);
    } catch (e) {
      if (task === this.abort) this.message = e.message || 'Song lookup failed.';
    } finally {
      clearTimeout(timeout);
      if (task === this.abort) {
        this.abort = null;
        this.onChange();
      }
    }
  }
  async lookup(query, task) {
    const key = JSON.stringify(query);
    let cached = this.cache.get(key);
    if (!cached || cached.until < Date.now()) {
      const id = spotifyId(query.id || '');
      if (!id && (!query.title?.trim() || !query.artist?.trim()))
        throw new Error('Enter a Spotify track link or both artist and title.');
      const route = id
        ? 'track?ids=' + id
        : 'track/search?searchText=' + encodeURIComponent(query.title.trim().slice(0, 200));
      const rows = (await request(api + route, task.signal)).content || [];
      const row = matchTrack(query, rows);
      let bpm;
      if (row) {
        const result = await request(api + 'audio-features?ids=' + encodeURIComponent(row.id), task.signal);
        bpm = result.content?.find((f) => f.id === row.id)?.tempo;
      }
      cached = { row, bpm, until: Date.now() + (row ? 86400000 : 300000) };
      if (this.cache.size >= 64) this.cache.delete(this.cache.keys().next().value);
      this.cache.set(key, cached);
    }
    if (this.abort !== task) return;
    const { bpm, row } = cached;
    if (row)
      this.trackTitle = [row.artists?.map((a) => a.name).join(', '), row.trackTitle]
        .filter(Boolean)
        .join(' · ');
    this.hint(bpm >= 30 && bpm <= 300 ? { bpm, key: row.id } : null);
    this.message = row
      ? bpm
        ? Math.round(bpm) + ' catalog BPM · ReccoBeats'
        : 'Matched track · no catalog tempo'
      : 'No unambiguous catalog match.';
  }
  search(query) {
    this.hint(null);
    this.trackTitle = '';
    this.message = 'Looking up track…';
    this.onChange();
    return this.run((task) => this.lookup(query, task));
  }
  async recognize() {
    if (!this.identify || !this.key || !this.enabled) return;
    await this.run(async (task) => {
      const blob = this.clips.wav();
      const reserve = () => {
        if (task !== this.abort) throw new Error('Recognition cancelled.');
        const time = Date.now() / 1000;
        let attempts;
        try {
          attempts = JSON.parse(localStorage.getItem('prism.audd.attempts') || '[]')
            .filter((t) => Number.isFinite(t) && t > time - 3600)
            .sort((a, b) => a - b);
        } catch {
          throw new Error('Recognition needs local storage for its request limit.');
        }
        const wait = recognitionWait(attempts, time);
        if (wait) throw new Error('Next identification available in ' + Math.ceil(wait) + ' seconds.');
        localStorage.setItem('prism.audd.attempts', JSON.stringify([...attempts, time]));
      };
      if (globalThis.navigator?.locks) await navigator.locks.request('prism.audd.request-budget', reserve);
      else reserve();
      if (task !== this.abort) return;
      this.next = Date.now() + 90000;
      this.message = 'Identifying with AudD…';
      this.onChange();
      const form = new FormData();
      form.set('api_token', this.key);
      form.set('return', 'spotify');
      form.set('file', blob, 'sample.wav');
      const result = await request(endpoint, task.signal, form);
      if (task !== this.abort) return;
      if (result.status !== 'success')
        throw new Error('AudD could not identify this clip. Check the key and credits.');
      this.hint(null);
      const song = result.result;
      this.trackTitle = song ? song.artist + ' · ' + song.title : '';
      if (!song) {
        this.message = 'No AudD match.';
        return;
      }
      this.message = 'Identified by AudD';
      this.onChange();
      await this.lookup(
        { id: song.spotify?.id, title: song.title, artist: song.artist, duration: song.spotify?.duration_ms },
        task
      );
    });
  }
}
