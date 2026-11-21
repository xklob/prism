import dgram from 'node:dgram';
import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { networkInterfaces } from 'node:os';
import { readFile } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import QRCode from 'qrcode';
import { Show } from './show.ts';
import type { AudioInput } from './show.ts';
import { identity } from './wire.ts';
import { applicationUrl, ParticipantError, startBrowserParticipants } from './browser-participants.ts';

export const now = (): number => Math.round(performance.now() * 1000) / 1000;
const secret = () => randomBytes(24).toString('base64url');
const equal = (a: unknown, b: string) => typeof a === 'string' && Buffer.byteLength(a) === Buffer.byteLength(b) &&
  timingSafeEqual(Buffer.from(a), Buffer.from(b));
type Endpoint = { kind: 'udp'; address: string; port: number } | { kind: 'web'; address: string };
type Participant = { endpoint: Endpoint; name: string; seen: number; source: boolean;
  health: Record<string, unknown>; rateAt: number; rateCount: number };
type Message = { id: string; v: number; token: string; type: string; name?: unknown; t1: number; nonce: string;
  health?: Record<string, unknown>; audio?: AudioInput; sid?: string };
// Windows can reserve TCP ranges that remain available to the UDP allocator.
async function bindShowPort(port: number, openBrowser: () => ReturnType<typeof startBrowserParticipants>) {
  for (let attempt = 1; ; attempt++) {
    const browser = await openBrowser();
    const boundPort = (browser.address() as AddressInfo).port;
    const udp = dgram.createSocket('udp4');
    try {
      await new Promise<void>((resolve, reject) => {
        udp.once('error', reject);
        udp.bind(boundPort, '0.0.0.0', () => { udp.removeListener('error', reject); resolve(); });
      });
      return { udp, browser, boundPort };
    } catch (error) {
      udp.close();
      await new Promise<void>(resolve => browser.close(() => resolve()));
      const code = (error as NodeJS.ErrnoException).code;
      if (port !== 0 || attempt >= 8 || (code !== 'EADDRINUSE' && code !== 'EACCES')) throw error;
    }
  }
}

