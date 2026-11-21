import http from 'node:http';
import https from 'node:https';
import { loadBrowserApp } from './browser-app.ts';

export class ParticipantError extends Error {
  code: number;
  constructor(code: number, message: string) {
    super(message);
    this.code = code;
  }
}

export function applicationUrl(value: string) {
  const url = new URL(value);
  const development = url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if ((!development && url.protocol !== 'https:') || url.username || url.password || url.search || url.hash) {
    throw new Error('PRISM_WEB_URL must be an HTTPS app URL or a localhost development URL.');
  }
  return url;
}

// This LAN listener has no access to operator commands, invitation creation, or the admin token.
export async function startBrowserParticipants(
  port: number, origins: ReadonlySet<string>, appDirectory: string, tls: { cert: Buffer; key: Buffer } | undefined,
  exchange: (packet: string, address: string, received: number) => object,
  now: () => number
) {
  const serveApp = await loadBrowserApp(appDirectory);
  const handle: http.RequestListener = async (req, res) => {
    const received = now();
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Vary', 'Origin');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    function reply(code: number, value?: object) {
      res.writeHead(code, { 'Content-Type': 'application/json' });
      res.end(value && JSON.stringify(value));
    }
    if (req.url !== '/crowd/exchange') { await serveApp(req, res); return; }
    const origin = req.headers.origin;
    if (origin && !origins.has(origin)) { reply(403); return; }
    if (origin) res.setHeader('Access-Control-Allow-Origin', origin);
    if (req.method === 'OPTIONS') {
      res.setHeader('Access-Control-Allow-Methods', 'POST');
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
      res.setHeader('Access-Control-Max-Age', '600');
      reply(204); return;
    }
    if (req.method !== 'POST') { reply(405); return; }
    if (req.headers['content-type']?.split(';')[0] !== 'application/json') { reply(415); return; }
    try {
      const chunks: Buffer[] = [];
      let bytes = 0;
      for await (const chunk of req) {
        bytes += chunk.length;
        if (bytes > 4096) { reply(413); return; }
        chunks.push(chunk);
      }
      reply(200, exchange(Buffer.concat(chunks).toString(), req.socket.remoteAddress ?? '', received));
    } catch (error) {
      reply(error instanceof ParticipantError ? error.code : 400);
    }
  };
  const server = tls ? https.createServer(tls, handle) : http.createServer(handle);
  server.headersTimeout = 5000;
  server.requestTimeout = 5000;
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '0.0.0.0', () => { server.removeListener('error', reject); resolve(); });
  });
  return server;
}
