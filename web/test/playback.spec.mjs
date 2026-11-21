import { test, expect } from '@playwright/test';
import { musicFile } from './audio-fixture.mjs';

test('the audible file clock advances, pauses and seeks on this browser engine', async ({ page }) => {
  await page.addInitScript(() => {
    const resume = AudioContext.prototype.resume;
    window.outputResumeCalls = 0;
    AudioContext.prototype.resume = function () {
      window.outputResumeCalls++;
      return resume.call(this);
    };
  });
  await page.goto('./');
  await page.locator('[data-tab=Audio]').click();
  await page.getByLabel('Source', { exact: true }).selectOption('file');
  await page.locator('#file').setInputFiles(await musicFile());
  await expect(page.locator('#audio-status')).toHaveText('Ready to play', { timeout: 30000 });
  expect(await page.evaluate(() => window.outputResumeCalls)).toBe(0);
  await page.getByRole('button', { name: 'Play track', exact: true }).click();
  const seconds = async () => {
    const [m, s] = (await page.locator('#track-time').textContent())
      .split('/')[0]
      .trim()
      .split(':')
      .map(Number);
    return m * 60 + s;
  };
  await expect.poll(seconds, { timeout: 10000 }).toBeGreaterThanOrEqual(2);
  await page.getByRole('button', { name: 'Pause track', exact: true }).click();
  const stopped = await seconds();
  await page.waitForTimeout(1100);
  expect(await seconds()).toBe(stopped);
  await page.getByRole('button', { name: 'Forward 10 seconds' }).click();
  await expect.poll(seconds).toBe(stopped + 10);
  await page.getByRole('button', { name: 'Play track', exact: true }).click();
  await expect.poll(seconds).toBeGreaterThan(stopped + 10);
});
