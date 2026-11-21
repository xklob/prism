const now = () => performance.now() / 1000;
export const sourceNames = { microphone: 'Microphone', system: 'Tab / system audio', file: 'Local file' };

export class AudioSession extends EventTarget {
  constructor(options, onPcm) {
    super();
    this.options = options;
    this.onPcm = onPcm;
    this.player = new Audio();
    this.player.preload = 'metadata';
    this.player.addEventListener('ended', () => this.playbackChanged());
    this.player.addEventListener('pause', () => this.playbackChanged());
    this.player.addEventListener('play', () => this.playbackChanged());
    this.generation = 0;
    this.phase = 'idle';
    this.source = null;
    this.message = '';
    this.track = null;
    this.frame = null;
    this.pending = 0;
    this.locked = false;
    this.lastSample = 0;
    this.mediaActions();
  }
  changed() {
    this.dispatchEvent(new Event('change'));
  }
  get active() {
    return this.phase === 'live' || this.phase === 'file-ready';
  }
  get playing() {
    return this.source === 'file' && this.active && !this.player.paused;
  }
  get position() {
    return this.source === 'file' ? this.player.currentTime || 0 : 0;
  }
  get duration() {
    return this.track?.duration || 0;
  }
  mediaActions() {
    const session = navigator.mediaSession;
    if (!session) return;
    for (const [action, handler] of Object.entries({
      play: () => this.play().catch(() => {}),
      pause: () => this.player.pause(),
      seekbackward: (e) => this.seek(this.position - (e.seekOffset || 10)),
      seekforward: (e) => this.seek(this.position + (e.seekOffset || 10)),
      seekto: (e) => this.seek(e.seekTime),
      previoustrack: () => this.seek(0),
      stop: () => {
        this.player.pause();
        this.seek(0);
      },
    }))
      try {
        session.setActionHandler(action, handler);
      } catch {
        /* Action availability varies by browser. */
      }
  }
  playbackChanged() {
    if (this.source !== 'file') return;
    if (!this.playing) this.frame = null;
    if (navigator.mediaSession) {
      navigator.mediaSession.playbackState = this.playing ? 'playing' : 'paused';
      if (this.duration && navigator.mediaSession.setPositionState) {
        try {
          navigator.mediaSession.setPositionState({
            duration: this.duration,
            position: Math.min(this.position, this.duration),
            playbackRate: 1,
          });
        } catch {}
      }
    }
    this.changed();
  }
  post(message) {
    this.worker?.postMessage(message);
  }
  setOptions() {
    this.post({ type: 'options', options: this.options(this.source) });
  }
  command(action) {
    if (this.phase === 'live') this.post({ type: 'command', action, time: now() });
  }
  cue(armed, offsetMs = 0) {
    this.armed = armed;
    this.offsetMs = offsetMs;
    this.post({ type: 'cue', armed, offsetMs });
  }
  diagnostics() {
    if (this.worker) this.post({ type: 'diagnostics' });
    else if (this.lastDiagnostics)
      this.dispatchEvent(new CustomEvent('diagnostics', { detail: this.lastDiagnostics }));
  }
  clearDiagnostics() {
    this.lastDiagnostics = null;
    this.post({ type: 'clear-diagnostics' });
  }
  stop(message = '') {
    ++this.generation;
    clearInterval(this.watchdog);
    this.startReject?.(new DOMException('Stopped', 'AbortError'));
    this.startReject = null;
    const previous = this.worker;
    if (previous && this.active) {
      const stopped = this.generation;
      const timeout = setTimeout(() => previous.terminate(), 1000);
      previous.onerror = () => {
        clearTimeout(timeout);
        previous.terminate();
      };
      previous.onmessage = ({ data }) => {
        if (data.type !== 'diagnostics') return;
        if (this.generation === stopped && this.phase === 'idle') {
          this.lastDiagnostics = data;
          this.dispatchEvent(new CustomEvent('diagnostics', { detail: data }));
        }
        clearTimeout(timeout);
        previous.terminate();
      };
      previous.postMessage({ type: 'diagnostics' });
    } else previous?.terminate();
    this.worker = null;
    if (this.tap) {
      this.tap.port.onmessage = null;
      this.tap.disconnect();
      this.tap = null;
    }
    this.inputNode?.disconnect();
    this.inputNode = null;
    this.stream?.getTracks().forEach((t) => {
      t.onended = null;
      t.stop();
    });
    this.stream = null;
    this.player.pause();
    this.player.removeAttribute('src');
    this.player.load();
    if (this.url) URL.revokeObjectURL(this.url);
    this.url = null;
    this.recordBus?.disconnect();
    this.recordBus?.stream.getTracks().forEach((track) => track.stop());
    this.recordBus = null;
    if (this.context) this.context.close().catch(() => {});
    this.context = null;
    if (navigator.mediaSession) {
      navigator.mediaSession.metadata = null;
      navigator.mediaSession.playbackState = 'none';
    }
    this.frame = null;
    this.track = null;
    this.source = null;
    this.phase = 'idle';
    this.message = message;
    this.pending = 0;
    this.changed();
  }
  begin(source) {
    if (this.locked) throw new Error('Stop recording before changing the input.');
    this.stop();
    this.lastDiagnostics = null;
    this.source = source;
    this.phase = 'starting';
    this.message = source === 'file' ? 'Opening track…' : 'Waiting for input…';
    this.progress = 0;
    this.changed();
    return this.generation;
  }
  fail(generation, error) {
    if (this.generation !== generation) return;
    const text =
      error.name === 'NotAllowedError'
        ? 'Access was declined. Connect again to choose an input.'
        : error.message || 'Audio could not start.';
    this.stop(text);
  }
  async contextForInput() {
    const Context = window.AudioContext || window.webkitAudioContext;
    if (!Context) throw new Error('This browser does not provide Web Audio.');
    const ctx = new Context({ sampleRate: 48000, latencyHint: 'interactive' });
    this.context = ctx;
    await ctx.resume();
    if (ctx.sampleRate !== 48000) throw new Error('This browser could not open a 48 kHz audio context.');
    return ctx;
  }
  startWorker(generation) {
    const worker = new Worker(new URL('./analysis-worker.js', import.meta.url), { type: 'module' });
    this.worker = worker;
    return new Promise((resolve, reject) => {
      this.startReject = reject;
      const error = (message) => {
        if (this.generation !== generation) return;
        this.startReject = null;
        reject(new Error(message));
        this.dispatchEvent(new Event('inputlost'));
        this.stop(message);
      };
      worker.onerror = (event) => error(event.message || 'The audio analysis engine could not load.');
      worker.onmessage = ({ data }) => {
        if (this.generation !== generation) return;
        switch (data.type) {
          case 'ready':
            this.startReject = null;
            if (this.armed) this.cue(true, this.offsetMs);
            resolve();
            break;
          case 'error':
            error(data.message);
            break;
          case 'progress':
            this.progress = data.fraction;
            this.dispatchEvent(new Event('progress'));
            break;
          case 'file-ready':
            this.track = data;
            this.phase = 'file-ready';
            this.message = 'Ready to play';
            this.changed();
            break;
          case 'frame':
            this.pending = Math.max(0, this.pending - 1);
            if (this.source === 'file' && !this.playing) break;
            this.frame = data;
            this.dispatchEvent(new CustomEvent('frame', { detail: data }));
            break;
          case 'diagnostics':
            this.dispatchEvent(new CustomEvent('diagnostics', { detail: data }));
            break;
        }
      };
      worker.postMessage({
        type: 'start',
        mode: this.source === 'file' ? 'file' : 'live',
        options: this.options(this.source),
      });
    });
  }
  async connect(source) {
    const g = this.begin(source);
    try {
      const devices = navigator.mediaDevices;
      if (!devices || !(source === 'system' ? devices.getDisplayMedia : devices.getUserMedia)) {
        throw new Error(
          source === 'system'
            ? 'Tab audio sharing is unavailable here. Choose a local file or microphone.'
            : 'Microphone input needs a supported browser on HTTPS or localhost.'
        );
      }
      const media =
        source === 'system'
          ? devices.getDisplayMedia({
              video: true,
              audio: true,
              systemAudio: 'include',
              selfBrowserSurface: 'exclude',
            })
          : devices.getUserMedia({
              audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
              video: false,
            });
      // Keep prompts tied to the click. A cancelled/superseded request releases even a late grant.
      const grant = media.then((stream) => {
        if (g !== this.generation) {
          stream.getTracks().forEach((t) => t.stop());
          throw new DOMException('Stopped', 'AbortError');
        }
        this.stream = stream;
        if (!stream.getAudioTracks().length)
          throw new Error('No audio was shared. Select a tab or screen with “Share audio” enabled.');
        for (const track of stream.getTracks())
          track.onended = () => {
            if (g === this.generation) {
              this.dispatchEvent(new Event('inputlost'));
              this.stop('Input ended. Connect again to resume.');
            }
          };
        return stream;
      });
      const [stream, ctx] = await Promise.all([grant, this.contextForInput()]);
      if (g !== this.generation) return;
      this.message = 'Loading beat models…';
      this.changed();
      await Promise.all([
        this.startWorker(g),
        ctx.audioWorklet.addModule(new URL('./capture-worklet.js', import.meta.url)),
      ]);
      if (g !== this.generation) return;
      this.tap = new AudioWorkletNode(ctx, 'prism-pcm', { numberOfOutputs: 0, channelCountMode: 'max' });
      this.inputNode = ctx.createMediaStreamSource(new MediaStream(stream.getAudioTracks()));
      this.recordBus = ctx.createMediaStreamDestination();
      this.inputNode.connect(this.recordBus);
      this.tap.port.onmessage = ({ data }) => {
        if (g !== this.generation) return;
        this.lastPcm = performance.now();
        this.onPcm?.(data.channels);
        // A slow worker must never accumulate unbounded microphone samples.
        if (this.pending >= 7) return;
        const time = now() - (ctx.currentTime - data.start);
        this.pending++;
        this.worker.postMessage(
          { type: 'pcm', channels: data.channels, time },
          data.channels.map((c) => c.buffer)
        );
      };
      this.inputNode.connect(this.tap);
      this.phase = 'live';
      this.message = 'Listening';
      this.changed();
      this.lastPcm = performance.now();
      this.watchdog = setInterval(() => {
        if (g === this.generation && !document.hidden && performance.now() - this.lastPcm > 4500) {
          this.dispatchEvent(new Event('inputlost'));
          this.stop('Audio input stalled. Connect again to resume.');
        }
      }, 1000);
    } catch (error) {
      this.fail(g, error);
    }
  }
  async loadFile(file) {
    if (!file) return;
    const g = this.begin('file');
    this.lastFile = file;
    try {
      if (file.size > 1024 ** 3) throw new Error('Choose an audio file smaller than 1 GB.');
      const ctx = await this.contextForInput();
      if (g !== this.generation) return;
      // Read duration before allocating decoded PCM, including compressed tracks with a small file size.
      this.url = URL.createObjectURL(file);
      const player = this.player.cloneNode(false);
      this.player = player;
      player.preload = 'metadata';
      const metadata = new Promise((resolve, reject) => {
        player.onloadedmetadata = resolve;
        player.onerror = () => reject(new Error('Choose a track this browser can play.'));
        player.onabort = () => reject(new DOMException('Stopped', 'AbortError'));
      });
      player.src = this.url;
      await metadata;
      if (g !== this.generation) return;
      if (Number.isFinite(player.duration) && player.duration > 3600)
        throw new Error('Choose a mono or stereo track up to one hour long.');
      this.message = 'Decoding track…';
      this.changed();
      const bytes = await file.arrayBuffer();
      if (g !== this.generation) return;
      const buffer = await ctx.decodeAudioData(bytes);
      if (g !== this.generation) return;
      if (!buffer.length || buffer.duration > 3600 || buffer.numberOfChannels > 2)
        throw new Error('Choose a mono or stereo track up to one hour long.');
      player.onloadedmetadata = null;
      player.onabort = null;
      for (const name of ['play', 'pause', 'ended'])
        player.addEventListener(name, () => this.playbackChanged());
      player.onerror = () => {
        if (g === this.generation) this.fail(g, new Error('The browser could not play this file.'));
      };
      player.preload = 'auto';
      this.inputNode = ctx.createMediaElementSource(player);
      this.recordBus = ctx.createMediaStreamDestination();
      this.inputNode.connect(this.recordBus);
      this.inputNode.connect(ctx.destination);
      if ('MediaMetadata' in window && navigator.mediaSession)
        navigator.mediaSession.metadata = new MediaMetadata({ title: file.name });
      this.phase = 'analyzing';
      this.message = 'Analyzing track…';
      this.changed();
      await this.startWorker(g);
      if (g !== this.generation) return;
      const channels = Array.from({ length: buffer.numberOfChannels }, (_, i) =>
        buffer.getChannelData(i).slice()
      );
      this.worker.postMessage(
        { type: 'file', channels },
        channels.map((c) => c.buffer)
      );
    } catch (error) {
      this.fail(g, error);
    }
  }
  async play() {
    if (this.phase !== 'file-ready') return;
    if (this.player.ended && this.locked) return;
    if (this.player.ended) this.seek(0);
    await this.context.resume();
    await this.player.play();
  }
  seek(value) {
    if (this.locked || this.phase !== 'file-ready' || !Number.isFinite(value)) return;
    this.player.currentTime = Math.max(0, Math.min(this.duration, value));
    this.frame = null;
    if (this.armed) {
      this.cue(false);
      this.cue(true, this.options('file').syncOffsetMs);
    }
    this.lastSample = 0;
    this.playbackChanged();
  }
  sample(timeMs) {
    if (!this.playing || this.pending || timeMs - this.lastSample < 19) return;
    this.lastSample = timeMs;
    this.pending++;
    this.post({ type: 'sample', position: this.position, now: timeMs / 1000 });
  }
}
