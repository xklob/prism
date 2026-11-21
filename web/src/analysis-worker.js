import { WebDsp } from '../dsp/prism-dsp.mjs';
import * as ort from '../dsp/ort.wasm.min.mjs';

const rate = 48_000;
const block = 960;
const uninformative = new Float32Array([0, 0, 1]);
const assets = new URL('../dsp/', import.meta.url);
ort.env.logLevel = 'error';
ort.env.wasm.wasmPaths = assets.href;
ort.env.wasm.numThreads = 1;
ort.env.wasm.proxy = false;

let resources;
let analyzer;
let models;
let mode;
let options;
let lastTime;
let hint;
let cue;
let failed = false;

async function asset(name) {
  const response = await fetch(new URL(name, assets));
  if (!response.ok)
    throw new Error('The audio analysis files could not be loaded. Reload prism and try again.');
  return response.arrayBuffer();
}

class Model {
  constructor(session) {
    this.session = session;
    this.reset();
  }

  reset() {
    this.hidden = new ort.Tensor('float32', new Float32Array(300), [2, 1, 150]);
    this.cell = new ort.Tensor('float32', new Float32Array(300), [2, 1, 150]);
  }

  async predict(features) {
    const output = await this.session.run({
      features: new ort.Tensor('float32', features, [1, 1, 272]),
      hidden: this.hidden,
      cell: this.cell,
    });
    this.hidden = output.next_hidden;
    this.cell = output.next_cell;
    const probabilities = output.probabilities.data;
    if (!probabilities.every((value) => Number.isFinite(value) && value >= 0 && value <= 1)) {
      throw new Error('The audio model returned an invalid prediction.');
    }
    return probabilities;
  }
}

async function load() {
  const [filters, primary, general] = await Promise.all([
    asset('filters.f32'),
    asset('beatnet.onnx'),
    asset('beatnet-general.onnx'),
  ]);
  // The central worker owns the recurrent state. No nested thread or cross-origin isolation is required.
  const config = { executionProviders: ['wasm'] };
  const first = await ort.InferenceSession.create(new Uint8Array(primary), config);
  const second = await ort.InferenceSession.create(new Uint8Array(general), config);
  return { filters: new Float32Array(filters), models: [new Model(first), new Model(second)] };
}

function configure(next = {}) {
  options = { ...options, ...next };
  analyzer.configure(options.meter, options.phraseBars, options.sensitivity);
}

function reset(filters) {
  models.forEach((model) => model.reset());
  analyzer = new WebDsp(filters, mode === 'file' || options.digital, mode === 'file');
  configure();
  if (hint) analyzer.hint(hint.bpm, hint.key);
  if (cue?.armed) analyzer.armCue(true, cue.offsetMs || 0);
}

function validate(channels, file) {
  if (
    !Array.isArray(channels) ||
    channels.length < 1 ||
    channels.length > 2 ||
    channels.some((channel) => !(channel instanceof Float32Array) || channel.length !== channels[0].length)
  ) {
    throw new Error('Choose a mono or stereo audio source.');
  }
  if (file && (channels[0].length < block || channels[0].length > rate * 3600)) {
    throw new Error('Choose a track between 20 milliseconds and one hour long.');
  }
  if (!file && channels[0].length !== block) throw new Error('An incomplete audio block was received.');
}

async function process(left, right, time, last) {
  const input = analyzer.prepare(left, right, time);
  if (input.reset) models.forEach((model) => model.reset());
  let primary = uninformative;
  let general = uninformative;
  if (input.features) {
    primary = await models[0].predict(input.features);
    general = await models[1].predict(input.features);
  }
  return analyzer.finish(primary, general, time + left.length / rate, last);
}

function frame(payload) {
  if (payload.cueFired) cue = undefined;
  self.postMessage({ type: 'frame', ...payload });
}

async function analyzeFile(channels) {
  validate(channels, true);
  let previous = performance.now();
  const length = channels[0].length;
  self.postMessage({ type: 'progress', fraction: 0 });
  for (let offset = 0; offset < length; offset += block) {
    const end = Math.min(offset + block, length);
    await process(
      channels[0].subarray(offset, end),
      channels[1]?.subarray(offset, end) ?? null,
      offset / rate,
      end === length
    );
    if (performance.now() - previous > 120) {
      self.postMessage({ type: 'progress', fraction: (0.98 * end) / length });
      previous = performance.now();
    }
  }
  self.postMessage({ type: 'file-ready', ...analyzer.finishFile() });
}

async function receive(message) {
  switch (message.type) {
    case 'start': {
      if (message.mode !== 'live' && message.mode !== 'file') throw new Error('Unknown audio source.');
      mode = message.mode;
      options = { meter: 0, phraseBars: 16, sensitivity: 1.4, digital: false, ...message.options };
      lastTime = undefined;
      cue = undefined;
      hint = undefined;
      resources ??= load();
      const loaded = await resources;
      models = loaded.models;
      reset(loaded.filters);
      self.postMessage({ type: 'ready' });
      return;
    }
    case 'pcm': {
      if (mode !== 'live') return;
      validate(message.channels, false);
      const time = message.time;
      if (!Number.isFinite(time)) throw new Error('The audio clock was unavailable.');
      if (lastTime !== undefined && Math.abs(time - lastTime - block / rate) > 0.035)
        reset((await resources).filters);
      lastTime = time;
      const started = performance.now();
      const result = await process(message.channels[0], message.channels[1] ?? null, time, false);
      frame({ ...result, inferenceMs: performance.now() - started });
      return;
    }
    case 'file':
      if (mode === 'file') await analyzeFile(message.channels);
      return;
    case 'sample':
      if (mode === 'file') frame({ ...analyzer.sample(message.position, message.now), epoch: message.epoch });
      return;
    case 'options':
      configure(message.options);
      return;
    case 'command':
      analyzer.command(message.action, message.time);
      return;
    case 'hint':
      hint = { bpm: Number(message.bpm) || 0, key: message.key || '' };
      analyzer.hint(hint.bpm, hint.key);
      return;
    case 'cue':
      cue = { armed: Boolean(message.armed), offsetMs: Number(message.offsetMs) || 0 };
      analyzer.armCue(cue.armed, cue.offsetMs);
      return;
    case 'clear-diagnostics':
      analyzer.clearDiagnostics();
      return;
    case 'diagnostics':
      self.postMessage({ type: 'diagnostics', ...analyzer.diagnostics() });
  }
}

// Recurrent predictions must finish in capture order. Terminating this worker cancels in-flight file analysis.
let pending = Promise.resolve();
self.onmessage = ({ data }) => {
  pending = pending
    .then(async () => {
      if (!failed) await receive(data);
    })
    .catch((error) => {
      failed = true;
      self.postMessage({ type: 'error', message: error.message || 'Audio analysis could not complete.' });
    });
};
