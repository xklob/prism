import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Show, beatAt } from '../src/show.ts';
import type { AudioInput } from '../src/show.ts';

test('start and settings share a future bar boundary, repeated edits do not postpone it', () => {
  const show = new Show();
  show.command({ action: 'start' }, 100);
  assert.equal(show.active(1099).running, false);
  assert.equal(show.active(1100).running, true);
  show.command({ action: 'settings', scene: 2 }, 1200);
  const first = show.snapshot(1200).cues[1]!;
  assert.ok(first.effective >= 2200);
  assert.equal(beatAt(first, first.effective) % 4, 0);
  show.command({ action: 'settings', palette: 3, complexity: .9 }, 1300);
  const revised = show.snapshot(1300).cues[1]!;
  assert.equal(revised.effective, first.effective);
  assert.equal(revised.scene, 2);
  assert.equal(revised.palette, 3);
  assert.equal(revised.previousScene, 1);
  assert.equal(show.active(revised.effective).scene, 2);
});
test('tempo change preserves the beat at the scheduled boundary', () => {
  const show = new Show();
  show.command({ action: 'start' }, 0);
  const prior = show.active(1100);
  show.command({ action: 'settings', bpm: 135 }, 1500);
  const next = show.snapshot(1500).cues[1]!;
  assert.equal(beatAt(next, next.effective), beatAt(prior, next.effective));
  assert.equal(beatAt(next, next.effective + 60000 / 135), next.beat + 1);
});
test('late edits cannot rewrite an imminent cue, blackout cancels queued starts', () => {
  const show = new Show();
  show.command({ action: 'start' }, 0);
  assert.throws(() => show.command({ action: 'settings', bpm: 90 }, 900));
  show.command({ action: 'blackout' }, 950);
  assert.equal(show.snapshot(2000).cues.length, 1);
  assert.equal(show.active(2000).running, false);
  assert.equal(show.snapshot(2000).blackout, true);
});
test('invalid commands cannot poison the timeline', () => {
  const show = new Show();
  for (const patch of [{ bpm: NaN }, { bpm: -1 }, { scene: 5 }, { flashEvery: .01 }, { flashMs: 1000 }, { mode: 'oops' }]) {
    assert.throws(() => show.command({ action: 'settings', ...patch }, 0));
    assert.equal(show.snapshot(0).cues.length, 1);
  }
});
test('tap tempo needs repeated consistent intervals', () => {
  const show = new Show();
  show.command({ action: 'tap' }, 0);
  show.command({ action: 'tap' }, 600);
  assert.equal(show.snapshot(600).cues.length, 1);
  show.command({ action: 'tap' }, 1200);
  assert.equal(show.snapshot(1200).cues[1]!.bpm, 100);
});
test('manual bar alignment uses the tap phase and activates on a future bar', () => {
  const show = new Show();
  show.command({ action: 'align' }, 215);
  const cue = show.snapshot(215).cues[1]!;
  assert.equal(cue.effective, 2215);
  assert.equal(beatAt(cue, 215), 0);
  assert.equal(beatAt(cue, cue.effective), 4);
});
const input = (at: number): AudioInput => ({
  at, beat: at / 500, bpm: 120, meter: 4, confidence: .9, barConfidence: .8,
  phraseConfidence: .6, phraseBars: 16, phraseBar: 3, ready: true, clockMs: 3
});
test('fresh audio is scheduled ahead, bounded, and expires on signal loss', () => {
  const show = new Show();
  show.command({ action: 'source', source: 'audio' }, 0);
  show.command({ action: 'start' }, 0);
  show.active(1000);
  show.receiveAudio(input(1100), 1100);
  const first = show.snapshot(1100).cues[1]!;
  show.receiveAudio(input(1200), 1200);
  assert.equal(show.snapshot(1200).cues[1]!.effective, first.effective);
  assert.equal(show.snapshot(1200).music.ready, true);
  assert.equal(show.snapshot(2300).music.ready, false);
  assert.throws(() => show.receiveAudio({ ...input(2400), clockMs: 100 }, 2400));
  assert.throws(() => show.receiveAudio(input(0), 2400));
  show.receiveAudio({ ...input(2500), ready: false }, 2500);
  assert.equal(show.snapshot(2500).music.ready, false);
});
