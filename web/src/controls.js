const icons = {
  monitor: '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8m-4-4v4"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>',
  moon: '<path d="M20.7 14A8.8 8.8 0 0 1 10 3.3 9 9 0 1 0 20.7 14Z"/>',
  pause: '<path d="M8 5v14m8-14v14" stroke-width="3"/>',
  play: '<path d="m7 4 13 8-13 8Z"/>',
  record: '<circle cx="12" cy="12" r="7"/>',
  stop: '<rect x="5" y="5" width="14" height="14" rx="2"/>',
  expand: '<path d="M9 3H3v6m12-6h6v6M3 15v6h6m12-6v6h-6"/>',
  collapse: '<path d="M3 9h6V3m6 0v6h6M3 15h6v6m6 0v-6h6"/>',
  wave: '<path d="M2 12h3l2-6 3 12 4-15 3 15 2-6h3"/>',
  sliders:
    '<path d="M3 7h6m5 0h7M3 17h11m5 0h2"/><circle cx="11.5" cy="7" r="2.5"/><circle cx="16.5" cy="17" r="2.5"/>',
  layers: '<path d="m12 3 10 5-10 5L2 8Zm-9 10 9 4.5 9-4.5M3 17l9 4.5 9-4.5"/>',
  settings:
    '<path d="m9 3 1-1h4l1 3 2 1 3-.3 2 3-2 2v3l1.7 2-2 3-3-.3-2 1-1 2h-4l-1-2-2-1-3 .3-2-3L4 14v-3L2.3 9l2-3 3 .3 2-1Z"/><circle cx="12" cy="12" r="3"/>',
  crosshair: '<circle cx="12" cy="12" r="7"/><path d="M12 2v5m0 10v5M2 12h5m10 0h5"/>',
  restart: '<path d="M5 4v16m13-15L7 12l11 7Z"/>',
  forward: '<path d="m3 6 7 6-7 6m9-12 7 6-7 6"/>',
  star: '<path d="m12 2 3 6.5 7 .9-5.2 5 .9 7.2L12 18l-5.7 3.6.9-7.2-5.2-5 7-.9Z"/>',
};
export const esc = (value) =>
  String(value ?? '').replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]
  );
export const icon = (name) => '<i data-icon="' + name + '" aria-hidden="true"></i>';
export function paintIcons(root) {
  root.querySelectorAll('i[data-icon]:empty').forEach((el) => {
    el.setAttribute('aria-hidden', 'true');
    el.innerHTML =
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">' +
      (icons[el.dataset.icon] || '') +
      '</svg>';
  });
}
export const clock = (time) => Math.floor(time / 60) + ':' + String(Math.floor(time % 60)).padStart(2, '0');
const group = (title, content) => '<section class="group"><h3>' + title + '</h3>' + content + '</section>';
const btn = (action, label, flags = '') =>
  '<button type="button" data-act="' + action + '" ' + flags + '>' + label + '</button>';
const actions = (...buttons) => '<div class="actions">' + buttons.join('') + '</div>';
const title = (heading, context = '') =>
  (context ? '<span class="eyebrow">' + context + '</span>' : '') + '<h2>' + heading + '</h2>';
const help = (label, catalog) =>
  catalog.help[label]
    ? '<button type="button" class="help" data-help="' +
      esc(label) +
      '" aria-label="Help for ' +
      esc(label) +
      '"><span aria-hidden="true">i</span></button>'
    : '';
const check = (name, label, value, flags = '') =>
  '<label class="line"><span>' +
  label +
  '</span><input type="checkbox" data-setting="' +
  name +
  '" ' +
  (value ? 'checked ' : '') +
  flags +
  '></label>';
const select = (name, label, value, choices, flags = '') =>
  '<label class="line">' +
  label +
  '<select aria-label="' +
  esc(label) +
  '" data-setting="' +
  name +
  '" ' +
  flags +
  '>' +
  choices
    .map(
      ([v, l]) =>
        '<option value="' +
        esc(v) +
        '"' +
        (String(v) === String(value) ? ' selected' : '') +
        '>' +
        l +
        '</option>'
    )
    .join('') +
  '</select></label>';
