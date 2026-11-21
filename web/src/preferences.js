const key = 'prism.web.v1';
const maxLooks = 32;
const visualKeys = [
  'palette',
  'audioAmount',
  'speed',
  'intensity',
  'complexity',
  'symmetry',
  'distortion',
  'zoom',
  'lineWidth',
  'hue',
  'saturation',
  'contrast',
  'colorSpeed',
  'rotation',
  'morph',
  'beatImpact',
  'barImpact',
  'flowImpact',
  'pulseLength',
  'textureAmount',
  'downbeatFlash',
  'downbeatInvert',
  'invertFadeMs',
  'kaleidoscopeShape',
  'kaleidoscopeSpace',
];
const select = (object, fields) =>
  Object.fromEntries(
    fields.filter((field) => Object.hasOwn(object, field)).map((field) => [field, object[field]])
  );

export class Preferences {
  constructor(catalog, storage = localStorage) {
    this.catalog = catalog;
    this.storage = storage;
    let saved;
    try {
      saved = JSON.parse(storage.getItem(key) || '{}');
    } catch {
      saved = {};
    }
    this.data = {
      scene: catalog.scenes[0].id,
      patterns: {},
      quick: {},
      looks: [],
      seconds: 1.5,
      theme: 'system',
      source: 'microphone',
      sensitivity: 1.4,
      syncOffsetMs: 0,
      beatsPerBar: 0,
      phraseBars: 16,
      fileMeter: 0,
      filePhraseBars: 16,
      waveform: null,
      tabletMonitor: true,
      targetFps: 60,
      batterySaver: false,
      adaptiveQuality: true,
      effects: {},
      songAssist: false,
      ...(saved && typeof saved === 'object' ? saved : {}),
    };
    const d = this.data;
    if (!catalog.scenes.some((s) => s.id === d.scene)) d.scene = catalog.scenes[0].id;
    if (!['system', 'light', 'dark'].includes(d.theme)) d.theme = 'system';
    for (const k of ['patterns', 'effects', 'quick']) if (!d[k] || typeof d[k] !== 'object') d[k] = {};
    d.looks = Array.isArray(d.looks)
      ? d.looks
          .filter(
            (l) => typeof l?.name === 'string' && l.visual && catalog.scenes.some((s) => s.id === l.scene)
          )
          .slice(0, maxLooks)
      : [];
  }
  save() {
    try {
      this.storage.setItem(key, JSON.stringify(this.data));
    } catch {
      /* Storage may be disabled. Settings still work for this tab. */
    }
  }
  get scene() {
    return this.catalog.scenes.find((s) => s.id === this.data.scene);
  }
  get settings() {
    const d = this.data;
    return {
      ...this.scene.defaults,
      ...d.patterns[d.scene],
      ...select(d, [
        'source',
        'sensitivity',
        'syncOffsetMs',
        'beatsPerBar',
        'tabletMonitor',
        'targetFps',
        'batterySaver',
        'adaptiveQuality',
      ]),
      scene: d.scene,
      effects: { ...this.catalog.effects, ...d.effects },
    };
  }
  setScene(id) {
    if (this.catalog.scenes.some((s) => s.id === id)) {
      this.data.scene = id;
      this.save();
    }
  }
  setPattern(name, value) {
    this.data.patterns[this.data.scene] = { ...this.data.patterns[this.data.scene], [name]: value };
    this.save();
  }
  set(name, value) {
    this.data[name] = value;
    this.save();
  }
  setEffect(name, value) {
    this.set('effects', { ...this.data.effects, [name]: value });
  }
  snapshot() {
    return {
      scene: this.data.scene,
      visual: select(this.settings, visualKeys),
      effects: { ...this.settings.effects },
    };
  }
  apply(look, withEffects = true) {
    this.data.scene = look.scene;
    this.data.patterns[look.scene] = {
      ...this.data.patterns[look.scene],
      ...select(look.visual, visualKeys),
    };
    if (withEffects) this.data.effects = { ...look.effects };
    this.save();
  }
  reset() {
    this.apply({ scene: this.data.scene, visual: this.scene.defaults }, false);
  }
  shuffle() {
    const rand = (min, max) => min + Math.random() * (max - min);
    const visual = {
      ...this.settings,
      palette: this.catalog.palettes[Math.floor(rand(0, this.catalog.palettes.length))].id,
      complexity: rand(0.6, 1),
      symmetry: Math.floor(rand(4, 17)),
      distortion: rand(0, 1.3),
      zoom: rand(0.75, 1.65),
      hue: Math.random(),
      rotation: rand(-0.35, 0.35),
      morph: rand(0.15, 0.85),
    };
    if (this.scene.id === 7)
      Object.assign(visual, {
        complexity: rand(0.75, 1),
        rotation: 0,
        morph: 0,
        speed: rand(0.25, 0.75),
        zoom: rand(0.8, 1.3),
      });
    if (this.scene.id === 9)
      Object.assign(visual, {
        kaleidoscopeShape: Math.floor(rand(0, 3)),
        kaleidoscopeSpace: rand(0.6, 1.5),
        lineWidth: rand(0.8, 1.5),
        rotation: rand(-0.075, 0.075),
        morph: rand(0.1, 0.5),
      });
    this.apply({ scene: this.scene.id, visual }, false);
  }
  saveQuick() {
    this.data.quick[this.data.scene] = this.snapshot();
    this.save();
  }
  addLook(name) {
    const title = name.trim().slice(0, 48);
    if (!title) throw new Error('Enter a look name.');
    if (this.data.looks.length >= maxLooks)
      throw new Error('The library holds 32 looks. Delete a look to save another.');
    const id = globalThis.crypto?.randomUUID?.() || String(Date.now()) + Math.random().toString(36).slice(2);
    this.data.looks.push({ ...this.snapshot(), id, name: title, favorite: false });
    this.save();
  }
  updateLook(id, update) {
    const look = this.data.looks.find((l) => l.id === id);
    if (look) Object.assign(look, update);
    this.save();
  }
  deleteLook(id) {
    this.data.looks = this.data.looks.filter((l) => l.id !== id);
    this.save();
  }
}
