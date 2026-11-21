import { constants } from 'node:fs';
import { lstat, open, readFile, realpath } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';

const types: Record<string, string> = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.png': 'image/png', '.webp': 'image/webp',
  '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.vert': 'text/plain', '.frag': 'text/plain', '.txt': 'text/plain',
  '.wasm': 'application/wasm', '.onnx': 'application/octet-stream', '.f32': 'application/octet-stream', '.map': 'application/json'
};
const rootFiles = ['index.html', 'style.css', 'sw.js', 'manifest.webmanifest'];
const appAsset = /^(?:src|assets|dsp)\/(?:[a-zA-Z0-9_-]+\/)*[a-zA-Z0-9_-]+(?:\.[a-zA-Z0-9_-]+)+$/;

// Only the generated release file list is public. Runtime, controller source,
// unlisted additions and symlinks must remain outside this listener.
export async function loadBrowserApp(directory: string) {
  const names = new Set<string>();
  let root: string;
  try {
    root = await realpath(directory);
    const manifest = path.join(root, 'prism-files.json');
    const stat = await lstat(manifest);
    if (!stat.isFile() || stat.size > 128 * 1024) throw new Error('Invalid browser bundle file list.');
    const entries: unknown = JSON.parse(await readFile(manifest, 'utf8'));
    if (!Array.isArray(entries) || entries.length > 2048) throw new Error('Invalid browser bundle file list.');
    for (const name of entries) if (typeof name === 'string' && name.length <= 256 && (rootFiles.includes(name) || appAsset.test(name))
        && Object.hasOwn(types, path.extname(name))) names.add(name);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
  }
  return async (req: IncomingMessage, res: ServerResponse) => {
    const raw = req.url?.split('?')[0];
    const name = raw === '/' ? 'index.html' : raw?.startsWith('/') ? raw.slice(1) : '';
    if (!names.has(name)) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end(raw === '/' ? 'Web app unavailable. Extract the complete prism controller ZIP or configure PRISM_WEB_URL.' : undefined);
      return;
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405).end(); return; }
    const filename = path.join(root, name);
    let handle;
    try {
      if (await realpath(filename) !== filename) throw new Error('Not a bundled file');
      const flags = process.platform === 'win32' ? 'r' : constants.O_RDONLY | constants.O_NOFOLLOW;
      handle = await open(filename, flags);
      const stat = await handle.stat();
      if (!stat.isFile()) throw new Error('Not a bundled file');
      res.writeHead(200, { 'Content-Type': types[path.extname(name)], 'Cache-Control': 'no-cache',
        'Content-Length': stat.size, 'Referrer-Policy': 'no-referrer',
        'Content-Security-Policy': "object-src 'none'; base-uri 'self'; frame-ancestors 'none'" });
      if (req.method === 'HEAD') res.end();
      else await pipeline(handle.createReadStream(), res);
    } catch {
      if (!res.headersSent) res.writeHead(404);
      res.end();
    } finally {
      await handle?.close();
    }
  };
}
