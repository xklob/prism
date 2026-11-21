import { execFile } from 'node:child_process';
import { cp, mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const run = promisify(execFile);
const root = fileURLToPath(new URL('../../', import.meta.url));
const project = join(root, 'web/dsp');

export async function buildAudioAssets(dist) {
  const destination = join(dist instanceof URL ? fileURLToPath(dist) : resolve(dist), 'dsp');
  const windows = process.platform === 'win32';
  const command = windows ? 'cmd.exe' : join(root, 'gradlew');
  const args = windows ? ['/d', '/c', '..\\..\\gradlew.bat'] : [];
  try {
    await run(
      command,
      [...args, '--max-workers=2', 'compileProductionExecutableKotlinJs', '--console=plain'],
      { cwd: project, maxBuffer: 2 * 1024 * 1024 }
    );
  } catch (error) {
    throw new Error(`Audio DSP compilation failed.\n${error.stdout || ''}${error.stderr || error.message}`);
  }
  await mkdir(destination, { recursive: true });
  const compiled = join(project, 'build/compileSync/js/main/productionExecutable/kotlin');
  const files = (await readdir(compiled)).filter((name) => name.endsWith('.mjs'));
  if (!files.includes('prism-dsp.mjs')) throw new Error('The browser DSP module is missing.');
  const models = join(root, 'app/src/main/assets/rhythm');
  const runtime = join(root, 'web/node_modules/onnxruntime-web/dist');
  const runtimeFiles = [
    'ort.wasm.min.mjs',
    'ort.wasm.min.mjs.map',
    'ort-wasm-simd-threaded.mjs',
    'ort-wasm-simd-threaded.wasm',
  ];
  const modelFiles = [
    'beatnet.onnx',
    'beatnet-general.onnx',
    'filters.f32',
    'manifest.json',
    'general-manifest.json',
    'BEATNET-LICENSE.txt',
    'NOTICE.txt',
    'ONNX-RUNTIME-LICENSE.txt',
    'ONNX-THIRD-PARTY-NOTICES.txt',
  ];
  await Promise.all([
    ...files.map(async (name) => {
      const code = await readFile(join(compiled, name), 'utf8');
      // The Kotlin source maps contain checkout paths and do not ship with the static app.
      await writeFile(join(destination, name), code.replace(/^\/\/# sourceMappingURL=.*$/gm, ''));
    }),
    ...modelFiles.map((name) => cp(join(models, name), join(destination, name))),
    ...runtimeFiles.map((name) => cp(join(runtime, name), join(destination, name))),
    cp(join(project, 'licenses'), destination, { recursive: true }),
  ]);
}
