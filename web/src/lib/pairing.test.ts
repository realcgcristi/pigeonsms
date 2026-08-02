import { afterEach, describe, expect, it, vi } from 'vitest';
import { createClaimSecret, parsePairingInvite } from './pairing';

afterEach(() => vi.unstubAllGlobals());

describe('pairing links', () => {
  it('accepts the first-party web and app forms', () => {
    vi.stubGlobal('window', { location: { origin: 'https://pigeonsms.aldi.best' } });
    const query = 'pairing_id=12345678&secret=abcdefghijklmnopqrstuvwxyzABCDEFGH123456789&api=https%3A%2F%2Fapi.pigeonsms.aldi.best';
    expect(parsePairingInvite(`https://pigeonsms.aldi.best/pair?${query}`)?.id).toBe('12345678');
    expect(parsePairingInvite(`pigeonsms://pair?${query}`)?.secret).toHaveLength(43);
  });

  it('rejects foreign APIs and malformed secrets', () => {
    vi.stubGlobal('window', { location: { origin: 'https://pigeonsms.aldi.best' } });
    expect(parsePairingInvite('pigeonsms://pair?pairing_id=12345678&secret=short&api=https://evil.test')).toBeNull();
  });

  it('creates url-safe 256-bit claim secrets', () => {
    expect(createClaimSecret()).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });
});
