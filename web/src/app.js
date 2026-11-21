import { createRenderer } from './renderer.js';
import { Preferences } from './preferences.js';
import { AudioSession, sourceNames } from './audio.js';
import { Recorder } from './recording.js';
import { CrowdClient, inviteFromFragment } from './crowd.js';
import { SongAssist } from './song.js';
import { Diagnostics, drawWave } from './plots.js';
import { panelContent, paintIcons, icon, esc, clock } from './controls.js';

const $ = (id) => document.getElementById(id);
const canvas = $('canvas'),
  controls = $('controls'),
  wave = $('wave');
const ui = {
  tab: 'Tune',
  tune: '',
  settings: 'Display',
  paused: false,
  immersive: false,
  queued: null,
  frozen: false,
  diagWindow: 8,
  panelClosed: false,
  monitorClosed: false,
  offline: 'Preparing offline copy…',
  install: null,
  crowdBrightness: 1,
  invite: inviteFromFragment(location.hash),
  pan: [0, 0],
};
window.addEventListener('beforeinstallprompt', (event) => {
  event.preventDefault();
  ui.install = event;
});
if (ui.invite) {
  history.replaceState(null, '', location.pathname + location.search);
  ui.tab = 'Settings';
  ui.settings = 'Crowd';
}
let noticeTimer, wakeLock;
async function keepScreenAwake() {
  if ((!ui.immersive && !crowd.connected) || document.hidden) {
    const held = wakeLock;
    wakeLock = null;
    await held?.release().catch(() => {});
    return;
  }
  if (!wakeLock || wakeLock.released) {
    try {
      wakeLock = await navigator.wakeLock?.request('screen');
    } catch {
      /* Browser power policy may override a request. */
    }
  }
}
function notice(message) {
  $('notice').textContent = message;
  $('notice').hidden = !message;
  clearTimeout(noticeTimer);
  if (message)
    noticeTimer = setTimeout(() => {
      $('notice').hidden = true;
    }, 5500);
}
function renderError(message) {
  $('render-error').textContent = message;
  $('render-error').hidden = !message;
}
paintIcons(document);
let renderer, catalog;
try {
  renderer = await createRenderer(canvas, {
    onError: (message) => renderError(message?.message || String(message || '')),
    onRestore: () => renderError(''),
  });
  catalog = renderer.catalog;
} catch (error) {
  renderError(error.message || 'Enable WebGL 2 to run prism.');
  const response = await fetch(new URL('../assets/visuals.json', import.meta.url));
  if (response.ok) catalog = await response.json();
}
if (!catalog) throw new Error('prism could not load its visual assets.');
const prefs = new Preferences(catalog),
  recorder = new Recorder(),
  diag = new Diagnostics();
const opts = (source) => ({
  meter: source === 'file' ? prefs.data.fileMeter : prefs.data.beatsPerBar,
  phraseBars: source === 'file' ? prefs.data.filePhraseBars : prefs.data.phraseBars,
  sensitivity: prefs.data.sensitivity,
  syncOffsetMs: prefs.data.syncOffsetMs,
  digital: source !== 'microphone',
});
const audio = new AudioSession(
  opts,
  (channels) => song.pcm(channels),
  () => (recorder.active ? recorder.stop() : undefined)
);
const song = new SongAssist(
  () => refreshReadouts(),
  (hint) => audio.post({ type: 'hint', bpm: hint?.bpm || 0, key: hint?.key || '' })
);
song.enabled = prefs.data.songAssist;
const crowd = new CrowdClient({
  onStatus: () => refreshReadouts(),
  getFps: () => renderer?.stats.fps || 0,
  getAudio: () => {
    const frame = audio.frame;
    if (!frame || (!audio.playing && audio.phase !== 'live')) return null;
    const r = frame.rhythm,
      p = frame.phrase;
    return {
      at: r.timestamp * 1000,
      beat: r.position - r.barOffset,
      bpm: r.bpm,
      meter: r.beatsPerBar,
      confidence: r.confidence,
      barConfidence: r.barConfidence,
      phraseConfidence: p.confidence,
      phraseBar: p.bar || 0,
      phraseBars: p.bars,
      ready: r.locked && r.barLocked && r.signalPresent && !r.coasting && r.conflict === 'NONE',
    };
  },
});
const model = { prefs, ui, audio, recorder, crowd, song };
let renderSettings,
  lastInputGeneration = -1,
  lastReadout = 0,
  pendingExport = false,
  frozenCsv = null;
