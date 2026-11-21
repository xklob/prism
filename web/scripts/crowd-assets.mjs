import { cp, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const web = fileURLToPath(new URL('../', import.meta.url));
export async function buildCrowdAssets(out) {
  const directory = path.join(out, 'assets');
  await mkdir(directory, { recursive: true });
  await Promise.all([
    build({
      entryPoints: [path.join(web, 'scripts/p256-verifier.mjs')],
      outfile: path.join(directory, 'crowd-p256.js'),
      bundle: true,
      platform: 'browser',
      format: 'esm',
      target: 'es2022',
      minify: true,
      legalComments: 'eof',
    }),
    cp(
      path.join(web, 'node_modules/@noble/curves/LICENSE'),
      path.join(directory, 'Noble-Curves-LICENSE.txt')
    ),
    cp(
      path.join(web, 'node_modules/@noble/hashes/LICENSE'),
      path.join(directory, 'Noble-Hashes-LICENSE.txt')
    ),
  ]);
}
