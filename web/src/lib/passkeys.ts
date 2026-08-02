import { api, deviceName } from '@/api/client';

export function passkeysSupported(): boolean {
  return typeof window !== 'undefined' && window.isSecureContext && 'PublicKeyCredential' in window;
}

function passkeyError(error: unknown): Error {
  if (error instanceof DOMException && error.name === 'NotAllowedError') {
    return new Error('passkey request cancelled or timed out');
  }
  if (error instanceof DOMException && error.name === 'InvalidStateError') {
    return new Error('that passkey is already registered');
  }
  return error instanceof Error ? error : new Error('passkey request failed');
}

export async function authenticateWithPasskey(login?: string) {
  if (!passkeysSupported()) throw new Error('passkeys are not supported on this device');
  try {
    const [pending, browser] = await Promise.all([
      api.passkeyAuthenticationOptions(login),
      import('@simplewebauthn/browser'),
    ]);
    const response = await browser.startAuthentication({ optionsJSON: pending.options });
    return api.verifyPasskeyAuthentication(pending.challenge_id, response);
  } catch (error) {
    throw passkeyError(error);
  }
}

export async function registerPasskey(name = deviceName()) {
  if (!passkeysSupported()) throw new Error('passkeys are not supported on this device');
  try {
    const [pending, browser] = await Promise.all([
      api.passkeyRegistrationOptions(),
      import('@simplewebauthn/browser'),
    ]);
    const response = await browser.startRegistration({ optionsJSON: pending.options });
    return api.verifyPasskeyRegistration(pending.challenge_id, response, name);
  } catch (error) {
    throw passkeyError(error);
  }
}
