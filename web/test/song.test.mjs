import test from 'node:test';
import assert from 'node:assert/strict';
import { matchTrack, recognitionWait, SongAssist } from '../src/song.js';

test('catalog matches retain recording identity and decline ambiguous edits', () => {
  const href = 'https://open.spotify.com/track/1234567890123456789012';
  const one = { href, trackTitle: 'Café', artists: [{ name: 'Example' }], durationMs: 120000 };
  const two = { ...one, href: href.replace('12', '44'), trackTitle: 'Café (Club Edit)' };
  assert.equal(matchTrack({ id: href }, [two, one]), one);
  assert.equal(matchTrack({ title: 'Cafe', artist: 'example', duration: 120400 }, [two, one]), one);
  assert.equal(matchTrack({ title: 'Cafe', artist: 'example' }, [one, { ...one }]), undefined);
  assert.equal(matchTrack({ title: 'Cafe', artist: 'example', duration: 99000 }, [one]), undefined);
});
test('audio identification spaces attempts and enforces a rolling limit', () => {
  assert.equal(recognitionWait([], 1000), 0);
  assert.equal(recognitionWait([980], 1000), 10);
  assert.equal(
    recognitionWait(
      Array.from({ length: 40 }, (_, i) => i * 35),
      1400
    ),
    2200
  );
  assert.equal(recognitionWait([1], 3602), 0);
});
test('turning off song assist invalidates an in-flight lookup', async () => {
  const previous = globalThis.fetch;
  let resolveFetch;
  globalThis.fetch = () =>
    new Promise((resolve) => {
      resolveFetch = resolve;
    });
  try {
    let hint;
    const assist = new SongAssist(
      () => {},
      (value) => {
        hint = value;
      }
    );
    assist.allow(true);
    const running = assist.search({ title: 'One', artist: 'Two' });
    assist.allow(false);
    resolveFetch({ ok: true, json: async () => ({ content: [] }) });
    await running;
    assert.equal(hint, null);
    assert.equal(assist.message, '');
    assert.equal(assist.trackTitle, '');
  } finally {
    globalThis.fetch = previous;
  }
});
