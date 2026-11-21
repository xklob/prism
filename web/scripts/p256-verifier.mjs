import { p256 } from '@noble/curves/nist.js';

// The controller exports an uncompressed SEC1 point in an SPKI with the
// id-ecPublicKey and prime256v1 OIDs. Reject other algorithms or encodings.
const prefix = Uint8Array.of(
  0x30,
  0x59,
  0x30,
  0x13,
  0x06,
  0x07,
  0x2a,
  0x86,
  0x48,
  0xce,
  0x3d,
  0x02,
  0x01,
  0x06,
  0x08,
  0x2a,
  0x86,
  0x48,
  0xce,
  0x3d,
  0x03,
  0x01,
  0x07,
  0x03,
  0x42,
  0x00
);
export function pinnedVerifier(spki) {
  if (
    spki.length !== prefix.length + 65 ||
    spki[prefix.length] !== 4 ||
    !prefix.every((byte, i) => byte === spki[i])
  ) {
    throw new Error('Invalid controller key.');
  }
  const point = spki.slice(prefix.length);
  p256.Point.fromBytes(point).assertValidity();
  // OpenSSL legitimately emits both low- and high-S P1363 signatures.
  return (signature, payload) =>
    p256.verify(signature, payload, point, { prehash: true, lowS: false, format: 'compact' });
}
