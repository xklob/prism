import { expect, test } from '@playwright/test';

const sequence = [
  'ArrowUp',
  'ArrowUp',
  'ArrowDown',
  'ArrowDown',
  'ArrowLeft',
  'ArrowRight',
  'ArrowLeft',
  'ArrowRight',
  'b',
  'a',
];
async function enterCode(page) {
  for (const key of sequence) await page.keyboard.press(key);
}

test('the Standard Design rainbow works with touch, key sequence, reduced motion and Immerse', async ({
  page,
}) => {
  await page.goto('./');
  await page.getByRole('button', { name: 'Wormhole', exact: true }).waitFor();
  const rainbow = page.getByRole('button', { name: 'Rainbow effect', exact: true });
  const settings = page.getByRole('button', { name: 'Settings', exact: true });
  const root = page.locator('html');
  await settings.click();
  await expect(rainbow).toHaveAttribute('aria-pressed', 'false');
  await expect(page.locator('.brand .slash')).toHaveAttribute('aria-hidden', 'true');
  await enterCode(page);
  await expect(rainbow).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Tune', exact: true }).click();
  await settings.click();
  await expect(rainbow).toHaveAttribute('aria-pressed', 'true');
  await rainbow.click();
  await expect(root).not.toHaveClass(/psychedelic/);
  await rainbow.click();
  await page.emulateMedia({ reducedMotion: 'reduce' });
  expect(await page.locator('[data-konami-ink]').evaluate((el) => getComputedStyle(el).animationName)).toBe(
    'none'
  );
  expect(await page.locator('body').evaluate((el) => getComputedStyle(el, '::before').animationName)).toBe(
    'none'
  );
  await enterCode(page);
  await expect(rainbow).toHaveAttribute('aria-pressed', 'false');
  await page.locator('select').first().focus();
  await enterCode(page);
  await expect(root).not.toHaveClass(/psychedelic/);
  await rainbow.click();
  await page.getByRole('button', { name: 'Immerse', exact: true }).click();
  await expect(rainbow).toBeHidden();
  expect(await page.locator('body').evaluate((el) => getComputedStyle(el, '::before').content)).toBe('none');
  await page.keyboard.press('Escape');
  await expect(rainbow).toBeVisible();
  await expect(root).not.toHaveClass(/psychedelic/);
});

test('display edits can be cancelled or applied and live tuning exposes units', async ({ page }) => {
  await page.goto('./');
  const settings = page.getByRole('button', { name: 'Settings', exact: true });
  await settings.click();
  const frameRate = page.getByLabel('Frame-rate target');
  await frameRate.selectOption('30');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(frameRate).toHaveValue('60');
  await frameRate.selectOption('30');
  await page.getByRole('button', { name: 'Apply', exact: true }).click();
  await page.reload();
  await settings.click();
  await expect(frameRate).toHaveValue('30');
  await page.getByRole('button', { name: 'Audio', exact: true }).click();
  const offset = page.getByRole('slider', { name: 'Sync offset' });
  await offset.focus();
  await page.keyboard.press('ArrowRight');
  await expect(offset).toHaveAttribute('aria-valuetext', '5 ms');
  await expect(page.locator('output[for="sl-syncOffsetMs"]')).toHaveText('5 ms');
});