function syncSettings() {
  renderSettings = { ...prefs.settings, paused: ui.paused };
}
function applyTheme() {
  document.documentElement.dataset.theme = prefs.data.theme;
  const light =
    prefs.data.theme === 'light' ||
    (prefs.data.theme === 'system' && matchMedia('(prefers-color-scheme: light)').matches);
  document.querySelector('meta[name=theme-color]').content = light ? '#fafaf8' : '#101110';
  document
    .querySelectorAll('[data-theme-choice]')
    .forEach((b) => b.setAttribute('aria-pressed', b.dataset.themeChoice === prefs.data.theme));
}
matchMedia('(prefers-color-scheme: light)').addEventListener('change', applyTheme);
function refreshScenes() {
  $('scenes').innerHTML = catalog.scenes
    .map(
      (s) =>
        '<button class="scene" data-scene="' +
        s.id +
        '" aria-pressed="' +
        (s.id === prefs.data.scene) +
        '"><span class="preview" style="background-image:url(./assets/scene-' +
        s.id +
        '.webp)"></span><span class="name">' +
        esc(s.title) +
        '</span></button>'
    )
    .join('');
  $('stage-title').textContent = prefs.scene.title;
}
function refreshPanel(resetScroll = false) {
  const scroll = resetScroll ? 0 : controls.scrollTop;
  const focus = controls.contains(document.activeElement) ? document.activeElement : null;
  const focusedId = focus?.id,
    focusKey = focus?.dataset.setting;
  const values = {};
  if (!resetScroll)
    for (const id of ['look-name', 'song-title', 'song-artist', 'song-key', 'invite'])
      if ($(id)) values[id] = $(id).value;
  controls.innerHTML = panelContent(model);
  for (const [id, value] of Object.entries(values)) if ($(id)) $(id).value = value;
  if ($('invite') && ui.invite) $('invite').value = ui.invite;
  if ($('last-video') && recorder.url) {
    $('view-video').href = recorder.url;
    $('last-video').href = recorder.url;
    $('last-video').download = recorder.file.name;
  }
  if (focusedId) $(focusedId)?.focus({ preventScroll: true });
  else if (focusKey)
    controls.querySelector('[data-setting="' + CSS.escape(focusKey) + '"]')?.focus({ preventScroll: true });
  controls.scrollTop = scroll;
  for (const b of $('tabs').children) {
    if (b.dataset.tab === ui.tab) b.setAttribute('aria-current', 'page');
    else b.removeAttribute('aria-current');
  }
  paintIcons(controls);
  refreshToolbar();
  refreshReadouts();
}
function refreshToolbar() {
  const paused = ui.paused;
  $('pause').setAttribute('aria-label', paused ? 'Resume visuals' : 'Pause visuals');
  $('pause').title = paused ? 'Resume visuals' : 'Pause visuals';
  $('pause').innerHTML =
    icon(paused ? 'play' : 'pause') + '<span>' + (paused ? 'Resume' : 'Pause') + '</span>';
  const label = recorder.active ? 'Stop recording' : 'Record session';
  for (const id of ['record', 'immersive-record']) {
    const el = $(id);
    el.disabled =
      !renderer ||
      !Recorder.supported ||
      crowd.connected ||
      (!recorder.active && ['starting', 'analyzing'].includes(audio.phase));
    el.classList.toggle('active', recorder.active);
    el.setAttribute('aria-label', label);
    el.title = recorder.active
      ? recorder.size
      : 'Record ' + (audio.active ? sourceNames[audio.source] : 'silent video');
    el.innerHTML =
      icon(recorder.active ? 'stop' : 'record') +
      (id === 'record' ? '<span>' + (recorder.active ? 'Stop' : 'Record') + '</span>' : '');
  }
  $('pause').disabled = crowd.connected;
  const file = audio.source === 'file' && audio.active;
  $('transport').hidden = !file;
  if (file) {
    $('track').textContent = audio.lastFile.name;
    $('transport-play').setAttribute('aria-label', audio.playing ? 'Pause track' : 'Play track');
    $('transport-play').innerHTML = icon(audio.playing ? 'pause' : 'play');
    $('transport')
      .querySelectorAll('[data-act=restart],[data-act=forward]')
      .forEach((el) => (el.disabled = recorder.active));
  }
  const showWave = file && (prefs.data.waveform ?? matchMedia('(min-width:761px)').matches);
  $('wave-panel').hidden = !showWave;
  wave.setAttribute('aria-disabled', String(recorder.active));
  if (file) wave.setAttribute('aria-valuemax', Math.round(audio.duration));
  paintIcons(document);
}
function refreshReadouts() {
  const f = audio.frame,
    r = f?.rhythm,
    p = f?.phrase;
  const hasTiming = r?.locked;
  const levelStatus = !audio.active
    ? 'Disconnected'
    : !f
      ? audio.source === 'file' && !audio.playing
        ? 'Paused'
        : 'Listening…'
      : !r.signalPresent
        ? 'Silent input'
        : f.peak < 0.015
          ? 'Very quiet audio'
          : 'Sound received';
  const meter = r?.beatsPerBar || 4;
  const beat = hasTiming ? ((((Math.floor(r.position) - r.barOffset) % meter) + meter) % meter) + 1 : null;
  const timing = hasTiming ? Math.round(r.bpm) + ' BPM · Beat ' + beat + '/' + meter : 'Tempo unresolved';
  const confidence = r
    ? 'Beat ' +
      Math.round(r.confidence * 100) +
      '% · Bar ' +
      Math.round(r.barConfidence * 100) +
      '% · Phrase ' +
      Math.round(p.confidence * 100) +
      '%'
    : '';
  if ($('audio-meter'))
    $('audio-meter').textContent = [levelStatus, timing, confidence].filter(Boolean).join(' · ');
  if ($('offline-state')) $('offline-state').textContent = ui.offline;
  if ($('level')) $('level').value = Math.min(1, Math.sqrt(f?.rms || 0));
  if ($('progress')) $('progress').value = audio.progress;
  const stats = renderer?.stats;
  $('fps').textContent = (stats?.fps ? Math.round(stats.fps) : '…') + ' FPS';
  if ($('render-stats') && stats)
    $('render-stats').textContent =
      stats.width +
      ' × ' +
      stats.height +
      ' · ' +
      Math.round(stats.scale * 100) +
      '% render scale · ' +
      stats.targetFps +
      ' FPS target';
  const crowdStatus = crowd.status;
  if ($('crowd-status'))
    $('crowd-status').textContent = [
      crowdStatus.phase,
      crowdStatus.message,
      crowdStatus.connected && crowdStatus.clockMs != null
        ? 'Clock ±' + crowdStatus.clockMs.toFixed(1) + ' ms'
        : '',
      crowdStatus.bpm ? Math.round(crowdStatus.bpm) + ' BPM' : '',
      crowdStatus.connected
        ? 'Beat ' +
          Math.round(crowdStatus.beatConfidence * 100) +
          '% · Bar ' +
          Math.round(crowdStatus.barConfidence * 100) +
          '% · Phrase ' +
          Math.round(crowdStatus.phraseConfidence * 100) +
          '%'
        : '',
      crowdStatus.phraseBar ? 'Phrase bar ' + crowdStatus.phraseBar : '',
      crowdStatus.connected ? 'Missed ' + crowdStatus.missed : '',
    ]
      .filter(Boolean)
      .join(' · ');
  const mode = crowd.connected
    ? 'Crowd show'
    : renderSettings.audioEnabled && audio.active
      ? sourceNames[audio.source]
      : 'Ambient';
  $('input-mode').textContent = mode;
  $('input-mode').classList.toggle('active', mode !== 'Ambient');
  $('monitor').hidden = !(
    prefs.data.tabletMonitor &&
    matchMedia('(min-width:761px)').matches &&
    (audio.active || crowd.connected)
  );
  if (!$('monitor').hidden) {
    $('monitor').title = ui.monitorClosed ? 'Expand timing monitor' : 'Minimize timing monitor';
    $('monitor').setAttribute('aria-expanded', String(!ui.monitorClosed));
    $('monitor').textContent = ui.monitorClosed
      ? 'Timing'
      : crowd.connected
        ? crowdStatus.phase + ' · ' + (crowdStatus.bpm || '…') + ' BPM'
        : [
            song.trackTitle,
            levelStatus + ' · ' + timing,
            confidence,
            p?.bar ? 'Phrase bar ' + p.bar + '/' + p.bars : '',
          ]
            .filter(Boolean)
            .join('\n');
  }
  if ($('song-status'))
    $('song-status').textContent = [song.trackTitle, song.message].filter(Boolean).join(' · ');
  if ($('cue-message'))
    $('cue-message').textContent =
      (prefs.data.looks.find((l) => l.id === ui.queued)?.name || '') +
      ' · ' +
      (f?.cueMessage || 'Waiting for a stable phrase grid');
  if (!$('transport').hidden)
    $('track-time').textContent = clock(audio.position) + ' / ' + clock(audio.duration);
  if (!$('wave-panel').hidden) {
    wave.setAttribute('aria-valuenow', Math.round(audio.position));
    wave.setAttribute('aria-valuetext', clock(audio.position));
    drawWave(wave, audio.track, audio.position);
  }
  if ($('diag-plot')) {
    const sample = (diag.frozen || diag.rows).at(-1);
    $('diagnostic-readout').textContent = sample
      ? [
          Math.round(sample.bpm) + ' BPM',
          sample.error != null ? 'Matched beat ' + Math.round(sample.error) + ' ms' : '',
          sample.status,
        ]
          .filter(Boolean)
          .join(' · ')
      : 'Waiting for timing samples';
    diag.draw($('diag-plot'), ui.diagWindow);
  }
}
function draw(time) {
  audio.sample(time);
  const frame = crowd.connected ? crowd.frame(time) : null;
  renderer?.draw(time, renderSettings, audio.frame, frame);
  if (time - lastReadout >= 100) {
    lastReadout = time;
    refreshReadouts();
  }
  requestAnimationFrame(draw);
}
function cue(look) {
  ui.queued = look?.id || null;
  audio.cue(!!look, prefs.data.syncOffsetMs);
}
function applyLook(look) {
  if (!look) return;
  cue(null);
  const enabled = renderSettings.audioEnabled;
  prefs.apply(look);
  prefs.setPattern('audioEnabled', enabled);
  syncSettings();
  renderer?.transitionTo(renderSettings, prefs.data.seconds);
  refreshScenes();
  refreshPanel();
  notice('Applied ' + look.name);
}
function followReaction(previous) {
  if (
    previous &&
    !renderSettings.audioEnabled &&
    audio.source !== 'file' &&
    !recorder.active &&
    !crowd.source &&
    audio.phase !== 'idle'
  )
    audio.stop();
}
function setScene(id) {
  if (crowd.connected) {
    notice('Leave the crowd show to choose a local pattern.');
    return;
  }
  const enabled = renderSettings.audioEnabled;
  prefs.setScene(id);
  ui.tune = '';
  syncSettings();
  followReaction(enabled);
  refreshScenes();
  refreshPanel(true);
}
function showPanel(tab, view) {
  ui.tab = tab;
  if (view) ui.settings = view;
  refreshPanel(true);
}
function inputChange(el) {
  const name = el.dataset.setting;
  let value =
    el.type === 'checkbox'
      ? el.checked
      : el.type === 'range' || (el.tagName === 'SELECT' && name !== 'source')
        ? Number(el.value)
        : el.value;
  if (name === 'source') {
    if (recorder.active) return;
    audio.stop();
    prefs.set(name, value);
  } else if (name.startsWith('pattern.')) {
    const enabled = renderSettings.audioEnabled;
    prefs.setPattern(name.slice(8), value);
    syncSettings();
    followReaction(enabled);
  } else if (name.startsWith('fx.')) prefs.setEffect(name.slice(3), value);
  else if (name === 'flashes' || name === 'blackout') crowd[name] = value;
  else if (name === 'crowdBrightness') {
    ui.crowdBrightness = value;
    canvas.style.filter = 'brightness(' + value + ')';
  } else if (name === 'diagWindow') ui.diagWindow = value;
  else {
    prefs.set(name, value);
    if (name === 'songAssist') song.allow(value);
    if (['beatsPerBar', 'phraseBars', 'sensitivity'].includes(name)) audio.setOptions();
    if (name === 'syncOffsetMs' && ui.queued) audio.cue(true, value);
  }
  syncSettings();
  if (el.type === 'range') {
    el.parentElement.querySelector('output').textContent = value;
  } else refreshPanel();
}
function dialog(title, message, initial, accept) {
  const d = $('dialog');
  $('dialog-title').textContent = title;
  $('dialog-content').textContent = message;
  const input = initial != null ? document.createElement('input') : null;
  if (input) {
    input.type = 'text';
    input.maxLength = 48;
    input.value = initial;
    input.setAttribute('aria-label', title);
    $('dialog-content').appendChild(input);
  }
  const ok = document.createElement('button');
  ok.value = 'ok';
  ok.className = 'primary';
  ok.textContent = accept ? 'Save' : 'Close';
  const actions = $('dialog-actions');
  actions.replaceChildren();
  if (accept) {
    const cancel = document.createElement('button');
    cancel.value = 'cancel';
    cancel.textContent = 'Cancel';
    actions.append(cancel);
  }
  actions.append(ok);
  d.returnValue = '';
  d.onclose = () => {
    if (d.returnValue === 'ok' && accept) accept(input?.value.trim());
  };
  d.showModal();
  if (input) {
    input.focus();
    input.select();
  }
}
function immerse(value) {
  ui.immersive = value;
  void keepScreenAwake();
  document.body.classList.toggle('immersive', value);
  $('exit-immersive').hidden = !value;
  $('immersive-record').hidden = !value;
  if (value) document.documentElement.requestFullscreen?.().catch(() => {});
  else if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
}
document.addEventListener('fullscreenchange', () => {
  if (ui.immersive && !document.fullscreenElement) immerse(false);
});
function record() {
  if (recorder.active) {
    recorder.stop();
    return;
  }
  if (!renderer || crowd.connected || ['starting', 'analyzing'].includes(audio.phase)) return;
  renderer.setRecording(true);
  try {
    recorder.start(canvas, audio.active ? audio.recordBus?.stream : null, renderer.stats.targetFps || 60);
    audio.locked = true;
    notice(
      'Recording ' + (audio.active ? sourceNames[audio.source] : 'silent video') + ' · ' + recorder.size
    );
    refreshPanel();
  } catch (error) {
    renderer.setRecording(false);
    notice(error.message);
  }
}
function download(url, name) {
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.append(link);
  link.click();
  link.remove();
}
function exportCsv(csv) {
  if (!csv || csv.trim().split('\n').length < 2) {
    notice('No diagnostic samples to export.');
    return;
  }
  const u = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  download(u, 'prism-timing.csv');
  setTimeout(() => URL.revokeObjectURL(u), 30000);
}
async function action(name, el) {
  const look = prefs.data.looks.find((l) => l.id === el?.closest('[data-look]')?.dataset.look);
  if (crowd.connected && !crowd.source && ['connect', 'choose', 'reanalyze'].includes(name)) return;
  if (name.startsWith('timing-')) {
    audio.command(name.slice(7));
    return;
  }
  switch (name) {
    case 'palette':
      prefs.setPattern('palette', Number(el.dataset.id));
      break;
    case 'shuffle':
      prefs.shuffle();
      break;
    case 'reset':
      prefs.reset();
      break;
    case 'save-quick':
      prefs.saveQuick();
      notice('Saved ' + prefs.scene.title + ' look.');
      break;
    case 'recall':
      if (prefs.data.quick[prefs.data.scene]) prefs.apply(prefs.data.quick[prefs.data.scene], false);
      break;
    case 'save-look':
      prefs.addLook($('look-name').value);
      $('look-name').value = '';
      notice('Look saved.');
      break;
    case 'favorite':
      prefs.updateLook(look.id, { favorite: !look.favorite });
      break;
    case 'rename':
      dialog('Rename look', '', look.name, (value) => {
        if (value) {
          prefs.updateLook(look.id, { name: value });
          refreshPanel();
        }
      });
      return;
    case 'delete':
      if (look.id === ui.queued) cue(null);
      prefs.deleteLook(look.id);
      break;
    case 'apply':
      applyLook(look);
      return;
    case 'cue':
      cue(look);
      break;
    case 'cancel-cue':
      cue(null);
      break;
    case 'apply-queued':
      applyLook(prefs.data.looks.find((l) => l.id === ui.queued));
      return;
    case 'install':
      if (ui.install) {
        await ui.install.prompt();
        await ui.install.userChoice;
        ui.install = null;
      }
      break;
    case 'panel-toggle':
      ui.panelClosed = !ui.panelClosed;
      document.body.classList.toggle('panel-closed', ui.panelClosed);
      $('panel').hidden = ui.panelClosed;
      $('panel-toggle').textContent = ui.panelClosed ? 'Show panel' : 'Hide panel';
      $('panel-toggle').setAttribute('aria-expanded', String(!ui.panelClosed));
      return;
    case 'monitor':
      ui.monitorClosed = !ui.monitorClosed;
      refreshReadouts();
      return;
    case 'recenter':
      ui.pan = [0, 0];
      renderer?.setPan(0, 0);
      return;
    case 'connect':
      prefs.setPattern('audioEnabled', true);
      syncSettings();
      await audio.connect(prefs.data.source);
      return;
    case 'disconnect':
      if (!recorder.active) audio.stop('Disconnected');
      return;
    case 'choose':
      if (!recorder.active) $('file').click();
      return;
    case 'reanalyze':
      await audio.loadFile(audio.lastFile);
      return;
    case 'play':
      if (audio.playing) audio.pause();
      else await audio.play();
      return;
    case 'restart':
      audio.seek(0);
      return;
    case 'forward':
      audio.seek(audio.position + 10);
      return;
    case 'wave-hide':
      prefs.set('waveform', false);
      break;
    case 'record-action':
      record();
      return;
    case 'share-video':
      await navigator.share({ files: [recorder.file] });
      return;
    case 'freeze':
      ui.frozen = !ui.frozen;
      diag.frozen = ui.frozen ? [...diag.rows] : null;
      frozenCsv = null;
      if (ui.frozen) audio.diagnostics();
      break;
    case 'export':
      if (!diag.rows.length && !diag.frozen?.length) {
        notice('No diagnostic samples to export.');
        return;
      }
      if (ui.frozen && frozenCsv) exportCsv(frozenCsv);
      else {
        pendingExport = true;
        audio.diagnostics();
      }
      return;
    case 'clear-diag':
      ui.frozen = false;
      frozenCsv = null;
      pendingExport = false;
      diag.clear();
      audio.clearDiagnostics();
      break;
    case 'lookup': {
      const value = $('song-title').value.trim();
      await song.search({ title: value, id: value, artist: $('song-artist').value.trim() });
      return;
    }
    case 'set-key':
      song.setKey($('song-key').value.trim());
      $('song-key').value = '';
      break;
    case 'remove-key':
      song.setKey('');
      break;
    case 'identify':
      await song.recognize();
      return;
    case 'join': {
      if (recorder.active) {
        notice('Finish the recording before joining a show.');
        return;
      }
      const invite = $('invite').value;
      ui.invite = '';
      el.disabled = true;
      try {
        await crowd.join(invite);
        void keepScreenAwake();
        if (!crowd.source) audio.stop();
        cue(null);
      } finally {
        refreshPanel();
      }
      break;
    }
    case 'leave':
      crowd.leave();
      void keepScreenAwake();
      ui.crowdBrightness = 1;
      canvas.style.filter = '';
      break;
  }
  syncSettings();
  refreshPanel();
}
document.addEventListener('click', (e) => {
  const b = e.target.closest('button');
  if (!b || b.disabled) return;
  try {
    if (b.dataset.act) {
      void action(b.dataset.act, b).catch((err) => {
        if (err.name !== 'AbortError') notice(err.message);
      });
      return;
    }
    if (b.dataset.help) {
      dialog(b.dataset.help, catalog.help[b.dataset.help]);
      return;
    }
    if (b.dataset.tab) {
      showPanel(b.dataset.tab);
      return;
    }
    if (b.dataset.view) {
      ui[b.dataset.view] = b.dataset.value;
      refreshPanel();
      return;
    }
    if (b.dataset.themeChoice) {
      prefs.set('theme', b.dataset.themeChoice);
      applyTheme();
      return;
    }
    if (b.dataset.scene) {
      setScene(Number(b.dataset.scene));
      return;
    }
    switch (b.id) {
      case 'pause':
        ui.paused = !ui.paused;
        syncSettings();
        if (ui.paused && ui.queued) audio.cue(false);
        else if (ui.queued) audio.cue(true, prefs.data.syncOffsetMs);
        if (
          ui.paused &&
          !recorder.active &&
          !crowd.source &&
          audio.source !== 'file' &&
          audio.phase !== 'idle'
        )
          audio.stop();
        refreshToolbar();
        break;
      case 'record':
      case 'immersive-record':
        record();
        break;
      case 'immerse':
        immerse(true);
        break;
      case 'exit-immersive':
        immerse(false);
        break;
    }
  } catch (error) {
    notice(error.message);
  }
});
controls.addEventListener('input', (e) => {
  if (e.target.type === 'range') inputChange(e.target);
});
controls.addEventListener('change', (e) => {
  if (e.target.dataset.setting && e.target.type !== 'range') inputChange(e.target);
});
$('file').addEventListener('change', async (e) => {
  const file = e.target.files[0];
  e.target.value = '';
  if (file && !(crowd.connected && !crowd.source)) {
    prefs.setPattern('audioEnabled', true);
    syncSettings();
    await audio.loadFile(file);
  }
});
audio.addEventListener('change', () => {
  if (audio.generation !== lastInputGeneration) {
    lastInputGeneration = audio.generation;
    song.reset();
  }
  if (audio.phase === 'starting') {
    diag.clear();
    ui.frozen = false;
    frozenCsv = null;
  }
  refreshPanel();
});
audio.addEventListener('progress', refreshReadouts);
audio.addEventListener('frame', (e) => {
  diag.add(e.detail);
  if (ui.queued && e.detail.cueFired && !ui.paused && !crowd.connected)
    applyLook(prefs.data.looks.find((l) => l.id === ui.queued));
});
audio.addEventListener('diagnostics', (e) => {
  if (ui.frozen && !frozenCsv) frozenCsv = e.detail.csv;
  if (pendingExport) {
    pendingExport = false;
    exportCsv(ui.frozen ? frozenCsv : e.detail.csv);
  }
});
recorder.addEventListener('finish', (e) => {
  audio.locked = false;
  renderer?.setRecording(false);
  if (!renderSettings.audioEnabled && audio.source !== 'file' && !crowd.source && audio.phase === 'live')
    audio.stop();
  if (e.detail) notice(e.detail);
  else {
    download(recorder.url, recorder.file.name);
    notice('Recording ready. Download it again in Settings → Display.');
  }
  refreshPanel();
});
let drag = null;
canvas.addEventListener('pointerdown', (e) => {
  if (crowd.connected) return;
  drag = { x: e.clientX, y: e.clientY, pan: [...ui.pan], moved: false };
  canvas.setPointerCapture(e.pointerId);
});
canvas.addEventListener('pointermove', (e) => {
  if (!drag) return;
  const dx = e.clientX - drag.x,
    dy = e.clientY - drag.y,
    dim = Math.max(1, Math.min(canvas.clientWidth, canvas.clientHeight));
  if (Math.hypot(dx, dy) > 4) drag.moved = true;
  ui.pan = [
    Math.max(-1, Math.min(1, drag.pan[0] + (dx * 2) / dim)),
    Math.max(-1, Math.min(1, drag.pan[1] - (dy * 2) / dim)),
  ];
  renderer?.setPan(...ui.pan);
});
canvas.addEventListener('pointerup', () => {
  if (ui.immersive && drag && !drag.moved) immerse(false);
  drag = null;
});
canvas.addEventListener('pointercancel', () => {
  drag = null;
});
const seekWave = (e) => {
  const rect = wave.getBoundingClientRect();
  audio.seek(((e.clientX - rect.left) / rect.width) * audio.duration);
};
wave.addEventListener('pointerdown', (e) => {
  if (recorder.active) return;
  wave.setPointerCapture(e.pointerId);
  seekWave(e);
});
wave.addEventListener('pointermove', (e) => {
  if (wave.hasPointerCapture(e.pointerId)) seekWave(e);
});
wave.addEventListener('keydown', (e) => {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return;
  e.preventDefault();
  audio.seek(
    { ArrowLeft: audio.position - 5, ArrowRight: audio.position + 5, Home: 0, End: audio.duration }[e.key]
  );
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && ui.immersive) immerse(false);
});
document.addEventListener('visibilitychange', () => {
  crowd.setVisible(!document.hidden);
  void keepScreenAwake();
  if (document.hidden) {
    recorder.stop();
    if (audio.source === 'microphone') audio.stop('Microphone stopped while hidden. Connect to resume.');
  }
});
window.addEventListener('pagehide', () => {
  recorder.stop();
  crowd.leave();
  audio.stop();
});
window.addEventListener('resize', refreshToolbar);
syncSettings();
applyTheme();
refreshScenes();
refreshPanel();
requestAnimationFrame(draw);
if ('serviceWorker' in navigator && window.isSecureContext) {
  navigator.serviceWorker
    .register(new URL('../sw.js', import.meta.url))
    .then(async (registration) => {
      const worker = registration.installing;
      worker?.addEventListener('statechange', () => {
        if (worker.state === 'redundant') ui.offline = 'Offline setup could not finish. Reload to retry.';
      });
      await navigator.serviceWorker.ready;
      ui.offline = 'Ready for offline use on this device.';
    })
    .catch(() => {
      ui.offline = 'Offline storage is unavailable in this browser.';
    });
} else ui.offline = 'Use HTTPS or localhost to install an offline copy.';
