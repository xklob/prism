import { test } from 'node:test';
import assert from 'node:assert/strict';
import dgram from 'node:dgram';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { startController, now } from '../src/server.ts';
import { identity, decode } from '../src/wire.ts';

test('a changed payload or a different controller key fails authentication', () => {
  const one = identity(), two = identity();
  const packet = one.encode({ type: 'state', seq: 1 });
  assert.equal(decode(packet, one.publicPin).seq, 1);
  assert.throws(() => decode(packet, two.publicPin));
  const tampered = JSON.parse(packet.toString());
  tampered.payload = Buffer.from('{"type":"state","seq":99}').toString('base64url');
  assert.throws(() => decode(Buffer.from(JSON.stringify(tampered)), one.publicPin));
});

test('50 UDP participants receive the same signed schedule; viewer tokens cannot publish audio', async t => {
  const server = await startController({ udpPort: 0, httpPort: 0, host: '127.0.0.1' });
  const sockets: dgram.Socket[] = [];
  t.after(async () => { for (const socket of sockets) socket.close(); await server.close(); });
  const join = new URL(server.joinUrl(false));
  const token = join.searchParams.get('token')!;
  const results = Array.from({ length: 50 }, () => ({ id: randomUUID(), sync: false, revision: -1, effective: 0 }));
  await Promise.all(results.map(async (result, index) => {
    const socket = dgram.createSocket('udp4'); sockets.push(socket);
    await new Promise<void>(resolve => socket.bind(0, '127.0.0.1', resolve));
    socket.on('message', packet => {
      const message = decode(packet, server.publicPin);
      if (message.type === 'sync') {
        assert.equal(message.id, result.id); assert.equal(message.nonce, 'probe');
        assert.ok(Number(message.t3) >= Number(message.t2)); result.sync = true;
      } else if (message.type === 'state') {
        const cues = message.cues as Array<{revision:number;effective:number}>;
        result.revision = cues.at(-1)!.revision; result.effective = cues.at(-1)!.effective;
      }
    });
    socket.send(JSON.stringify({ v: 1, type: 'sync', id: result.id, token, name: 'Simulated phone ' + index,
      nonce: 'probe', t1: now() }), server.udpPort, '127.0.0.1');
  }));
  server.show.command({ action: 'start' }, now());
  const deadline = now() + 3000;
  while (results.some(r => !r.sync || r.revision < 1) && now() < deadline) await delay(25);
  assert.equal(results.filter(r => r.sync && r.revision >= 1).length, 50);
  assert.equal(new Set(results.map(r => r.effective)).size, 1);
  server.show.command({ action: 'source', source: 'audio' }, now());
  sockets[0]!.send(JSON.stringify({ v:1,type:'audio',id:results[0]!.id,token,
    audio:{at:now(),beat:0,bpm:120,meter:4,phraseBars:16,phraseBar:1,confidence:1,barConfidence:1,
      phraseConfidence:1,ready:true,clockMs:1} }), server.udpPort,'127.0.0.1');
  await delay(50);
  assert.equal(server.show.snapshot(now()).music.ready,false);
  const rejected = await fetch(new URL('/api/status',server.dashboardUrl));
  assert.equal(rejected.status,403);
  const auth = server.dashboardUrl.split('#')[1]!;
  const status = await (await fetch(new URL('/api/status',server.dashboardUrl),{headers:{Authorization:'Bearer '+auth}})).json() as {clients:unknown[]};
  assert.equal(status.clients.length,50);
});
