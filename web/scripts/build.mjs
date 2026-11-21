import { createHash } from 'node:crypto';
import { cp, mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildVisualAssets } from './visual-assets.mjs';
import { buildCrowdAssets } from './crowd-assets.mjs';
import { buildAudioAssets } from './audio-assets.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const out = path.join(root, 'dist');
await rm(out, { recursive: true, force: true });
await mkdir(path.join(out, 'assets'), { recursive: true });
await Promise.all([
  ...['index.html', 'style.css', 'manifest.webmanifest', 'src'].map((name) =>
    cp(path.join(root, name), path.join(out, name), { recursive: true })
  ),
  cp(path.join(root, 'assets'), path.join(out, 'assets'), { recursive: true }),
  cp(
    path.join(
      root,
      'node_modules/@fontsource-variable/space-grotesk/files/space-grotesk-latin-wght-normal.woff2'
    ),
    path.join(out, 'assets/space-grotesk.woff2')
  ),
  cp(
    path.join(root, 'node_modules/@fontsource-variable/space-grotesk/LICENSE'),
    path.join(out, 'assets/Space-Grotesk-OFL.txt')
  ),
  buildVisualAssets(out),
  buildCrowdAssets(out),
  buildAudioAssets(out),
]);
const files = (await readdir(out, { recursive: true, withFileTypes: true }))
  .filter((entry) => entry.isFile())
  .map((entry) => path.relative(out, path.join(entry.parentPath, entry.name)).split(path.sep).join('/'))
  .sort();
const workerTemplate = await readFile(path.join(root, 'scripts/offline-worker.js'), 'utf8');
const digest = createHash('sha256').update(workerTemplate);
for (const name of files) digest.update(name).update(await readFile(path.join(out, name)));
const serviceWorker = workerTemplate
  .replace('__BUILD_HASH__', digest.digest('hex').slice(0, 16))
  .replace('/* FILES */ []', JSON.stringify(files));
await writeFile(path.join(out, 'sw.js'), serviceWorker);
await writeFile(path.join(out, 'prism-files.json'), JSON.stringify([...files, 'sw.js']));
// Local launchers ship with the ZIP. The participant server only exposes app resources.
await cp(path.join(root, 'scripts/serve.mjs'), path.join(out, 'serve.mjs'));
await cp(path.join(root, 'START.txt'), path.join(out, 'START.txt'));
console.log(`Built prism and ${files.length} self-hosted resources in web/dist.`);
