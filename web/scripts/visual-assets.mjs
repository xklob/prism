import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const native = fileURLToPath(new URL('../../app/src/main/', import.meta.url));
const kotlin = join(native, 'java/com/caleb/prism');

function section(text, start, end) {
  const begin = text.indexOf(start);
  const finish = text.indexOf(end, begin + start.length);
  if (begin === -1 || finish === -1) throw new Error(`Missing native definition: ${start}`);
  return text.slice(begin + start.length, finish);
}

function valueOf(literal) {
  if (literal === 'true' || literal === 'false') return literal === 'true';
  if (/^-?(?:\d*\.)?\d+f?$/.test(literal)) return Number(literal.replace(/f$/, ''));
  throw new Error(`Unsupported native visual setting: ${literal}`);
}

// Read only the declarative enum entries, constructor defaults and named default
// overrides. Reject new expressions so a native change can't silently alter a web look.
function constructorDefaults(text, replacements = {}) {
  return Object.fromEntries(
    text
      .trim()
      .split(/,\s*/)
      .map((field) => {
        const match = /^val (\w+): \w+ = (.+)$/.exec(field.trim());
        if (!match) throw new Error(`Unsupported native field: ${field}`);
        const [, key, literal] = match;
        return [key, Object.hasOwn(replacements, literal) ? replacements[literal] : valueOf(literal)];
      })
  );
}

function makeCatalog(sceneFile, qualityFile, helpFile) {
  const entries = section(sceneFile, 'val accent: Color) {', ';')
    .trim()
    .split(/,\s*\n/);
  const scenes = entries.map((entry) => {
    const match = /^(\w+)\((\d+), "([^"]+)", "([^"]+)", Color\(0xFF([\dA-F]{6})\)\)$/.exec(entry.trim());
    if (!match) throw new Error(`Unsupported scene: ${entry}`);
    const [, key, id, title, subtitle, color] = match;
    return { id: Number(id), key, title, subtitle, accent: '#' + color };
  });
  const paletteDefs = section(sceneFile, 'val colors: List<Color>) {', '\n}');
  const palettes = paletteDefs
    .trim()
    .split(/,\s*\n/)
    .map((entry, id) => {
      const match = /^(\w+)\("([^"]+)", listOf\((.+)\)\)$/.exec(entry.trim());
      if (!match) throw new Error(`Unsupported palette: ${entry}`);
      const colors = match[3].split(', ').map((color) => {
        const rgb = /^Color\(0xFF([\dA-F]{6})\)$/.exec(color);
        if (!rgb) throw new Error(`Unsupported palette color: ${color}`);
        return '#' + rgb[1];
      });
      return { id, key: match[1], title: match[2], colors };
    });

  const effects = constructorDefaults(section(qualityFile, 'data class PostEffects(', '\n)'));
  const literals = { 'PostEffects()': effects, 'AudioSource.MICROPHONE': 'microphone' };
  for (const { key, id } of scenes) literals['Scene.' + key] = id;
  for (const { key, id } of palettes) literals['Palette.' + key] = id;
  const defaults = constructorDefaults(section(sceneFile, 'data class VisualSettings(', '\n)'), literals);
  const overrides = section(sceneFile, 'fun defaults(scene: Scene) = when (scene) {', '\n        }');
  for (const scene of scenes) {
    const args = new RegExp(`Scene\\.${scene.key} -> VisualSettings\\(([^)]*)\\)`).exec(overrides);
    if (!args) throw new Error(`Missing defaults for ${scene.key}`);
    scene.defaults = { ...defaults, scene: scene.id };
    for (const pair of args[1].split(/,\s*/)) {
      const [key, literal] = pair.trim().split(/\s*=\s*/);
      if (key === 'scene' && literal === 'scene') continue;
      if (!Object.hasOwn(defaults, key)) throw new Error(`Unknown visual default: ${key}`);
      scene.defaults[key] = valueOf(literal);
    }
  }
  const ranges = {};
  for (const [, key, lo, hi] of sceneFile.matchAll(/f\("(\w+)", d\.\w+, ([-.\df]+), ([-.\df]+)\)/g)) {
    ranges[key] = [valueOf(lo), valueOf(hi)];
  }
  for (const [, key, lo, hi] of sceneFile.matchAll(
    /getInt\(prefix \+ "(\w+)", d\.\w+\)\.coerceIn\((\d+), (\d+)\)/g
  )) {
    ranges[key] = [Number(lo), Number(hi)];
  }
  const help = {};
  for (const [, keys, description] of helpFile.matchAll(/^\s*((?:"[^"\n]+"(?:, )?)+) -> "([^"\n]+)"$/gm)) {
    for (const [, key] of keys.matchAll(/"([^"]+)"/g)) help[key] = description;
  }
  // These controls have platform-specific behavior or permission models.
  delete help['Spotify compatibility'];
  delete help['Playback info access'];
  Object.assign(help, {
    Brightness: 'Scales the rendered colors before contrast. High values can clip bright details to white.',
    'Input source':
      'Choose a local track, use your microphone, or share a tab or screen with audio through the browser picker.',
    'Adaptive quality':
      'Lowers rendering resolution when frames run late. A sustained recovery gradually restores sharpness.',
    'Target frame rate':
      'Choose a 30 or 60 fps target for the visualizer. Adaptive quality uses this target to balance sharpness and smoothness.',
    'AudD API key':
      'Holds your AudD credential in memory for this tab. Closing or reloading the app clears it.',
  });
  return { scenes, palettes, defaults, effects, ranges, help };
}

export async function buildVisualAssets(dist) {
  if (!isAbsolute(dist)) throw new TypeError('Pass the absolute web output directory');
  const assets = join(dist, 'assets');
  const [scene, quality, help] = await Promise.all(
    ['Scene.kt', 'RenderQuality.kt', 'SettingHelp.kt'].map((name) => readFile(join(kotlin, name), 'utf8'))
  );
  const catalog = makeCatalog(scene, quality, help);
  await mkdir(assets, { recursive: true });
  await Promise.all([
    cp(join(native, 'assets/shaders'), join(assets, 'shaders'), { recursive: true }),
    writeFile(join(assets, 'visuals.json'), JSON.stringify(catalog) + '\n'),
  ]);
  return catalog;
}
