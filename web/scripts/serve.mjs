import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = existsSync(path.join(here, 'index.html')) ? here : path.resolve(here, '../dist');
const port = Number(process.env.PORT || 4173);
const mime = {
  '.html': 'text/html',
  '.mjs': 'text/javascript',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.wasm': 'application/wasm',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain',
};
if (!existsSync(path.join(root, 'index.html'))) {
  console.error('Build prism first with npm run build, or extract the complete web ZIP.');
  process.exit(1);
}
const server = createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    const file = path.resolve(root, '.' + (pathname.endsWith('/') ? pathname + 'index.html' : pathname));
    if (!file.startsWith(root + path.sep) || !(await stat(file)).isFile()) throw new Error();
    res.writeHead(200, {
      'Content-Type': mime[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
      'X-Content-Type-Options': 'nosniff',
    });
    res.end(await readFile(file));
  } catch {
    res.writeHead(404).end();
  }
});
server.on('error', (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
server.listen(port, '127.0.0.1', () => {
  const url = 'http://127.0.0.1:' + server.address().port + '/';
  console.log('prism at ' + url + ' (Ctrl+C to stop)');
  if (process.argv.includes('--open')) {
    const open = { win32: 'explorer.exe', darwin: 'open', linux: 'xdg-open' }[process.platform];
    if (open) {
      const child = spawn(open, [url], { detached: true, stdio: 'ignore' });
      child.on('error', () => console.log('Open ' + url + ' in your browser.'));
      child.unref();
    }
  }
});
