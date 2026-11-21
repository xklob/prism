import { expect, test } from '@playwright/test';
import { once } from 'node:events';
import { createServer } from 'node:http';

test('a static host that redirects index.html can reload prism offline under a subpath', async ({
  page,
  context,
  baseURL,
}) => {
  const prefix = '/prism/';
  const server = createServer(async (request, response) => {
    if (!request.url.startsWith(prefix)) return response.writeHead(404).end();
    if (request.url === prefix + 'index.html') return response.writeHead(307, { Location: prefix }).end();
    try {
      const asset = await fetch(new URL(request.url.slice(prefix.length), baseURL));
      response.writeHead(asset.status, { 'Content-Type': asset.headers.get('content-type') });
      response.end(Buffer.from(await asset.arrayBuffer()));
    } catch {
      response.writeHead(502).end();
    }
  });
  server.listen(0, '127.0.0.1');
  try {
    await once(server, 'listening');
    const url = 'http://127.0.0.1:' + server.address().port + prefix;
    await page.goto(url);
    await expect(page.getByRole('button', { name: 'Wormhole', exact: true })).toBeVisible();
    await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
    await context.setOffline(true);
    await page.reload();
    await page.getByRole('button', { name: 'Wormhole', exact: true }).click();
    await expect(page.locator('#stage-title')).toHaveText('Wormhole');
  } finally {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
});
