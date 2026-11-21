import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';

const tab = (page, name) => page.locator('#tabs').getByRole('button', { name, exact: true });
const button = (page, name) => page.getByRole('button', { name, exact: true });
import { musicFile } from './audio-fixture.mjs';
const open = async (page) => {
  await page.goto('./');
  await button(page, 'Wormhole').waitFor();
};
test('the graphics error leaves audio and playback controls accessible', async ({ page }) => {
  await page.addInitScript(() => {
    const getContext = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (type, ...options) {
      return type === 'webgl2' ? null : getContext.call(this, type, ...options);
    };
  });
  await open(page);
  await expect(page.locator('#render-error')).toContainText('WebGL 2 is unavailable');
  await tab(page, 'Audio').click();
  await page.getByLabel('Source', { exact: true }).selectOption('file');
  await page.locator('#file').setInputFiles(await musicFile());
  await button(page, 'Play track').click();
  await expect(button(page, 'Pause track')).toBeVisible();
  await expect.poll(() => page.locator('#track-time').textContent()).not.toMatch(/^0:00 /);
});

test('pattern tuning, saved looks, transitions, effects, themes and fit on a phone', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.setViewportSize({ width: 393, height: 852 });
  await open(page);
  for (const name of ['Kaleidoscope', 'Mandelbrot', 'Wormhole', 'Kaleido']) {
    await button(page, name).click();
    await expect(page.locator('#stage-title')).toHaveText(name);
  }
  await button(page, 'Help for Mirror segments').click();
  await expect(page.locator('dialog')).toBeVisible();
  await button(page, 'Close').click();
  await button(page, 'Color').click();
  await button(page, 'Ember').click();
  await tab(page, 'Settings').click();
  await button(page, 'Effects').click();
  await page.getByLabel('Global effects').check();
  await tab(page, 'Looks').click();
  await page.getByLabel('Save this look').fill('Test <look>');
  await button(page, 'Save look').click();
  await button(page, 'Favorite Test <look>').click();
  await button(page, 'Rename').click();
  await page.getByRole('textbox', { name: 'Rename look' }).fill('Morning');
  await page.locator('dialog').getByRole('button', { name: 'Save' }).click();
  await button(page, 'Cue').click();
  await expect(page.locator('#cue-message')).toContainText('Morning');
  await button(page, 'Apply now').click();
  await button(page, 'Dark theme').click();
  await page.reload();
  await expect(button(page, 'Dark theme')).toHaveAttribute('aria-pressed', 'true');
  await tab(page, 'Looks').click();
  await expect(page.locator('.look')).toContainText('Morning');
  await button(page, 'Wormhole').click();
  await button(page, 'Play').click();
  await expect(page.locator('#stage-title')).toHaveText('Kaleido');
  await expect(page.locator('#render-error')).toBeHidden();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(errors).toEqual([]);
});
test('local analysis, playback, waveform seeking, soundtrack recording and diagnostic export', async ({
  page,
}, testInfo) => {
  await open(page);
  await tab(page, 'Audio').click();
  await page.getByLabel('Source', { exact: true }).selectOption('file');
  await page.locator('#file').setInputFiles(await musicFile());
  await expect(page.locator('#audio-status')).toHaveText('Ready to play', { timeout: 30000 });
  await expect(page.locator('#controls')).toContainText('128 BPM');
  await button(page, 'Play track').click();
  await expect(button(page, 'Pause track')).toBeVisible();
  await expect.poll(() => page.locator('#track-time').textContent()).not.toMatch(/^0:00 /);
  await page.getByLabel('Track waveform', { exact: true }).check();
  const wave = page.locator('#wave');
  await wave.focus();
  await wave.press('ArrowRight');
  await expect.poll(async () => Number(await wave.getAttribute('aria-valuenow'))).toBeGreaterThanOrEqual(5);
  await button(page, 'Record session').click();
  await expect(page.getByLabel('Source', { exact: true })).toBeDisabled();
  await expect(button(page, 'Forward 10 seconds')).toBeDisabled();
  await page.waitForTimeout(1800);
  const saved = page.waitForEvent('download');
  await button(page, 'Stop recording').click();
  const video = await saved;
  expect(video.suggestedFilename()).toMatch(/\.(mp4|webm)$/);
  await video.saveAs(testInfo.outputPath('recording.' + video.suggestedFilename().split('.').at(-1)));
  await button(page, 'Pause track').click();
  await tab(page, 'Settings').click();
  await button(page, 'Diagnostics').click();
  await button(page, 'Freeze').click();
  const csvEvent = page.waitForEvent('download');
  await button(page, 'Export CSV').click();
  const csv = await readFile(await (await csvEvent).path(), 'utf8');
  expect(csv).toMatch(/^seconds,bpm,beat_position,/);
  expect(csv.trim().split('\n').length).toBeGreaterThan(5);
  await button(page, 'Clear').click();
  await expect(page.locator('#diagnostic-readout')).toHaveText('Waiting for timing samples');
});
test('microphone connects, pattern switch disconnects, and offline reload can analyze a track', async ({
  page,
  context,
}) => {
  await open(page);
  await tab(page, 'Audio').click();
  await button(page, 'Connect audio').click();
  await expect(page.locator('#audio-status')).toHaveText('Listening', { timeout: 20000 });
  await expect.poll(() => page.locator('#level').evaluate((el) => el.value)).toBeGreaterThan(0);
  await button(page, 'Wormhole').click();
  await expect(page.locator('#audio-status')).toHaveText('Disconnected');
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
    if (!navigator.serviceWorker.controller)
      await new Promise((r) =>
        navigator.serviceWorker.addEventListener('controllerchange', r, { once: true })
      );
  });
  await context.setOffline(true);
  await page.reload();
  await expect(button(page, 'Wormhole')).toHaveAttribute('aria-pressed', 'true');
  await tab(page, 'Audio').click();
  await page.getByLabel('Source', { exact: true }).selectOption('file');
  await page.locator('#file').setInputFiles(await musicFile());
  await expect(page.locator('#controls')).toContainText('128 BPM', { timeout: 30000 });
});

