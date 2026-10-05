// Run natively on the emulator's host. The Android test reads the generated
// invitation; no controller admin credentials are passed into the Android app.
import { writeFile, mkdir } from 'node:fs/promises';
import { startController, now } from '../src/server.ts';
const server = await startController({ host: process.env.PRISM_TEST_HOST ?? '10.0.2.2' });
server.show.command({ action: 'settings', flashEvery: 1 }, now());
server.show.command({ action: 'start' }, now());
await mkdir('runtime', { recursive: true });
await writeFile('runtime/android-test.json', JSON.stringify({
  invite: Buffer.from(server.joinUrl(false)).toString('base64url'),
  sourceInvite: Buffer.from(server.joinUrl(true)).toString('base64url'),
  dashboard: server.dashboardUrl
}), { mode: 0o600 });
console.log('Android crowd test harness ready.');
process.on('SIGINT', () => void server.close().then(() => process.exit(0)));
process.on('SIGTERM', () => void server.close().then(() => process.exit(0)));
