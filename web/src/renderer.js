import { BeatMotion, RenderQuality } from './render-motion.js';
import { Pipeline, shaderNames } from './render-pipeline.js';

const clamp = (value, lo, hi) => Math.min(hi, Math.max(lo, value));
const ease = (t) => t * t * (3 - 2 * t);
const axes = ['bass', 'mid', 'high'];
const noMotion = { beat: 0, bar: 0, flow: 0, texture: 0, flash: 0, inversion: 0 };

async function readAsset(base, path, json = false) {
  const response = await fetch(new URL(path, base));
  if (!response.ok) throw new Error(`Could not load ${path} (HTTP ${response.status}).`);
  return json ? response.json() : response.text();
}

function makeLayer(settings, previous) {
  return {
    settings,
    time: previous?.time ?? 0,
    rotation: previous?.rotation ?? 0,
    colorPhase: previous?.colorPhase ?? 0,
    morphTime: previous?.morphTime ?? 0,
    bands: new Float32Array(previous?.bands ?? 3),
    beatMotion: new BeatMotion(),
  };
}

function advance(layer, dt, now, audio, crowd) {
  const s = layer.settings;
  if (crowd) {
    const seconds = (crowd.timeMs ?? crowd.time ?? 0) / 1000;
    layer.time = seconds * s.speed;
    layer.rotation = seconds * s.rotation * 0.3;
    layer.colorPhase = seconds * s.colorSpeed;
    layer.morphTime = seconds * s.morph;
  } else if (!s.paused) {
    layer.time += dt * s.speed;
    layer.rotation += dt * s.rotation * 0.3;
    layer.colorPhase += dt * s.colorSpeed;
    layer.morphTime += dt * s.morph;
  }
  const levels = audio?.levels ?? audio;
  const fresh = !audio?.rhythm || now - audio.rhythm.timestamp < 0.5;
  if (!levels || !s.audioEnabled || crowd || !fresh) layer.bands.fill(0);
  else if (!s.paused) {
    for (let i = 0; i < axes.length; i++) {
      const target = clamp((levels[axes[i]] || 0) * s.audioAmount, 0, 2);
      const tau = target > layer.bands[i] ? 0.045 : 0.22;
      layer.bands[i] += (target - layer.bands[i]) * (1 - Math.exp(-dt / tau));
    }
  }
  return crowd ? { ...noMotion, ...crowd.motion } : layer.beatMotion.at(now, s, audio);
}

function blendEffects(from, to, weight) {
  if (weight <= 0) return from;
  if (weight >= 1) return to;
  const mix = (a, b) => a + (b - a) * weight;
  const oldStrength = from.enabled ? from.strength : 0;
  const newStrength = to.enabled ? to.strength : 0;
  return {
    enabled: from.enabled || to.enabled,
    strength: 1,
    bloom: mix(from.bloom * oldStrength, to.bloom * newStrength),
    blur: mix(from.blur * oldStrength, to.blur * newStrength),
    bloomRadius: mix(from.bloomRadius, to.bloomRadius),
    blurRadius: mix(from.blurRadius, to.blurRadius),
    threshold: mix(from.threshold, to.threshold),
    quality: Math.max(from.quality, to.quality),
  };
}

