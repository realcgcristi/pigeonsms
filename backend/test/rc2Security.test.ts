import { describe, expect, it } from 'vitest';
import { pairingVerificationCode } from '../src/lib/pairing';
import { resolveWebAuthnContext } from '../src/lib/webauthn';
import type { Env } from '../src/types';

function env(fields: Partial<Env> = {}): Env {
  return fields as Env;
}

describe('device pairing transcript', () => {
  it('produces a stable six-digit comparison code', async () => {
    const code = await pairingVerificationCode('pair-1', 'secret-a', 'claim-a');
    expect(code).toMatch(/^\d{6}$/);
    expect(await pairingVerificationCode('pair-1', 'secret-a', 'claim-a')).toBe(code);
  });

  it('binds the comparison code to every transcript field', async () => {
    const base = await pairingVerificationCode('pair-1', 'secret-a', 'claim-a');
    await expect(pairingVerificationCode('pair-2', 'secret-a', 'claim-a')).resolves.not.toBe(base);
    await expect(pairingVerificationCode('pair-1', 'secret-b', 'claim-a')).resolves.not.toBe(base);
    await expect(pairingVerificationCode('pair-1', 'secret-a', 'claim-b')).resolves.not.toBe(base);
  });
});

describe('webauthn relying-party binding', () => {
  it('binds first-party and desktop origins to their exact host', () => {
    expect(resolveWebAuthnContext(env(), 'https://pigeonsms.aldi.best', 'web')).toEqual({
      rpID: 'pigeonsms.aldi.best',
      expectedOrigins: ['https://pigeonsms.aldi.best'],
    });
    expect(resolveWebAuthnContext(env(), 'https://tauri.localhost', 'desktop')).toEqual({
      rpID: 'tauri.localhost',
      expectedOrigins: ['https://tauri.localhost'],
    });
  });

  it('accepts pages previews but rejects lookalike origins', () => {
    expect(resolveWebAuthnContext(env(), 'https://feature-123.pigeonsms-web.pages.dev', 'web').rpID)
      .toBe('feature-123.pigeonsms-web.pages.dev');
    expect(() => resolveWebAuthnContext(env(), 'https://pigeonsms.aldi.best.evil.test', 'web'))
      .toThrow('passkeys are not available');
  });

  it('requires explicit android signing origins', () => {
    expect(() => resolveWebAuthnContext(env(), undefined, 'android')).toThrow('android passkeys are not configured');
    expect(resolveWebAuthnContext(env({
      WEBAUTHN_RP_ID: 'pigeonsms.aldi.best',
      WEBAUTHN_ANDROID_ORIGINS: 'android:apk-key-hash:release,android:apk-key-hash:debug',
    }), undefined, 'android')).toEqual({
      rpID: 'pigeonsms.aldi.best',
      expectedOrigins: ['android:apk-key-hash:release', 'android:apk-key-hash:debug'],
    });
  });
});
