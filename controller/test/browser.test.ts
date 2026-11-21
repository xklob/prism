import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, webcrypto } from 'node:crypto';
import dgram from 'node:dgram';
import { setTimeout as delay } from 'node:timers/promises';
import { startController, now } from '../src/server.ts';
import { decode, identity } from '../src/wire.ts';

type Envelope = { payload: string; signature: string };
const bytes = (encoded: string) => Uint8Array.from(Buffer.from(encoded, 'base64url'));
const importPin = (pin: string) => webcrypto.subtle.importKey('spki', bytes(pin), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
const verified = (packet: Envelope, key: webcrypto.CryptoKey) => webcrypto.subtle.verify(
  { name: 'ECDSA', hash: 'SHA-256' }, key, bytes(packet.signature), bytes(packet.payload));

test('browser invitation scopes, signed show and clock, and native UDP interoperability', async t => {
  const app = 'https://example.test/prism/';
  const server = await startController({ udpPort: 0, httpPort: 0, host: '127.0.0.1', webUrl: app });
  const socket = dgram.createSocket('udp4');
  t.after(async () => { socket.close(); await server.close(); });
  const url = new URL(server.webJoinUrl(false));
  assert.equal(url.origin + url.pathname, app);
  assert.equal(url.search, '');
  const viewer = new URL(new URLSearchParams(url.hash.slice(1)).get('crowd')!);
  assert.equal(viewer.searchParams.has('password'), false);
  assert.equal(viewer.searchParams.get('role'), 'viewer');
  const source = new URL(server.joinUrl(true));
  assert.notEqual(source.searchParams.get('token'), viewer.searchParams.get('token'));
  const target = 'http://127.0.0.1:' + server.udpPort + '/crowd/exchange';
  const headers = { Origin: url.origin, 'Content-Type': 'application/json' };
  const m = { v: 1, type: 'exchange', sid: server.sid, id: randomUUID(), token: viewer.searchParams.get('token'), t1: now(), nonce: randomUUID() };
  const post = (changes: object = {}) => fetch(target, { method: 'POST', headers, body: JSON.stringify({ ...m, ...changes }) });
  assert.equal((await fetch(target, { method: 'OPTIONS', headers: { Origin: 'https://example.org' } })).status, 403);
  const allowed = await fetch(target, { method: 'OPTIONS', headers });
  assert.equal(allowed.headers.get('Access-Control-Allow-Origin'), url.origin);
  assert.equal((await fetch(new URL('/api/command', target), { method: 'POST', headers })).status, 404);
  assert.equal((await fetch(new URL('/api/join', server.dashboardUrl))).status, 403);
  assert.equal((await post({ token: 'invalid' })).status, 403);
  assert.equal((await post({ sid: randomUUID() })).status, 403);
  assert.equal((await post({ audio: {} })).status, 403);
  const authorizedJoin = await fetch(new URL('/api/join?platform=web', server.dashboardUrl), {
    headers: { Authorization: 'Bearer ' + new URL(server.dashboardUrl).hash.slice(1) }
  });
  const qr = await authorizedJoin.json() as { link: string; qr: string };
  assert.equal(qr.link, url.toString());
  assert.match(qr.qr, /^data:image\/png;base64,/);
  const udpRevisions = new Map<number, unknown>();
  socket.on('message', packet => {
    const signed = decode(packet, server.publicPin);
    if (signed.type === 'state') udpRevisions.set(Number(signed.seq), signed.cues);
  });
  await new Promise<void>(resolve => socket.bind(0, '127.0.0.1', resolve));
  socket.send(JSON.stringify({ ...m, id: randomUUID(), type: 'sync' }), server.udpPort, '127.0.0.1');
  server.show.command({ action: 'settings', mode: 'scene', scene: 7 }, now());
  server.show.command({ action: 'start' }, now());
  const pin = await importPin(server.publicPin);
  const deadline = now() + 3000;
  while (!udpRevisions.size && now() < deadline) await delay(10);
  const response = await post();
  assert.equal(response.status, 200);
  const envelopes = await response.json() as { sync: Envelope; state: Envelope };
  assert.ok(await verified(envelopes.sync, pin));
  assert.ok(await verified(envelopes.state, pin));
  assert.equal(await verified(envelopes.state, await importPin(identity().publicPin)), false);
  assert.equal(await verified({ ...envelopes.state, payload: Buffer.from('{}').toString('base64url') }, pin), false);
  const sync = JSON.parse(Buffer.from(envelopes.sync.payload, 'base64url').toString());
  assert.deepEqual([sync.type, sync.id, sync.nonce, sync.sid, sync.t1], ['sync', m.id, m.nonce, m.sid, m.t1]);
  assert.ok(sync.t2 <= sync.t3);
  const state = JSON.parse(Buffer.from(envelopes.state.payload, 'base64url').toString());
  while (!udpRevisions.has(state.seq) && now() < deadline) await delay(10);
  assert.deepEqual(udpRevisions.get(state.seq), state.cues);
  assert.equal(state.cues.at(-1).scene, 7);
  assert.equal(state.validUntil, state.sent + 2000);

  server.show.command({ action: 'source', source: 'audio' }, now());
  const sourceId = randomUUID(), sourceToken = source.searchParams.get('token');
  const audio = { at: now(), beat: 8, bpm: 128, meter: 4, phraseBars: 16, phraseBar: 3, confidence: 1,
    barConfidence: 1, phraseConfidence: .7, clockMs: 1, ready: true };
  assert.equal((await post({ id: sourceId, token: sourceToken, audio })).status, 200);
  assert.equal(server.show.snapshot(now()).music.ready, true);
  const collision = await (await post({ id: randomUUID(), token: sourceToken, audio })).json() as { error: Envelope };
  assert.ok(await verified(collision.error, pin));
  assert.match(Buffer.from(collision.error.payload, 'base64url').toString(), /owns the audio input/);
  await post({ id: sourceId, token: sourceToken, type: 'leave' });
  assert.equal((await post({ id: randomUUID(), token: sourceToken, audio: { ...audio, at: now() } })).status, 200);
});

test('default invitations open the packaged app and expose only listed static assets', async t => {
  const { mkdtemp, mkdir, rm, writeFile, symlink } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const path = await import('node:path');
  const root = await mkdtemp(path.join(tmpdir(), 'prism-browser-'));
  const app = path.join(root, 'app');
  await mkdir(path.join(app, 'assets'), { recursive: true });
  await writeFile(path.join(root, 'outside.txt'), 'fixture outside bundle');
  await writeFile(path.join(app, 'index.html'), '<title>prism test</title>');
  await writeFile(path.join(app, 'assets/logo.svg'), '<svg/>');
  await writeFile(path.join(app, 'assets/unlisted.json'), '{}');
  await writeFile(path.join(app, 'prism-files.json'), JSON.stringify(['index.html', 'assets/logo.svg', 'assets/link.txt', '../outside.txt']));
  try { await symlink(path.join(root, 'outside.txt'), path.join(app, 'assets/link.txt')); }
  catch (error) { if (process.platform !== 'win32' || (error as NodeJS.ErrnoException).code !== 'EPERM') throw error; }
  const controller = await startController({ udpPort: 0, httpPort: 0, host: '127.0.0.1', webUrl: '', appDirectory: app });
  t.after(async () => { await controller.close(); await rm(root, { recursive: true, force: true }); });
  const invite = new URL(controller.webJoinUrl(false));
  assert.equal(invite.origin, 'http://127.0.0.1:' + controller.udpPort);
  assert.equal(invite.pathname, '/');
  assert.equal(invite.search, '');
  assert.ok(invite.hash.startsWith('#crowd='));
  const page = await fetch(invite);
  assert.match(await page.text(), /prism test/);
  const asset = await fetch(new URL('/assets/logo.svg', invite), { method: 'HEAD' });
  assert.equal(asset.status, 200);
  assert.equal(asset.headers.get('content-type'), 'image/svg+xml');
  for (const name of ['/assets/unlisted.json', '/prism-files.json', '/assets/link.txt', '/runtime/launch-url.txt', '/assets/..%2f..%2foutside.txt']) {
    assert.equal((await fetch(new URL(name, invite))).status, 404, name);
  }
  const endpoint = new URL('/crowd/exchange', invite);
  const permission = await fetch(endpoint, { method: 'OPTIONS', headers: { Origin: invite.origin } });
  assert.equal(permission.headers.get('Access-Control-Allow-Origin'), invite.origin);
});

test('a fixed UDP port collision releases the companion TCP listener', async t => {
  const net = await import('node:net');
  const reservation = net.createServer();
  const listen = (server: ReturnType<typeof net.createServer>, port: number) =>
    new Promise<number>((resolve, reject) => {
      server.once('error', reject);
      server.listen(port, '0.0.0.0', () => {
        server.removeListener('error', reject);
        resolve((server.address() as import('node:net').AddressInfo).port);
      });
    });
  const port = await listen(reservation, 0);
  const occupied = dgram.createSocket('udp4');
  await new Promise<void>((resolve, reject) => {
    occupied.once('error', reject);
    occupied.bind(port, '0.0.0.0', () => { occupied.removeListener('error', reject); resolve(); });
  });
  t.after(() => occupied.close());
  await new Promise<void>(resolve => reservation.close(() => resolve()));
  await assert.rejects(startController({ udpPort: port, httpPort: 0 }), { code: 'EADDRINUSE' });
  assert.equal(await listen(reservation, port), port);
  await new Promise<void>(resolve => reservation.close(() => resolve()));
});