export async function startController(options: { udpPort?: number; httpPort?: number; host?: string; webUrl?: string; appDirectory?: string } = {}) {
  const dashboard = await readFile(fileURLToPath(new URL('../web/index.html', import.meta.url)));
  const browserScript = await readFile(fileURLToPath(new URL('../web/app.js', import.meta.url)));
  const udpPort = options.udpPort ?? Number(process.env.PRISM_UDP_PORT ?? 48761);
  const httpPort = options.httpPort ?? Number(process.env.PRISM_HTTP_PORT ?? 48760);
  const customUrl = options.webUrl ?? process.env.PRISM_WEB_URL;
  const appUrl = customUrl ? applicationUrl(customUrl) : undefined;
  const appDirectory = options.appDirectory ?? process.env.PRISM_APP_DIR ?? fileURLToPath(new URL('../app/', import.meta.url));
  const cert = process.env.PRISM_TLS_CERT, key = process.env.PRISM_TLS_KEY;
  if (!!cert !== !!key) throw new Error('Configure both PRISM_TLS_CERT and PRISM_TLS_KEY for HTTPS.');
  const tls = cert && key ? { cert: await readFile(cert), key: await readFile(key) } : undefined;
  const show = new Show(), keys = identity(), sid = randomUUID();
  const viewerToken = secret(), sourceToken = secret(), adminToken = secret();
  const clients = new Map<string, Participant>();
  let seq = 0, audioOwner: string | undefined, rejected = 0, lastPacketBytes = 0;
  let socketError = '';
  const message = (type: string, body: object) => ({ v: 1, type, sid, ...body });
  const state = (time: number) => message('state', { seq: ++seq, sent: time, validUntil: time + 2000, ...show.snapshot(time) });
  let browserState = keys.webEnvelope(state(now()));
  const send = (packet: Buffer, peer: { port: number; address: string }) =>
    udp.send(packet, peer.port, peer.address, (error) => { if (error) socketError = error.message; });

  function participant(m: Message, endpoint: Endpoint, received: number) {
    const source = equal(m.token, sourceToken);
    if (!source && !equal(m.token, viewerToken)) throw new ParticipantError(403, 'Invalid invitation.');
    if (m.v !== 1 || typeof m.id !== 'string' || !/^[a-zA-Z0-9-]{1,64}$/.test(m.id)) {
      throw new ParticipantError(400, 'Invalid participant.');
    }
    if ((m.type === 'sync' || m.type === 'exchange') &&
        (!Number.isFinite(m.t1) || typeof m.nonce !== 'string' || !m.nonce.length || m.nonce.length > 64)) {
      throw new ParticipantError(400, 'Invalid clock request.');
    }
    const id = endpoint.kind + ':' + m.id;
    let client = clients.get(id);
    if (client && client.source !== source) throw new ParticipantError(403, 'Invitation role mismatch.');
    if (m.type === 'leave') {
      clients.delete(id);
      if (audioOwner === id) audioOwner = undefined;
      return;
    }
    if (!client) {
      if (clients.size >= 50) throw new ParticipantError(503, 'This show already has 50 participants.');
      client = { endpoint, name: String(m.name ?? 'prism phone').slice(0, 64), seen: received, source,
        health: {}, rateAt: received, rateCount: 0 };
      clients.set(id, client);
    }
    if (received - client.rateAt > 1000) { client.rateAt = received; client.rateCount = 0; }
    if (++client.rateCount > 40) throw new ParticipantError(429, 'Too many timing requests.');
    client.endpoint = endpoint; client.seen = received;
    if (m.health && typeof m.health === 'object') {
      const health = m.health;
      client.health = Object.fromEntries(['state', 'clockMs', 'fps', 'missed', 'frames', 'phraseBar', 'beatConfidence']
        .filter(k => ['string', 'number', 'boolean'].includes(typeof health[k]))
        .map(k => [k, typeof health[k] === 'string' ? health[k].slice(0, 80) : health[k]]));
    }
    if (m.audio && source) {
      if (audioOwner && audioOwner !== id) throw new ParticipantError(409, 'Another device owns the audio input. Disconnect it first.');
      try { show.receiveAudio(m.audio, received); }
      catch { throw new ParticipantError(422, 'The audio timing is stale or invalid.'); }
      audioOwner = id;
    }
  }
  function sync(m: Message, received: number) {
    return message('sync', { id: m.id, nonce: m.nonce, t1: m.t1, t2: received, t3: now() });
  }
  const interfaces = () => Object.entries(networkInterfaces()).flatMap(([name, entries]) =>
    (entries ?? []).filter(n => n.family === 'IPv4' && !n.internal)
      .map(n => ({ name, address: n.address })));
  let advertisedHost = options.host ?? process.env.PRISM_HOST ?? interfaces()[0]?.address ?? '127.0.0.1';
  const appOrigins = new Set<string>(appUrl ? [appUrl.origin] : []);
  const { udp, browser, boundPort } = await bindShowPort(udpPort, () =>
    startBrowserParticipants(udpPort, appOrigins, appDirectory, tls, (packet, address, received) => {
      const m: Message = JSON.parse(packet);
      if (m.sid !== sid) throw new ParticipantError(403, 'Expired invitation.');
      if (!['exchange', 'leave'].includes(m.type)) throw new ParticipantError(400, 'Unknown request.');
      if (m.audio && !equal(m.token, sourceToken)) throw new ParticipantError(403, 'Audio input requires a source invitation.');
      let errorEnvelope;
      try { participant(m, { kind: 'web', address }, received); }
      catch (error) {
        if (!(error instanceof ParticipantError) || ![409, 422].includes(error.code)) throw error;
        errorEnvelope = keys.webEnvelope(message('error', { message: error.message }));
      }
      if (m.type === 'leave') return {};
      return { sync: keys.webEnvelope(sync(m, received)), state: browserState, error: errorEnvelope };
    }, now));
  const localAppUrl = () => new URL((tls ? 'https://' : 'http://') + advertisedHost + ':' + boundPort + '/');
  appOrigins.add(localAppUrl().origin);
  udp.on('error', error => { socketError = error.message; });
  udp.on('message', (packet, remote) => {
    const received = now();
    try {
      if (packet.length > 4096) return;
      const m: Message = JSON.parse(packet.toString());
      if (!['sync', 'health', 'audio', 'leave'].includes(m.type)) return;
      if (m.type !== 'audio') delete m.audio;
      if (m.type !== 'health') delete m.health;
      participant(m, { kind: 'udp', address: remote.address, port: remote.port }, received);
      if (m.type === 'sync') send(keys.encode(sync(m, received)), remote);
    } catch (error) {
      if (error instanceof ParticipantError && [409, 503].includes(error.code)) {
        send(keys.encode(message('error', { message: error.message })), remote);
      }
      rejected++;
    }
  });
  const ticker = setInterval(() => {
    const time = now();
    for (const [id, client] of clients) if (time - client.seen > 5000) {
      clients.delete(id); if (audioOwner === id) audioOwner = undefined;
    }
    const schedule = state(time);
    const packet = keys.encode(schedule);
    browserState = keys.webEnvelope(schedule);
    lastPacketBytes = packet.length;
    for (const { endpoint } of clients.values()) if (endpoint.kind === 'udp') send(packet, endpoint);
  }, 100);
  const joinUrl = (source: boolean, browserOnly = false) => {
    const uri = new URL('prism://crowd');
    for (const [key, value] of Object.entries({
      host: advertisedHost, port: String(boundPort), sid, key: keys.publicPin,
      web: tls ? 'https' : 'http',
      token: source ? sourceToken : viewerToken, role: source ? 'source' : 'viewer',
      ssid: browserOnly ? '' : process.env.PRISM_WIFI_SSID ?? '', password: browserOnly ? '' : process.env.PRISM_WIFI_PASSWORD ?? ''
    })) if (value) uri.searchParams.set(key, value);
    return uri.toString();
  };
  const webJoinUrl = (source: boolean) => {
    const url = new URL(appUrl ?? localAppUrl());
    url.hash = new URLSearchParams({ crowd: joinUrl(source, true) }).toString();
    return url.toString();
  };
  const web = http.createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost');
    const headers = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
      'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'" };
    const json = (code: number, body: object) => { res.writeHead(code, { ...headers, 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
    // The controller UI is loopback-only. State and commands also require a fresh
    // per-run admin token, never included in the participant QR.
    try {
      if (req.method === 'GET' && url.pathname === '/') {
        res.writeHead(200, { ...headers, 'Content-Type': 'text/html; charset=utf-8' }); res.end(dashboard); return;
      }
      if (req.method === 'GET' && url.pathname === '/app.js') {
        res.writeHead(200, { ...headers, 'Content-Type': 'text/javascript; charset=utf-8' }); res.end(browserScript); return;
      }
      if (!equal(req.headers.authorization, 'Bearer ' + adminToken)) { json(403, { error: 'Open the controller using the launch link.' }); return; }
      if (req.method === 'GET' && url.pathname === '/api/status') {
        const time = now();
        json(200, { ...show.snapshot(time), time, host: advertisedHost, interfaces: interfaces(), port: boundPort,
          maxClients: Number(process.env.PRISM_HOTSPOT_LIMIT ?? 0), socketError, packetBytes: lastPacketBytes,
          clients: [...clients.entries()].map(([key, c]) => ({ id: key.slice(key.indexOf(':') + 1), name: c.name, source: c.source,
            age: time - c.seen, ...c.health })), rejected }); return;
      }
      if (req.method === 'GET' && url.pathname === '/api/join') {
        const source = url.searchParams.get('role') === 'source';
        const link = url.searchParams.get('platform') === 'web' ? webJoinUrl(source) : joinUrl(source);
        json(200, { link, qr: await QRCode.toDataURL(link, { width: 400, margin: 2, errorCorrectionLevel: 'M' }) }); return;
      }
      if (req.method === 'POST' && url.pathname === '/api/command') {
        let body = '';
        for await (const chunk of req) {
          body += chunk;
          if (Buffer.byteLength(body) > 4096) { json(413, { error: 'Request too large.' }); return; }
        }
        const command = JSON.parse(body);
        if (command.action === 'interface') {
          if (!interfaces().some(n => n.address === command.host)) throw new Error('Choose a local interface.');
          advertisedHost = command.host;
          appOrigins.add(localAppUrl().origin);
        } else show.command(command, now());
        json(200, { ok: true }); return;
      }
      json(404, { error: 'Not found' });
    } catch (error) { json(400, { error: error instanceof Error ? error.message : 'Invalid request' }); }
  });
  try {
    await new Promise<void>((resolve, reject) => {
      web.once('error', reject);
      // Existing show port selections can coincide with the default dashboard port.
      // The launcher already opens the dashboard's actual loopback address.
      web.listen(httpPort === boundPort ? 0 : httpPort, '127.0.0.1', () => { web.removeListener('error', reject); resolve(); });
    });
  } catch (error) {
    clearInterval(ticker);
    await new Promise<void>(resolve => browser.close(() => resolve()));
    await new Promise<void>(resolve => udp.close(() => resolve()));
    throw error;
  }
  const address = web.address();
  if (!address || typeof address === 'string') throw new Error('Could not start controller.');
  const dashboardUrl = 'http://127.0.0.1:' + address.port + '/#' + adminToken;
  return {
    show, sid, publicPin: keys.publicPin, udpPort: boundPort, dashboardUrl, joinUrl, webJoinUrl,
    close: async () => {
      clearInterval(ticker);
      show.command({ action: 'blackout' }, now());
      const stopped = state(now()), stop = keys.encode(stopped);
      browserState = keys.webEnvelope(stopped);
      for (const { endpoint } of clients.values()) if (endpoint.kind === 'udp') send(stop, endpoint);
      // Give foreground browser participants two polling intervals to receive the blackout.
      if ([...clients.values()].some(c => c.endpoint.kind === 'web')) await delay(250);
      await new Promise<void>(resolve => browser.close(() => resolve()));
      await new Promise<void>(resolve => udp.close(() => resolve()));
      await new Promise<void>(resolve => web.close(() => resolve()));
    }
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const controller = await startController();
  console.log('prism controller: ' + controller.dashboardUrl);
  console.log('Keep this window open. Ctrl+C stops the show. Participant port (UDP/TCP): ' + controller.udpPort);
  if (process.env.PRISM_LAUNCH_FILE) {
    const { writeFile } = await import('node:fs/promises');
    await writeFile(process.env.PRISM_LAUNCH_FILE, controller.dashboardUrl, { mode: 0o600 });
  }
  let stopping = false;
  const stop = () => { if (!stopping) { stopping = true; void controller.close().then(() => process.exit(0)); } };
  process.on('SIGINT', stop); process.on('SIGTERM', stop);
}
