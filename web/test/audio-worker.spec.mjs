import { test, expect } from '@playwright/test';
import { fileURLToPath } from 'node:url';

const fixture = fileURLToPath(
  new URL('../../app/src/androidTest/assets/rhythm/drums128.pcm', import.meta.url)
);

test.beforeEach(async ({ page, baseURL }) => {
  // Use a blank same-origin document. The complete worker, real model binaries and WASM are served by the app.
  const harness = new URL('worker-test.html', baseURL || 'http://127.0.0.1:4173/').href;
  await page.route(harness, (route) =>
    route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>worker test</title>' })
  );
  await page.goto(harness);
  await page.evaluate(() => {
    window.audioWorker = new Worker('./src/analysis-worker.js', { type: 'module' });
    window.reply = (message, type) =>
      new Promise((resolve, reject) => {
        const worker = window.audioWorker;
        const clean = () => {
          worker.removeEventListener('message', receive);
          worker.removeEventListener('error', failed);
        };
        const failed = (event) => {
          clean();
          reject(new Error(event.message));
        };
        const receive = ({ data }) => {
          if (data.type === 'error') return failed(data);
          if (data.type === type) {
            clean();
            resolve(data);
          }
        };
        worker.addEventListener('message', receive);
        worker.addEventListener('error', failed);
        worker.postMessage(message);
      });
  });
});

test('the shipped models recover the Android fixture tempo, bar markers and waveform', async ({ page }) => {
  await page.route('**/fixture.pcm', (route) =>
    route.fulfill({ path: fixture, contentType: 'application/octet-stream' })
  );
  const { bpm, markers, waveform, sample, diagnostics, cleared } = await page.evaluate(async () => {
    await window.reply({ type: 'start', mode: 'file' }, 'ready');
    const pcm = new Int16Array(await (await fetch('fixture.pcm')).arrayBuffer());
    const channels = [Float32Array.from(pcm, (value) => value / 32768)];
    const grid = await window.reply({ type: 'file', channels }, 'file-ready');
    const sample = await window.reply({ type: 'sample', position: 10.525, now: 17 }, 'frame');
    const diagnostics = await window.reply({ type: 'diagnostics' }, 'diagnostics');
    window.audioWorker.postMessage({ type: 'clear-diagnostics' });
    const cleared = await window.reply({ type: 'diagnostics' }, 'diagnostics');
    return {
      bpm: grid.bpm,
      markers: grid.markers,
      waveform: [grid.peaks.length, Math.max(...grid.peaks)],
      sample: { rhythm: sample.rhythm, spectrum: Math.max(...sample.levels.spectrum) },
      diagnostics,
      cleared,
    };
  });
  expect(bpm).toBeCloseTo(128, 0);
  expect(markers.length).toBeGreaterThan(55);
  expect(markers.filter((marker) => marker.kind === 1).length).toBeGreaterThan(12);
  expect(waveform[0]).toBe(1024);
  expect(waveform[1]).toBeGreaterThan(0.8);
  expect(sample.rhythm.locked).toBe(true);
  expect(sample.rhythm.timestamp).toBe(17);
  expect(sample.spectrum).toBeGreaterThan(0);
  expect(diagnostics.samples).toHaveLength(1);
  expect(diagnostics.csv).toContain('match_error_ms');
  expect(cleared.samples).toHaveLength(0);
  expect(cleared.csv.trim().split('\n')).toHaveLength(1);
});

test('a silent local file produces no invented grid', async ({ page }) => {
  const result = await page.evaluate(async () => {
    await window.reply({ type: 'start', mode: 'file' }, 'ready');
    const grid = await window.reply({ type: 'file', channels: [new Float32Array(144000)] }, 'file-ready');
    const frame = await window.reply({ type: 'sample', position: 2, now: 25 }, 'frame');
    return {
      bpm: grid.bpm,
      markers: grid.markers.length,
      peak: Math.max(...grid.peaks),
      energy: frame.levels.energy,
      locked: frame.rhythm.locked,
      phrase: frame.phrase.bar,
    };
  });
  expect(result).toEqual({ bpm: 0, markers: 0, peak: 0, energy: 0, locked: false, phrase: null });
});

test('live stereo cancellation, manual alignment and one-shot phrase cues share the Android clock', async ({
  page,
}) => {
  const result = await page.evaluate(async () => {
    await window.reply(
      { type: 'start', mode: 'live', options: { meter: 3, phraseBars: 8, digital: true } },
      'ready'
    );
    const left = Float32Array.from(
      { length: 960 },
      (_, i) => 0.12 * Math.sin((2 * Math.PI * 100 * i) / 48000)
    );
    const right = Float32Array.from(left, (value) => -value);
    const command = (action, time) => window.audioWorker.postMessage({ type: 'command', action, time });
    let frame;
    let fired = 0;
    for (let i = 0; i < 780; i++) {
      const time = 10 + 0.02 * i;
      if (i === 10 || i === 35 || i === 60 || i === 85) command('tap', time);
      if (i === 110) command('phrase', time);
      if (i === 116) window.audioWorker.postMessage({ type: 'cue', armed: true });
      frame = await window.reply({ type: 'pcm', time, channels: [left, right] }, 'frame');
      if (frame.cueFired) fired++;
    }
    return {
      bpm: frame.rhythm.bpm,
      manual: frame.rhythm.manualTempo,
      meter: frame.rhythm.beatsPerBar,
      bar: frame.rhythm.barLocked,
      phrase: frame.phrase.manual,
      stereo: frame.cancelingStereo,
      energy: frame.levels.energy,
      fired,
    };
  });
  expect(result.bpm).toBeCloseTo(120, 3);
  expect(result.meter).toBe(3);
  expect(result.manual && result.bar && result.phrase && result.stereo).toBe(true);
  expect(result.energy).toBeGreaterThan(0.05);
  expect(result.fired).toBe(1);
});
