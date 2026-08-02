import { sha256Hex } from './crypto';

export const PAIRING_TTL_MS = 5 * 60_000;
export const PAIRING_SECRET_PATTERN = /^[A-Za-z0-9_-]{43}$/;

export async function pairingVerificationCode(
  pairingId: string,
  secret: string,
  claimSecret: string,
): Promise<string> {
  const digest = await sha256Hex(`pigeon-pair-v1:${pairingId}:${secret}:${claimSecret}`);
  return (BigInt(`0x${digest.slice(0, 12)}`) % 1_000_000n).toString().padStart(6, '0');
}
