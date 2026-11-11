import dgram from 'node:dgram';
import http from 'node:http';
import { networkInterfaces } from 'node:os';
import { readFile } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import QRCode from 'qrcode';
import { Show } from './show.ts';
import type { AudioInput } from './show.ts';
import { identity } from './wire.ts';

export const now = (): number => Math.round(performance.now() * 1000) / 1000;
const secret = () => randomBytes(24).toString('base64url');
const equal = (a: unknown, b: string) => typeof a === 'string' && Buffer.byteLength(a) === Buffer.byteLength(b) &&
  timingSafeEqual(Buffer.from(a), Buffer.from(b));
type Participant = { address: string; port: number; name: string; seen: number; source: boolean;
  health: Record<string, unknown>; rateAt: number; rateCount: number };
export async function startController(options: { udpPort?: number; httpPort?: number; host?: string } = {}) {
  const dashboard = await readFile(fileURLToPath(new URL('../web/index.html', import.meta.url)));
  const browserScript = await readFile(fileURLToPath(new URL('../web/app.js', import.meta.url)));
  const udpPort = options.udpPort ?? Number(process.env.PRISM_UDP_PORT ?? 48761);
  const httpPort = options.httpPort ?? Number(process.env.PRISM_HTTP_PORT ?? 48760);
  const show = new Show(), keys = identity(), sid = randomUUID();
  const viewerToken = secret(), sourceToken = secret(), adminToken = secret();
  const clients = new Map<string, Participant>();
  const udp = dgram.createSocket('udp4');
  let seq = 0, audioOwner: string | undefined, rejected = 0, lastPacketBytes = 0;
  let socketError = '';
  const signPacket = (type: string, body: object) => keys.encode({ v: 1, type, sid, ...body });
  const send = (packet: Buffer, peer: { port: number; address: string }) =>
    udp.send(packet, peer.port, peer.address, (error) => { if (error) socketError = error.message; });
  udp.on('error', error => { socketError = error.message; });
  udp.on('message', (packet, remote) => {
    const received = now();
    try {
      if (packet.length > 4096) return;
      const m = JSON.parse(packet.toString());
      if (m.v !== 1 || !/^[a-zA-Z0-9-]{1,64}$/.test(m.id)) return;
      const source = equal(m.token, sourceToken);
      if (!source && !equal(m.token, viewerToken)) return;
      let client = clients.get(m.id);
      if (!client) {
        if (clients.size >= 50) {
          send(signPacket('error', { message: 'This show already has 50 participants.' }), remote); return;
        }
        client = { address: remote.address, port: remote.port,
          name: String(m.name ?? 'Prism phone').slice(0, 64), seen: received, source,
          health: {}, rateAt: received, rateCount: 0 };
        clients.set(m.id, client);
      }
      if (received - client.rateAt > 1000) { client.rateAt = received; client.rateCount = 0; }
      if (++client.rateCount > 40) return;
      client.address = remote.address; client.port = remote.port; client.seen = received;
      if (m.type === 'leave') {
        clients.delete(m.id); if (audioOwner === m.id) audioOwner = undefined; return;
      }
      if (m.type === 'sync' && Number.isFinite(m.t1) && typeof m.nonce === 'string' && m.nonce.length <= 64) {
        send(signPacket('sync', { id: m.id, nonce: m.nonce, t1: m.t1, t2: received, t3: now() }), client);
      } else if (m.type === 'health' && m.health && typeof m.health === 'object') {
        client.health = Object.fromEntries(['state', 'clockMs', 'fps', 'missed', 'frames', 'phraseBar', 'beatConfidence']
          .filter(key => ['string', 'number', 'boolean'].includes(typeof m.health[key]))
          .map(key => [key, typeof m.health[key] === 'string' ? m.health[key].slice(0, 80) : m.health[key]]));
      } else if (m.type === 'audio' && source) {
        if (audioOwner && audioOwner !== m.id) {
          send(signPacket('error', { message: 'Another phone owns the audio input. Disconnect it first.' }), client); return;
        }
        show.receiveAudio(m.audio as AudioInput, received);
        audioOwner = m.id;
      }
    } catch { rejected++; }
  });
  await new Promise<void>((resolve, reject) => {
    udp.once('error', reject);
    udp.bind(udpPort, '0.0.0.0', () => { udp.removeListener('error', reject); resolve(); });
  });
  const boundPort = udp.address().port;
  const ticker = setInterval(() => {
    const time = now();
    for (const [id, client] of clients) if (time - client.seen > 5000) {
      clients.delete(id); if (audioOwner === id) audioOwner = undefined;
    }
    const packet = signPacket('state', { seq: ++seq, sent: time, validUntil: time + 2000, ...show.snapshot(time) });
    lastPacketBytes = packet.length;
    for (const peer of clients.values()) send(packet, peer);
  }, 100);
  const interfaces = () => Object.entries(networkInterfaces()).flatMap(([name, entries]) =>
    (entries ?? []).filter(n => n.family === 'IPv4' && !n.internal)
      .map(n => ({ name, address: n.address })));
  let advertisedHost = options.host ?? process.env.PRISM_HOST ?? interfaces()[0]?.address ?? '127.0.0.1';
  const joinUrl = (source: boolean) => {
    const uri = new URL('prism://crowd');
    for (const [key, value] of Object.entries({
      host: advertisedHost, port: String(boundPort), sid, key: keys.publicPin,
      token: source ? sourceToken : viewerToken, role: source ? 'source' : 'viewer',
      ssid: process.env.PRISM_WIFI_SSID ?? '', password: process.env.PRISM_WIFI_PASSWORD ?? ''
    })) if (value) uri.searchParams.set(key, value);
    return uri.toString();
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
          clients: [...clients.entries()].map(([id, c]) => ({ id, name: c.name, source: c.source,
            age: time - c.seen, ...c.health })), rejected }); return;
      }
      if (req.method === 'GET' && url.pathname === '/api/join') {
        const link = joinUrl(url.searchParams.get('role') === 'source');
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
        } else show.command(command, now());
        json(200, { ok: true }); return;
      }
      json(404, { error: 'Not found' });
    } catch (error) { json(400, { error: error instanceof Error ? error.message : 'Invalid request' }); }
  });
  try {
    await new Promise<void>((resolve, reject) => {
      web.once('error', reject);
      web.listen(httpPort, '127.0.0.1', () => { web.removeListener('error', reject); resolve(); });
    });
  } catch (error) {
    clearInterval(ticker);
    await new Promise<void>(resolve => udp.close(() => resolve()));
    throw error;
  }
  const address = web.address();
  if (!address || typeof address === 'string') throw new Error('Could not start controller.');
  const dashboardUrl = 'http://127.0.0.1:' + address.port + '/#' + adminToken;
  return {
    show, sid, publicPin: keys.publicPin, udpPort: boundPort, dashboardUrl, joinUrl,
    close: async () => {
      clearInterval(ticker);
      show.command({ action: 'blackout' }, now());
      const stop = signPacket('state', { seq: ++seq, sent: now(), validUntil: now() + 500, ...show.snapshot(now()) });
      for (const peer of clients.values()) send(stop, peer);
      await new Promise<void>(resolve => udp.close(() => resolve()));
      await new Promise<void>(resolve => web.close(() => resolve()));
    }
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const controller = await startController();
  console.log('Prism controller: ' + controller.dashboardUrl);
  console.log('Keep this window open. Ctrl+C stops the show. UDP port: ' + controller.udpPort);
  if (process.env.PRISM_LAUNCH_FILE) {
    const { writeFile } = await import('node:fs/promises');
    await writeFile(process.env.PRISM_LAUNCH_FILE, controller.dashboardUrl, { mode: 0o600 });
  }
  let stopping = false;
  const stop = () => { if (!stopping) { stopping = true; void controller.close().then(() => process.exit(0)); } };
  process.on('SIGINT', stop); process.on('SIGTERM', stop);
}