test('tab sharing handles cancelled, video-only and revoked grants without recording shared pixels', async ({
  page,
}, testInfo) => {
  // Synthetic display picker. A real desktop picker requires the browser's native consent dialog.
  await page.addInitScript(() => {
    window.shareMode = 'cancel';
    const NativeRecorder = window.MediaRecorder;
    window.MediaRecorder = class extends NativeRecorder {
      constructor(...args) {
        super(...args);
        window.encodedChunks = 0;
        this.addEventListener('dataavailable', (event) => {
          if (event.data.size) window.encodedChunks++;
        });
      }
    };
    navigator.mediaDevices.getDisplayMedia = async () => {
      if (window.shareMode === 'cancel') throw new DOMException('Cancelled', 'NotAllowedError');
      const surface = (window.fixtureSurface = document.createElement('canvas'));
      surface.width = 32;
      surface.height = 32;
      const painted = surface.getContext('2d');
      painted.fillStyle = '#feff01';
      painted.fillRect(0, 0, 32, 32);
      const stream = surface.captureStream(10);
      if (window.shareMode === 'audio') {
        const context = new AudioContext();
        await context.resume();
        const oscillator = context.createOscillator();
        const bus = context.createMediaStreamDestination();
        oscillator.connect(bus);
        oscillator.start();
        stream.addTrack(bus.stream.getAudioTracks()[0]);
        window.fixtureContext = context;
      }
      window.fixtureStream = stream;
      return stream;
    };
  });
  await open(page);
  await tab(page, 'Audio').click();
  await page.getByLabel('Source', { exact: true }).selectOption('system');
  await button(page, 'Connect audio').click();
  await expect(page.locator('#audio-status')).toContainText('declined');
  await page.evaluate(() => {
    window.shareMode = 'video';
  });
  await button(page, 'Connect audio').click();
  await expect(page.locator('#audio-status')).toContainText('No audio was shared');
  expect(
    await page.evaluate(() => window.fixtureStream.getTracks().every((t) => t.readyState === 'ended'))
  ).toBe(true);
  await page.evaluate(() => {
    window.shareMode = 'audio';
  });
  await button(page, 'Connect audio').click();
  await expect(page.locator('#audio-status')).toHaveText('Listening');
  await expect.poll(() => page.locator('#level').evaluate((el) => el.value)).toBeGreaterThan(0.1);
  await button(page, 'Record session').click();
  await expect(button(page, 'Stop recording')).toBeVisible();
  await expect
    .poll(() => page.evaluate(() => window.encodedChunks), { timeout: 20000 })
    .toBeGreaterThanOrEqual(2);
  await page.evaluate(() => window.fixtureStream.getAudioTracks()[0].dispatchEvent(new Event('ended')));
  await expect(button(page, 'Record session')).toBeVisible();
  await expect(page.locator('#audio-status')).toContainText('Input ended');
  expect(
    await page.evaluate(() => window.fixtureStream.getTracks().every((t) => t.readyState === 'ended'))
  ).toBe(true);
  await tab(page, 'Settings').click();
  await expect(page.locator('#view-video')).toBeVisible();
  const saved = page.waitForEvent('download');
  await page.locator('#last-video').click();
  const clip = await saved;
  await clip.saveAs(testInfo.outputPath('tab-recording.' + clip.suggestedFilename().split('.').at(-1)));
  const frames = await page.locator('#view-video').evaluate(async (link) => {
    const video = document.createElement('video');
    video.muted = true;
    video.src = link.href;
    const probe = document.createElement('canvas');
    probe.width = 32;
    probe.height = 32;
    const ctx = probe.getContext('2d', { willReadFrequently: true });
    const frames = [];
    const sample = () => {
      ctx.drawImage(video, 0, 0, 32, 32);
      const data = ctx.getImageData(0, 0, 32, 32).data;
      let yellow = 0,
        lit = 0;
      for (let i = 0; i < data.length; i += 4) {
        if (data[i] > 230 && data[i + 1] > 230 && data[i + 2] < 30) yellow++;
        if (Math.max(data[i], data[i + 1], data[i + 2]) > 30) lit++;
      }
      frames.push({ yellow, lit });
      video.requestVideoFrameCallback(sample);
    };
    const ended = new Promise((resolve, reject) => {
      video.onended = resolve;
      video.onerror = () => reject(new Error(video.error.message));
    });
    video.requestVideoFrameCallback(sample);
    await video.play();
    await ended;
    return frames;
  });
  expect(frames.length).toBeGreaterThan(1);
  expect(Math.max(...frames.map((f) => f.yellow))).toBeLessThan(50);
  expect(frames.filter((f) => f.lit > 10).length).toBeGreaterThan(1);
});
