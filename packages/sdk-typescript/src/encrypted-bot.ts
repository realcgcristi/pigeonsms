import { PigeonClient } from './client.js';
import type { Message } from './types.js';

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function b64(bytes: Uint8Array): string {
  let value = '';
  for (let index = 0; index < bytes.length; index += 0x8000) value += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  return btoa(value).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}

function unb64(value: string): Uint8Array<ArrayBuffer> {
  const input = value.replaceAll('-', '+').replaceAll('_', '/').padEnd(Math.ceil(value.length / 4) * 4, '=');
  const binary = atob(input);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

export interface EncryptedBotIdentity {
  publicKey: string;
  privateKey: JsonWebKey;
}

export interface BotKeyEnvelope {
  algorithm: 'OP-BOT-X25519-HKDF-A256GCM-1';
  ephemeral_public_key: string;
  salt: string;
  iv: string;
  ciphertext: string;
}

async function wrappingKey(privateKey: CryptoKey, publicKey: CryptoKey, salt: Uint8Array<ArrayBuffer>) {
  const secret = await crypto.subtle.deriveBits({ name: 'X25519', public: publicKey }, privateKey, 256);
  const material = await crypto.subtle.importKey('raw', secret, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt, info: encoder.encode('open-pigeon encrypted bot key') },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

export async function generateEncryptedBotIdentity(): Promise<EncryptedBotIdentity> {
  const pair = await crypto.subtle.generateKey({ name: 'X25519' }, true, ['deriveBits']) as CryptoKeyPair;
  return {
    publicKey: b64(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey))),
    privateKey: await crypto.subtle.exportKey('jwk', pair.privateKey),
  };
}

export async function sealBotChannelKey(publicKey: string, channelKey: Uint8Array<ArrayBuffer>): Promise<BotKeyEnvelope> {
  const recipient = await crypto.subtle.importKey('raw', unb64(publicKey), { name: 'X25519' }, false, []);
  const ephemeral = await crypto.subtle.generateKey({ name: 'X25519' }, true, ['deriveBits']) as CryptoKeyPair;
  const salt = crypto.getRandomValues(new Uint8Array(32));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await wrappingKey(ephemeral.privateKey, recipient, salt);
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, channelKey);
  return {
    algorithm: 'OP-BOT-X25519-HKDF-A256GCM-1',
    ephemeral_public_key: b64(new Uint8Array(await crypto.subtle.exportKey('raw', ephemeral.publicKey))),
    salt: b64(salt),
    iv: b64(iv),
    ciphertext: b64(new Uint8Array(ciphertext)),
  };
}

export class EncryptedBotRuntime {
  readonly client: PigeonClient;
  readonly identity: EncryptedBotIdentity;
  private readonly privateKey: CryptoKey;
  private readonly channelKeys = new Map<string, CryptoKey>();
  deviceId: string | null = null;

  private constructor(client: PigeonClient, identity: EncryptedBotIdentity, privateKey: CryptoKey) {
    this.client = client;
    this.identity = identity;
    this.privateKey = privateKey;
  }

  static async create(options: { baseUrl: string; token: string; identity?: EncryptedBotIdentity; fetch?: typeof fetch }) {
    const identity = options.identity ?? await generateEncryptedBotIdentity();
    const privateKey = await crypto.subtle.importKey('jwk', identity.privateKey, { name: 'X25519' }, false, ['deriveBits']);
    return new EncryptedBotRuntime(new PigeonClient({ baseUrl: options.baseUrl, token: options.token, ...(options.fetch ? { fetch: options.fetch } : {}) }), identity, privateKey);
  }

  async register(name = 'encrypted bot runtime') {
    const result = await this.client.registerDevice(this.identity.publicKey, name);
    this.deviceId = result.id;
    return { ...result, public_key: this.identity.publicKey };
  }

  async openEnvelope(envelope: BotKeyEnvelope): Promise<Uint8Array<ArrayBuffer>> {
    if (envelope.algorithm !== 'OP-BOT-X25519-HKDF-A256GCM-1') throw new Error('unsupported bot envelope');
    const ephemeral = await crypto.subtle.importKey('raw', unb64(envelope.ephemeral_public_key), { name: 'X25519' }, false, []);
    const key = await wrappingKey(this.privateKey, ephemeral, unb64(envelope.salt));
    return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(envelope.iv) }, key, unb64(envelope.ciphertext)));
  }

  async syncChannelKey(channelId: string) {
    if (!this.deviceId) throw new Error('register the encrypted bot runtime first');
    const envelopes = await this.client.keyEnvelopes(channelId);
    const stored = envelopes.find((envelope) => envelope.to_device === this.deviceId);
    if (!stored) return false;
    const raw = await this.openEnvelope(JSON.parse(stored.wrapped_key) as BotKeyEnvelope);
    this.channelKeys.set(channelId, await crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']));
    return true;
  }

  async setChannelKey(channelId: string, raw: Uint8Array<ArrayBuffer>) {
    this.channelKeys.set(channelId, await crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']));
  }

  async encrypt(channelId: string, plaintext: string) {
    const key = this.channelKeys.get(channelId);
    if (!key) throw new Error('no channel key');
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, encoder.encode(plaintext));
    return b64(encoder.encode(JSON.stringify({ algorithm: 'OP-MSG-A256GCM-1', iv: b64(iv), ciphertext: b64(new Uint8Array(ciphertext)) })));
  }

  async decrypt(message: Message) {
    const key = this.channelKeys.get(message.channel_id);
    if (!key || !message.encrypted) throw new Error('message is not decryptable');
    const payload = JSON.parse(decoder.decode(unb64(message.content))) as { algorithm: string; iv: string; ciphertext: string };
    if (payload.algorithm !== 'OP-MSG-A256GCM-1') throw new Error('unsupported encrypted message');
    const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(payload.iv) }, key, unb64(payload.ciphertext));
    return decoder.decode(plaintext);
  }

  async send(channelId: string, plaintext: string) {
    return this.client.sendMessage(channelId, {
      content: await this.encrypt(channelId, plaintext),
      encrypted: true,
      metadata: { e2ee: { algorithm: 'OP-MSG-A256GCM-1', bot: true } },
    });
  }
}
