import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { CrowdClient, inviteFromFragment, parseInvitation } from '../src/crowd.js';
import { pinnedVerifier } from '../scripts/p256-verifier.mjs';

test('LAN verifier checks a pinned P-256 key, including OpenSSL high-S signatures', () => {
  // A public OpenSSL P1363 fixture with the high bit of the S component set.
  const spki = Buffer.from(
    'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYxJ_Kch8AAL-_I_pmvjCPz4IaIb-eK0zYsDua4he6w4C5_a9Fvc1IVtA5SKG9JnrzeZj1veN0f6gCFklWrSEYQ',
    'base64url'
  );
  const signature = Buffer.from(
    'KAvIdyWiCLE-FI5wHlJOXZdMkpLEjo8T4H3ziPGANFHU0zRK26vFGNZA686BI-mNi_1s4MftTgDytgrFvHAQiQ',
    'base64url'
  );
  const verify = pinnedVerifier(spki);
  assert.equal(verify(signature, Buffer.from('signed show fixture')), true);
  assert.equal(verify(signature, Buffer.from('changed fixture')), false);
  const invalid = Buffer.from(spki);
  invalid[20] ^= 1;
  assert.throws(() => pinnedVerifier(invalid), /Invalid controller key/);
});

test('browser participant samples only verified, fresh cues and resets after hiding', async (t) => {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const pin = publicKey.export({ format: 'der', type: 'spki' }).toString('base64url');
  const sid = randomUUID();
  const invite =
    'prism://crowd?' +
    new URLSearchParams({
      sid,
      role: 'viewer',
      token: 'x'.repeat(32),
      key: pin,
      host: '127.0.0.1',
      port: '48761',
      web: 'http',
    });
  const link = 'https://example.test/prism/#' + new URLSearchParams({ crowd: invite });
  assert.equal(inviteFromFragment(new URL(link).hash), invite);
  assert.equal(parseInvitation(link).endpoint, 'http://127.0.0.1:48761/crowd/exchange');
  assert.throws(() => parseInvitation(invite.replace('127.0.0.1', '8.8.8.8')), /Invalid address/);
  assert.throws(() => parseInvitation(invite.replace('&web=http', '')), /controller 0.5.0/);

  let seq = 0,
    expires = 0,
    tamper = false;
  const cue = {
    revision: 1,
    effective: 0,
    anchor: 0,
    beat: 0,
    bpm: 128,
    meter: 4,
    phraseBars: 16,
    phraseOrigin: 0,
    running: true,
    mode: 'scene',
    scene: 7,
    previousScene: 1,
    transitionAt: 0,
    palette: 2,
    speed: 0.7,
    intensity: 1,
    complexity: 0.7,
    symmetry: 8,
    distortion: 0.5,
    zoom: 1,
    hue: 0,
    rotation: 0.1,
    colorSpeed: 0.02,
    morph: 0.4,
    flashEvery: 4,
    flashMs: 50,
    invertMs: 180,
  };
  const envelope = (type, fields) => {
    const payload = Buffer.from(JSON.stringify({ v: 1, type, sid, ...fields }));
    return {
      payload: payload.toString('base64url'),
      signature: sign('sha256', payload, { key: privateKey, dsaEncoding: 'ieee-p1363' }).toString(
        'base64url'
      ),
    };
  };
  t.mock.method(globalThis, 'fetch', async (_url, req) => {
    const m = JSON.parse(req.body);
    if (m.type === 'leave') return Response.json({});
    const sent = performance.now();
    expires = sent + 2000;
    const state = envelope('state', {
      seq: ++seq,
      sent,
      validUntil: expires,
      cues: [cue],
      music: { ready: true, confidence: 1, barConfidence: 1, phraseConfidence: 0.8 },
      blackout: false,
    });
    if (tamper) state.payload = Buffer.from('{}').toString('base64url');
    return Response.json({
      sync: envelope('sync', { id: m.id, t1: m.t1, t2: sent, t3: sent, nonce: m.nonce }),
      state,
    });
  });
  const client = new CrowdClient({ getAudio: () => assert.fail('Audience client requested audio') });
  t.after(() => client.leave());
  await client.join(link);
  const deadline = performance.now() + 4000;
  while (client.status.phase !== 'Ready' && performance.now() < deadline) await delay(25);
  assert.equal(client.status.phase, 'Ready');
  assert.equal(client.frame().settings.scene, 7);
  client.blackout = true;
  assert.equal(client.frame().dark, true);
  client.blackout = false;
  const stale = client.frame(performance.now() + 3000);
  assert.equal(stale.timeMs, expires);
  assert.deepEqual(stale.motion, { beat: 0, bar: 0, flow: 0, texture: 0, flash: 0, inversion: 0 });
  client.setVisible(false);
  assert.equal(client.status.phase, 'Suspended');
  assert.equal(client.frame().cue, null);
  client.leave();
  client.setVisible(true);
  tamper = true;
  await assert.rejects(client.join(link), /signature/);
  assert.equal(client.connected, false);
});
