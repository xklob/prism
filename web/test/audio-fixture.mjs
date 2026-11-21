import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const pcmFixture = new URL('../../app/src/androidTest/assets/rhythm/drums128.pcm', import.meta.url);
const temp = new URL('../../review/web-input/', import.meta.url);
export const microphonePath = fileURLToPath(new URL('synthetic-drums.wav', temp));
export async function musicFile() {
  const pcm = await readFile(pcmFixture);
  const header = Buffer.alloc(44);
  header.write('RIFF');
  header.writeUInt32LE(pcm.length + 36, 4);
  header.write('WAVEfmt ', 8);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(48000, 24);
  header.writeUInt32LE(96000, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36);
  header.writeUInt32LE(pcm.length, 40);
  return { name: 'Synthetic 128 BPM.wav', mimeType: 'audio/wav', buffer: Buffer.concat([header, pcm]) };
}
export default async function prepareMicrophone() {
  await mkdir(temp, { recursive: true });
  await writeFile(microphonePath, (await musicFile()).buffer);
}
