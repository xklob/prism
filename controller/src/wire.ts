import { generateKeyPairSync, sign, verify, createPublicKey } from 'node:crypto';

export function identity() {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const publicPin = publicKey.export({ type: 'spki', format: 'der' }).toString('base64url');
  function envelope(message: object, dsaEncoding: 'der' | 'ieee-p1363') {
    const payload = Buffer.from(JSON.stringify(message));
    return {
      payload: payload.toString('base64url'),
      signature: sign('sha256', payload, { key: privateKey, dsaEncoding }).toString('base64url')
    };
  }
  return {
    publicPin,
    encode(message: object): Buffer {
      return Buffer.from(JSON.stringify(envelope(message, 'der')));
    },
    // Web Crypto accepts the fixed-width r || s representation.
    webEnvelope: (message: object) => envelope(message, 'ieee-p1363')
  };
}
export function decode(packet: Buffer, pin: string): Record<string, unknown> {
  if (packet.length > 8192) throw new Error('Oversize packet');
  const envelope = JSON.parse(packet.toString());
  const payload = Buffer.from(envelope.payload, 'base64url');
  const key = createPublicKey({ key: Buffer.from(pin, 'base64url'), type: 'spki', format: 'der' });
  if (!verify('sha256', payload, key, Buffer.from(envelope.signature, 'base64url'))) throw new Error('Bad signature');
  return JSON.parse(payload.toString());
}
