import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { buildVisualAssets } from '../scripts/visual-assets.mjs';
import { BeatMotion, RenderQuality } from '../src/render-motion.js';

test('shared visual assets preserve stable scene IDs, look defaults and native shaders', async (t) => {
  const out = await mkdtemp(join(tmpdir(), 'prism-visual-assets-'));
  t.after(() => rm(out, { recursive: true, force: true }));
  const catalog = await buildVisualAssets(out);
  assert.deepEqual(catalog, JSON.parse(await readFile(join(out, 'assets/visuals.json'), 'utf8')));
  assert.deepEqual(
    catalog.scenes.map((s) => s.id),
    [1, 2, 7, 9]
  );
  const fractal = catalog.scenes.find((s) => s.key === 'KALEIDOSCOPE');
  assert.equal(fractal.defaults.scene, fractal.id);
  assert.equal(fractal.defaults.downbeatFlash, 0);
  assert.equal(fractal.defaults.colorSpeed, 0.025);
  assert.equal(catalog.palettes[fractal.defaults.palette].key, 'SPECTRUM');
  assert.deepEqual(catalog.ranges.symmetry, [3, 20]);
  for (const name of [
    'visualizer.vert',
    'visualizer.frag',
    'post_mix.frag',
    'post_blur.frag',
    'post_composite.frag',
  ]) {
    const original = new URL('../../app/src/main/assets/shaders/' + name, import.meta.url);
    assert.deepEqual(await readFile(join(out, 'assets/shaders', name)), await readFile(original));
  }
});

test('a downbeat effect fires on a continuous grid crossing and disarms on seeking or lost confidence', () => {
  const s = {
    audioAmount: 1,
    audioEnabled: true,
    syncOffsetMs: 0,
    pulseLength: 0.28,
    beatImpact: 1,
    barImpact: 0.9,
    flowImpact: 0.5,
    textureAmount: 0,
    downbeatFlash: 1,
    downbeatInvert: 1,
    invertFadeMs: 180,
  };
  const motion = new BeatMotion();
  const at = (now, beat, changes) =>
    motion.at(now, s, {
      rhythm: {
        timestamp: now,
        position: beat,
        bpm: 120,
        beatsPerBar: 4,
        barOffset: 0,
        locked: true,
        confidence: 1,
        barConfidence: 1,
        barLocked: true,
        signalPresent: true,
        lastBeatTime: now,
        conflict: 'NONE',
        ...changes,
      },
    });
  assert.equal(at(1.99, 3.98).flash, 0);
  assert.equal(at(2, 4).flash, 1);
  assert.equal(at(2.1, 4.2).flash, 0);
  assert(at(2.12, 4.24).inversion > 0);
  assert.equal(at(2.13, 8).flash, 0);
  assert.equal(at(2.14, 8.02, { signalPresent: false }).beat, 0);
  assert.equal(at(2.15, 12).flash, 0);
});

test('adaptive resolution lowers pixel cost under sustained load and restores it gradually', () => {
  const quality = new RenderQuality();
  const sample = (fps, seconds, enabled = true) => {
    let scale;
    for (let i = 0; i < fps * seconds; i++) scale = quality.sample(1 / fps, 60, enabled, false);
    return scale;
  };
  assert.equal(sample(25, 2), 1);
  assert.equal(sample(25, 2), 0.85);
  assert(sample(60, 7) <= 0.85);
  assert.equal(sample(60, 30), 1);
  assert(sample(20, 8) < 1);
  assert.equal(sample(20, 1, false), 1);
});