/** Creates one renderer. The owner calls draw from requestAnimationFrame. */
export async function createRenderer(
  canvas,
  { assetBase = new URL('./assets/', document.baseURI), onError, onRestore } = {}
) {
  const gl = canvas.getContext('webgl2', {
    alpha: false,
    antialias: false,
    depth: false,
    stencil: false,
    powerPreference: 'high-performance',
  });
  if (!gl)
    throw new Error(
      'WebGL 2 is unavailable. Enable hardware acceleration or open prism in a WebGL 2 browser.'
    );
  const base = new URL(String(assetBase).replace(/\/?$/, '/'), document.baseURI);
  const [catalog, loadedShaders] = await Promise.all([
    readAsset(base, 'visuals.json', true),
    Promise.all(
      Object.entries(shaderNames).map(async ([key, path]) => [key, await readAsset(base, 'shaders/' + path)])
    ),
  ]);
  const shaders = Object.fromEntries(loadedShaders);
  let pipeline = new Pipeline(gl, shaders);
  let quality = new RenderQuality();
  const scenes = new Map(catalog.scenes.map((scene) => [scene.id, scene]));
  const defaultScene = catalog.scenes[0];
  const pan = [0, 0],
    panTarget = [0, 0];
  const stats = { fps: 0, width: 0, height: 0, scale: 1, targetFps: 60 };
  let layer = null,
    crowdLayer = null,
    fade = null,
    requested = null;
  let displayedEffects = catalog.effects;
  let recordingSize = null,
    disposed = false,
    failed = false;
  let previousFrame = null,
    nextFrame = 0,
    previousRate = 0;
  let fpsStart = null,
    fpsFrames = 0;

  function settingsFor(value) {
    const scene = scenes.get(Number(value?.scene)) ?? defaultScene;
    return {
      ...scene.defaults,
      ...value,
      scene: scene.id,
      effects: { ...catalog.effects, ...value?.effects },
    };
  }

  function startTransition(settings, seconds) {
    if (!layer) {
      layer = makeLayer(settings);
      return;
    }
    const duration = clamp(seconds, 0, 8);
    const frozen = fade && duration > 0 ? pipeline.freeze() : null;
    fade = duration > 0 ? { layer, frozen, duration, progress: 0, effects: displayedEffects } : null;
    layer = makeLayer(settings, layer);
  }

  function dimensions(s, scale) {
    const cssWidth = canvas.clientWidth,
      cssHeight = canvas.clientHeight;
    const ratio = Math.min(window.devicePixelRatio || 1, pipeline.maxEdge / Math.max(1, cssWidth, cssHeight));
    const w = recordingSize?.width ?? Math.max(1, Math.round(cssWidth * ratio));
    const h = recordingSize?.height ?? Math.max(1, Math.round(cssHeight * ratio));
    if (canvas.width !== w) canvas.width = w;
    if (canvas.height !== h) canvas.height = h;
    // The native volumetric pattern has a 1440 px cap. Battery saver caps every pattern.
    const cap = s.batterySaver ? 1080 : scenes.get(s.scene)?.key === 'KALEIDOSCOPE' ? 1440 : Infinity;
    const pixelScale = Math.min(1, cap / Math.max(w, h)) * scale;
    return [Math.max(1, Math.floor(w * pixelScale)), Math.max(1, Math.floor(h * pixelScale))];
  }

  function paint(nowMs, value, audio, crowd) {
    const local = value ?? layer?.settings ?? defaultScene.defaults;
    // Android builds controller looks from the shared constructor defaults.
    const renderSettings =
      crowd?.settings ??
      (crowd?.cue && { ...catalog.defaults, ...crowd.cue }) ??
      requested?.settings ??
      local;
    const targetFps = local.batterySaver || Number(local.targetFps) === 30 ? 30 : 60;
    const rate = !crowd && local.paused && !recordingSize ? 10 : targetFps;
    if (rate !== previousRate) nextFrame = nowMs;
    previousRate = rate;
    if (nowMs + 0.7 < nextFrame) return false;
    const interval = 1000 / rate;
    nextFrame += Math.max(1, Math.floor((nowMs + 0.7 - nextFrame) / interval) + 1) * interval;

    const frameSeconds = previousFrame === null ? 0 : (nowMs - previousFrame) / 1000;
    const dt = clamp(frameSeconds, 0, 0.25);
    previousFrame = nowMs;
    const s = settingsFor(renderSettings);
    const scale = quality.sample(frameSeconds, targetFps, local.adaptiveQuality ?? true, s.paused && !crowd);
    const [width, height] = dimensions({ scene: s.scene, batterySaver: local.batterySaver }, scale);
    if (crowd) {
      fade = requested = null;
      crowdLayer ??= makeLayer(s);
      pan.fill(0);
    } else {
      if (requested || !layer || layer.settings.scene !== s.scene)
        startTransition(s, requested?.seconds ?? 0.6);
      requested = null;
      if (!s.paused) {
        const k = 1 - Math.exp(-dt / 0.2);
        pan[0] += (panTarget[0] - pan[0]) * k;
        pan[1] += (panTarget[1] - pan[1]) * k;
      }
    }
    const current = crowd ? crowdLayer : layer;
    current.settings = s;
    const sceneTarget = pipeline.target('scene', width, height);
    const now = nowMs / 1000;
    const motion = advance(current, dt, now, audio, crowd);
    if (crowd && (crowd.dark || crowd.diagnostic)) pipeline.clear(crowd.dark ? 0 : motion.flash);
    else
      pipeline.scene(
        sceneTarget,
        current,
        motion,
        pan,
        crowd?.previousScene ?? crowd?.cue?.previousScene,
        crowd?.transition
      );
    let source = sceneTarget;
    displayedEffects = crowd ? catalog.effects : s.effects;
    if (fade) {
      let from = fade.frozen;
      if (!from) {
        from = pipeline.target('outgoing', width, height);
        // Shared transport and input state apply to both moving layers during the fade.
        Object.assign(fade.layer.settings, {
          paused: s.paused,
          audioEnabled: s.audioEnabled,
          syncOffsetMs: s.syncOffsetMs,
        });
        const outgoingMotion = advance(fade.layer, dt, now, audio);
        pipeline.scene(from, fade.layer, outgoingMotion, pan);
      }
      const weight = ease(fade.progress);
      source = pipeline.mix(from, sceneTarget, weight);
      displayedEffects = blendEffects(fade.effects, s.effects, weight);
      fade.progress = Math.min(1, fade.progress + dt / fade.duration);
      if (weight === 1) fade = null;
    }
    pipeline.present(source, displayedEffects, canvas.width, canvas.height);
    Object.assign(stats, { scale, width, height, targetFps });
    if (fpsStart === null || frameSeconds > 2) {
      fpsStart = nowMs;
      fpsFrames = 0;
    } else {
      fpsFrames++;
      if (nowMs - fpsStart >= 1000) {
        stats.fps = (fpsFrames * 1000) / (nowMs - fpsStart);
        fpsFrames = 0;
        fpsStart = nowMs;
      }
    }
    return true;
  }

  function lost(event) {
    event.preventDefault();
    pipeline = null;
    fade = null;
    onError?.('The graphics context was lost. Waiting for the browser to restore it.');
  }
  function restored() {
    try {
      pipeline = new Pipeline(gl, shaders);
      quality = new RenderQuality();
      previousFrame = fpsStart = null;
      failed = false;
      onRestore?.();
    } catch (error) {
      onError?.(error.message);
    }
  }
  canvas.addEventListener('webglcontextlost', lost);
  canvas.addEventListener('webglcontextrestored', restored);

  return {
    catalog,
    stats,
    draw(nowMs, settings, audio = null, crowd = null) {
      if (disposed || !pipeline || failed || gl.isContextLost()) return false;
      try {
        return paint(nowMs, settings, audio, crowd);
      } catch (error) {
        if (gl.isContextLost()) return false;
        failed = true;
        if (!onError) throw error;
        onError(error.message);
        return false;
      }
    },
    transitionTo(settings, seconds = 1.5) {
      requested = { settings: settingsFor(settings), seconds };
    },
    setPan(x, y) {
      panTarget[0] = clamp(x, -1, 1);
      panTarget[1] = clamp(y, -1, 1);
    },
    setRecording(active) {
      recordingSize = active ? { width: canvas.width, height: canvas.height } : null;
    },
    dispose() {
      disposed = true;
      canvas.removeEventListener('webglcontextlost', lost);
      canvas.removeEventListener('webglcontextrestored', restored);
      pipeline?.dispose();
    },
  };
}