function slider(name, label, value, min, max, step, catalog) {
  return (
    '<div class="slider"><div class="row"><span><label for="sl-' +
    name +
    '">' +
    label +
    '</label>' +
    help(label, catalog) +
    '</span><output for="sl-' +
    name +
    '">' +
    value +
    '</output></div><input type="range" id="sl-' +
    name +
    '" data-setting="' +
    name +
    '" min="' +
    min +
    '" max="' +
    max +
    '" step="' +
    step +
    '" value="' +
    value +
    '"></div>'
  );
}
const segments = (name, current, options) =>
  '<div class="segments" role="group" aria-label="' +
  name +
  '">' +
  options
    .map(
      (value) =>
        '<button type="button" data-view="' +
        name +
        '" data-value="' +
        value +
        '" class="' +
        (value === current ? 'selected' : '') +
        '" aria-pressed="' +
        (value === current) +
        '">' +
        value +
        '</button>'
    )
    .join('') +
  '</div>';
const shape = {
  1: [
    'Mirrors',
    'Recursion depth',
    'Mirror segments',
    'Fold angle',
    'Zoom',
    'Edge width',
    'Ring drift',
    'Fold evolution',
  ],
  2: [
    'Tunnel',
    'Ring density',
    'Tunnel facets',
    'Tunnel twist',
    'Tunnel scale',
    'Engraving width',
    'Travel speed',
    'Twist evolution',
  ],
  7: ['Zoom', 'Fractal iterations', null, 'Zoom depth', 'Framing', 'Contour width', 'Zoom speed', null],
  9: [
    'Chambers',
    'Fractal detail',
    null,
    'Fold shape',
    'Camera zoom',
    'Filament thickness',
    'Camera drift',
    'Fractal evolution',
  ],
};
function tune({ prefs, ui }) {
  const s = prefs.settings,
    c = prefs.catalog,
    labels = shape[s.scene];
  const scale = (name, label, lo, hi, step = 0.01) =>
    slider('pattern.' + name, label, s[name], lo, hi, step, c);
  const views = [labels[0], s.scene === 7 ? 'Journey' : 'Motion', 'Color', 'Beat'];
  const view = views.includes(ui.tune) ? ui.tune : views[0];
  let body;
  if (view === views[0]) {
    body =
      (s.scene === 9
        ? select('pattern.kaleidoscopeShape', 'Space shape', s.kaleidoscopeShape, [
            [0, 'Chamber'],
            [1, 'Gallery'],
            [2, 'Tunnel'],
          ])
        : '') +
      scale('complexity', labels[1], 0, 1) +
      (labels[2] ? scale('symmetry', labels[2], 3, 20, 1) : '') +
      (s.scene === 9 ? scale('kaleidoscopeSpace', 'Open space', 0.4, 2) : '') +
      scale('distortion', labels[3], 0, 1.5) +
      scale('zoom', labels[4], 0.45, 3) +
      scale('lineWidth', labels[5], 0.6, 3);
  } else if (view === views[1]) {
    body =
      scale('speed', labels[6], 0.05, 2) +
      scale('rotation', 'Rotation', -1, 1) +
      (labels[7] ? scale('morph', labels[7], 0, 1.5) : '');
  } else if (view === 'Color') {
    body =
      '<h3>Palette</h3><div class="segments">' +
      c.palettes
        .map((p) =>
          btn(
            'palette',
            '<span class="colors" aria-hidden="true">' +
              p.colors.map((color) => '<b style="background:' + color + '"></b>').join('') +
              '</span>' +
              p.title,
            'class="palette ' +
              (p.id === s.palette ? 'selected' : '') +
              '" aria-pressed="' +
              (p.id === s.palette) +
              '" data-id="' +
              p.id +
              '"'
          )
        )
        .join('') +
      '</div>' +
      scale('hue', 'Hue shift', 0, 1) +
      scale('saturation', 'Saturation', 0, 1.6) +
      scale('intensity', 'Brightness', 0.25, 1.6) +
      scale('contrast', 'Contrast', 0.6, 1.8) +
      scale('colorSpeed', 'Color cycling', 0, 0.2, 0.005);
  } else {
    body =
      check('pattern.audioEnabled', 'React to audio', s.audioEnabled) +
      scale('audioAmount', 'Reaction strength', 0, 2.5) +
      scale('beatImpact', { 2: 'Beat travel', 7: 'Beat zoom' }[s.scene] || 'Beat expansion', 0, 2) +
      scale('barImpact', { 7: 'Bar rotation', 9: 'Bar fold' }[s.scene] || 'Bar twist', 0, 2) +
      scale('pulseLength', 'Pulse length', 0.08, 0.8) +
      scale('flowImpact', 'Shape breathing', 0, 2) +
      scale('textureAmount', 'Treble detail', 0, 1) +
      scale('downbeatFlash', 'Downbeat flash', 0, 1) +
      scale('downbeatInvert', 'Color inversion', 0, 1) +
      scale('invertFadeMs', 'Invert fade', 60, 400, 5);
  }
  return (
    title(prefs.scene.title, 'Pattern tuning') +
    segments('tune', view, views) +
    '<div class="group">' +
    body +
    '</div>' +
    group(
      'Pattern look',
      actions(
        btn('shuffle', 'Shuffle'),
        btn('save-quick', 'Save'),
        btn('recall', 'Recall', prefs.data.quick[s.scene] ? '' : 'disabled'),
        btn('reset', 'Reset')
      )
    )
  );
}
function audioPanel({ prefs, audio, recorder, crowd }) {
  if (crowd.connected && !crowd.source)
    return (
      title('Show audio') +
      '<p class="info">Following timing from the Windows controller. An audio-input invitation enables sending timing from this device.</p>'
    );
  const d = prefs.data,
    s = prefs.settings,
    source = d.source,
    file = source === 'file',
    locked = recorder.active ? 'disabled' : '';
  const meter = [
      [0, 'Auto'],
      [3, '3 beats'],
      [4, '4 beats'],
    ],
    phrase = [
      [8, '8 bars'],
      [16, '16 bars'],
      [32, '32 bars'],
    ];
  const pending = ['starting', 'analyzing'].includes(audio.phase);
  const canControl = audio.phase === 'live';
  const live =
    '<p class="info">' +
    (source === 'system'
      ? 'Choose a browser tab or screen with audio sharing enabled.'
      : 'Use the microphone for live music or a room input.') +
    '</p>' +
    actions(
      pending || canControl
        ? btn('disconnect', pending ? 'Cancel' : 'Disconnect', locked)
        : btn('connect', 'Connect audio', 'class="action-main" ' + locked)
    );
  const fileActions = actions(
    btn('choose', 'Choose file', 'class="action-main" ' + locked),
    ...(audio.lastFile && !pending ? [btn('reanalyze', 'Analyze again', locked)] : []),
    ...(pending ? [btn('disconnect', 'Cancel', locked)] : [])
  );
  const status =
    '<p class="metric" id="audio-status" role="status">' +
    esc(audio.message || 'Disconnected') +
    '</p>' +
    (pending && file
      ? '<progress id="progress" max="1" value="' +
        audio.progress +
        '" aria-label="Analysis progress"></progress>'
      : '');
  return (
    title('Audio input') +
    check('pattern.audioEnabled', 'React to audio', s.audioEnabled) +
    select(
      'source',
      'Source',
      source,
      [
        ['microphone', 'Microphone'],
        ['system', 'Tab / system audio'],
        ['file', 'Local file'],
      ],
      locked
    ) +
    (file ? fileActions : live) +
    status +
    (file
      ? group(
          'Local track',
          '<p class="info">' +
            esc(
              audio.lastFile?.name || 'Choose a track to analyze its beat grid and waveform on this device.'
            ) +
            '</p>' +
            (audio.track
              ? '<p class="metric">' +
                clock(audio.duration) +
                ' · ' +
                (audio.track.bpm ? Math.round(audio.track.bpm) + ' BPM' : 'Tempo unresolved') +
                '</p>'
              : '') +
            select('fileMeter', 'Analysis meter', d.fileMeter, meter, locked) +
            select('filePhraseBars', 'Phrase length', d.filePhraseBars, phrase, locked) +
            check('waveform', 'Track waveform', d.waveform ?? matchMedia('(min-width:761px)').matches)
        )
      : group(
          'Live timing',
          select('beatsPerBar', 'Meter', d.beatsPerBar, meter) +
            select('phraseBars', 'Phrase length', d.phraseBars, phrase) +
            actions(
              ...[
                ['tap', 'Tap tempo'],
                ['beat', 'Beat starts here'],
                ['bar', 'Bar starts here'],
                ['half', '½ BPM'],
                ['double', '2× BPM'],
                ['auto', 'Auto timing'],
                ['phrase', 'Phrase starts here'],
                ['autoPhrase', 'Auto phrase'],
              ].map(([k, label]) => btn('timing-' + k, label, canControl ? '' : 'disabled'))
            )
        )) +
    group(
      'Input monitor',
      '<div id="audio-meter" class="metric">Listening for input</div><meter class="meter" id="level" min="0" max="1" value="0" aria-label="Input level"></meter>' +
        slider('sensitivity', 'Input gain', s.sensitivity, 0.4, 4, 0.1, prefs.catalog) +
        slider('syncOffsetMs', 'Sync offset', s.syncOffsetMs, -250, 250, 5, prefs.catalog)
    )
  );
}
function looks({ prefs, ui }) {
  const d = prefs.data;
  return (
    title('Look library') +
    '<div class="group"><label for="look-name">Save this look</label><input id="look-name" type="text" placeholder="Look name" maxlength="48" autocomplete="off">' +
    actions(btn('save-look', 'Save look', 'class="action-main"')) +
    '</div>' +
    slider('seconds', 'Transition seconds', d.seconds, 0, 8, 0.1, prefs.catalog) +
    (ui.queued
      ? group(
          'Queued look',
          '<span id="cue-message"></span>' +
            actions(btn('apply-queued', 'Apply now'), btn('cancel-cue', 'Cancel'))
        )
      : '') +
    group(
      'Saved looks · ' + d.looks.length + '/32',
      d.looks.length
        ? [...d.looks]
            .sort((a, b) => Number(b.favorite) - Number(a.favorite))
            .map(
              (l) =>
                '<article class="look" data-look="' +
                esc(l.id) +
                '"><div class="row"><strong>' +
                esc(l.name) +
                '</strong>' +
                btn(
                  'favorite',
                  l.favorite ? '★' : '☆',
                  'aria-label="Favorite ' + esc(l.name) + '" aria-pressed="' + l.favorite + '"'
                ) +
                '</div><span class="info">' +
                esc(prefs.catalog.scenes.find((s) => s.id === l.scene)?.title) +
                '</span>' +
                actions(
                  btn('apply', 'Play'),
                  btn('cue', 'Cue'),
                  btn('rename', 'Rename'),
                  btn('delete', 'Delete')
                ) +
                '</article>'
            )
            .join('')
        : '<p class="info">Save a pattern and its effects to recall it here.</p>'
    )
  );
}
function settings({ prefs, ui, recorder, crowd, audio, song }) {
  const d = prefs.data,
    e = prefs.settings.effects,
    c = prefs.catalog,
    view = ui.settings;
  const fx = (k, label, max = 1) => slider('fx.' + k, label, e[k], 0, max, 0.01, c);
  let body = '';
  if (view === 'Display')
    body =
      select('targetFps', 'Frame-rate target', d.targetFps, [
        [30, '30 FPS'],
        [60, '60 FPS'],
      ]) +
      check('adaptiveQuality', 'Adaptive quality', d.adaptiveQuality) +
      check('batterySaver', 'Battery saver', d.batterySaver) +
      check('tabletMonitor', 'Timing monitor', d.tabletMonitor) +
      '<p id="render-stats" class="info"></p>' +
      group(
        'Offline app',
        '<p class="info" id="offline-state"></p>' +
          actions(btn('install', 'Install prism', ui.install ? '' : 'hidden'))
      ) +
      group(
        'Recording',
        '<p class="info">Record the visual canvas with the connected input. Download the finished video here.</p>' +
          actions(
            btn('record-action', recorder.active ? 'Stop recording' : 'Record session'),
            '<a class="button" id="view-video" target="_blank" rel="noopener" ' +
              (recorder.url ? '' : 'hidden') +
              '>View last video</a>',
            '<a class="button" id="last-video" ' +
              (recorder.url ? '' : 'hidden') +
              ' download>Download last video</a>',
            btn(
              'share-video',
              'Share',
              recorder.file && navigator.canShare?.({ files: [recorder.file] }) ? '' : 'hidden'
            )
          )
      );
  if (view === 'Effects')
    body =
      check('fx.enabled', 'Global effects', e.enabled) +
      fx('strength', 'Effect strength') +
      fx('bloom', 'Bloom', 2) +
      fx('bloomRadius', 'Bloom radius') +
      fx('threshold', 'Bloom threshold') +
      fx('blur', 'Blur') +
      fx('blurRadius', 'Blur radius') +
      select('fx.quality', 'Effect quality', e.quality, [
        [0, 'Economy'],
        [1, 'Balanced'],
        [2, 'High'],
      ]);
  if (view === 'Diagnostics')
    body =
      '<div id="diagnostic-readout" class="metric"></div><canvas id="diag-plot" aria-label="Beat and downbeat predictions, tracked beats and timing confidence"></canvas><p class="info">Beat · downbeat · beat/bar/phrase confidence</p>' +
      select('diagWindow', 'History window', ui.diagWindow, [
        [8, '8 seconds'],
        [16, '16 seconds'],
      ]) +
      actions(
        btn('freeze', ui.frozen ? 'Live view' : 'Freeze'),
        btn('export', 'Export CSV'),
        btn('clear-diag', 'Clear')
      );
  if (view === 'Song')
    body =
      check('songAssist', 'Song assist', d.songAssist) +
      '<p class="info">Look up a tempo using a Spotify track link or artist and title. ReccoBeats receives the track details you enter.</p>' +
      (d.songAssist
        ? '<label for="song-title">Title or Spotify link</label><input id="song-title" type="text" maxlength="240"><label for="song-artist">Artist</label><input id="song-artist" type="text" maxlength="160">' +
          actions(btn('lookup', 'Look up track', 'class="action-main"')) +
          group(
            'Audio identification',
            '<p class="info">Enabling identification sends 8–10 seconds of live audio to AudD periodically using your account. The token lasts for this tab. Up to 40 requests per hour.</p>' +
              (song.identify
                ? actions(
                    btn('identify', 'Identify now', audio.phase === 'live' ? '' : 'disabled'),
                    btn('remove-key', 'Disable and forget token')
                  )
                : '<label for="song-key">AudD token</label><input id="song-key" type="password" autocomplete="off">' +
                  actions(btn('set-key', 'Enable identification')))
          ) +
          '<div id="song-status" class="metric" role="status"></div>'
        : '');
  if (view === 'Crowd')
    body =
      '<p class="info">Join the controller’s hotspot, then paste its invitation. Your browser may ask for local network access.</p>' +
      (crowd.connected
        ? actions(btn('leave', 'Leave show'))
        : '<label for="invite">Show invitation</label><input id="invite" type="text" autocomplete="off" spellcheck="false">' +
          actions(btn('join', 'Join show', 'class="action-main"'))) +
      '<p id="crowd-status" role="status" class="metric"></p>' +
      (crowd.connected
        ? check('flashes', 'Flashes and inversion', crowd.flashes) +
          slider('crowdBrightness', 'Visual brightness', ui.crowdBrightness, 0.1, 1, 0.05, c) +
          check('blackout', 'Local blackout', crowd.blackout) +
          (crowd.source
            ? '<p class="info">Connect a microphone, shared tab or local file in Audio to send its timing.</p>'
            : '')
        : '');
  return (
    title('App settings') +
    segments('settings', view, ['Display', 'Effects', 'Diagnostics', 'Song', 'Crowd']) +
    '<div class="group">' +
    body +
    '</div>'
  );
}
export function panelContent(model) {
  return { Tune: tune, Audio: audioPanel, Looks: looks, Settings: settings }[model.ui.tab](model);
}
