/**
 * Bot identity primitives shared by the bot routes, the interaction dispatcher
 * and the client-facing command endpoints.
 *
 * ## Why a bot is a user
 *
 * A bot owns a real `users` row carrying `BOT_USER_FLAG`, plus a row in `bots`.
 * Everything that already works on a user — DM channels, space membership,
 * messages, reactions, attachments, profiles — therefore works for a bot with no
 * special-casing. The `bots` row only adds what a bot needs on top: who owns it,
 * how to reach it, and the secrets.
 *
 * ## Why the token contains the bot id
 *
 * `PGB.<botId>.<secret>` is human-diagnosable (an author can tell two of their
 * tokens apart in a .env without decrypting anything) and lets a client route on
 * sight. Auth never trusts that segment: `sha256Hex` of the **whole** string is
 * what is stored and what is looked up, so a forged prefix buys nothing.
 */

import { ApiError } from '../middleware/errors';
import { sha256Hex } from './crypto';
import type { Env } from '../types';

/** `users.flags & 1` marks a bot account. Bit 0 was previously unused. */
export const BOT_USER_FLAG = 1;

export function isBotUser(flags: number | null | undefined): boolean {
  return ((flags ?? 0) & BOT_USER_FLAG) !== 0;
}

/** Hard caps from the build contract; enforced on every command write. */
export const MAX_COMMAND_OPTIONS = 25;
export const MAX_COMMANDS_PER_BOT = 100;

const COMMAND_NAME_RE = /^[a-z0-9_-]{1,32}$/;
const OPTION_TYPES = ['string', 'integer', 'number', 'boolean', 'user', 'channel'] as const;

export type BotOptionType = (typeof OPTION_TYPES)[number];

export interface BotCommandOptionChoice {
  name: string;
  value: string | number;
}

export interface BotCommandOption {
  name: string;
  description: string;
  type: BotOptionType;
  required: boolean;
  choices?: BotCommandOptionChoice[];
  min?: number;
  max?: number;
}

export interface BotRow {
  id: string;
  user_id: string;
  owner_id: string;
  name: string;
  description: string | null;
  token_hash: string;
  interactions_url: string | null;
  signing_secret: string;
  public: number;
  dm_enabled: number;
  encryption_mode: string;
  encryption_public_key: string | null;
  created_at: number;
  updated_at: number;
  deleted_at: number | null;
}

export interface BotCommandRow {
  id: string;
  bot_id: string;
  space_id: string | null;
  name: string;
  description: string;
  options: string;
  dm_enabled: number;
  created_at: number;
}

export interface BotInteractionRow {
  id: string;
  bot_id: string;
  command: string;
  options: string;
  user_id: string;
  channel_id: string;
  space_id: string | null;
  is_dm: number;
  state: string;
  delivery: string;
  callback_token_hash: string;
  response: string | null;
  error: string | null;
  created_at: number;
  delivered_at: number | null;
  responded_at: number | null;
}

const BOT_COLUMNS =
  'id, user_id, owner_id, name, description, token_hash, interactions_url, signing_secret, ' +
  'public, dm_enabled, encryption_mode, encryption_public_key, created_at, updated_at, deleted_at';

const encoder = new TextEncoder();

function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replaceAll('=', '');
}

/**
 * Mint a fresh bot token. Returns the raw token (shown to the owner exactly
 * once, at creation and at rotation) alongside the hash that is all we persist.
 */
export async function mintBotToken(botId: string): Promise<{ token: string; hash: string }> {
  const secret = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const token = `PGB.${botId}.${secret}`;
  return { token, hash: await sha256Hex(token) };
}

/** 32 random bytes, base64url — used for `signing_secret` and callback tokens. */
export function randomSecret(): string {
  return base64url(crypto.getRandomValues(new Uint8Array(32)));
}

/** Live (non-deleted) bot by id, or null. */
export async function loadBot(env: Env, botId: string): Promise<BotRow | null> {
  return await env.DB.prepare(`SELECT ${BOT_COLUMNS} FROM bots WHERE id = ? AND deleted_at IS NULL`)
    .bind(botId)
    .first<BotRow>();
}

/** Live bot by its *user* id — the lookup for "is this author a bot, and whose?". */
export async function loadBotByUser(env: Env, userId: string): Promise<BotRow | null> {
  return await env.DB.prepare(
    `SELECT ${BOT_COLUMNS} FROM bots WHERE user_id = ? AND deleted_at IS NULL`,
  )
    .bind(userId)
    .first<BotRow>();
}

/**
 * Load a bot and assert the caller owns it.
 *
 * A bot that exists but belongs to someone else 404s rather than 403s: the owner
 * list is private, and a 403 would confirm the id is real to anyone probing.
 */
export async function requireBotOwner(env: Env, botId: string, userId: string): Promise<BotRow> {
  const bot = await loadBot(env, botId);
  if (!bot || bot.owner_id !== userId) throw new ApiError(404, 'not_found', 'bot not found');
  return bot;
}

export function validateCommandName(raw: unknown): string {
  const name = String(raw ?? '').trim().toLowerCase();
  if (!COMMAND_NAME_RE.test(name)) {
    throw new ApiError(400, 'invalid_command_name', '1-32 chars: a-z, 0-9, underscore, hyphen');
  }
  return name;
}

function validateChoices(raw: unknown, type: BotOptionType, optionName: string): BotCommandOptionChoice[] | undefined {
  if (raw === undefined || raw === null) return undefined;
  if (!Array.isArray(raw)) {
    throw new ApiError(400, 'invalid_option', `${optionName}: choices must be an array`);
  }
  if (raw.length > MAX_COMMAND_OPTIONS) {
    throw new ApiError(400, 'invalid_option', `${optionName}: at most ${MAX_COMMAND_OPTIONS} choices`);
  }
  // Choices only make sense for values the user picks from a list; a user or
  // channel option is already a picker, and a boolean has two values by nature.
  if (type !== 'string' && type !== 'integer' && type !== 'number') {
    throw new ApiError(400, 'invalid_option', `${optionName}: choices need a string or numeric type`);
  }
  return raw.map((entry) => {
    if (entry === null || typeof entry !== 'object') {
      throw new ApiError(400, 'invalid_option', `${optionName}: each choice must be an object`);
    }
    const choice = entry as Record<string, unknown>;
    const name = String(choice.name ?? '').trim();
    if (!name || name.length > 64) {
      throw new ApiError(400, 'invalid_option', `${optionName}: choice names are 1-64 chars`);
    }
    const value = choice.value;
    if (type === 'string') {
      if (typeof value !== 'string' || !value || value.length > 256) {
        throw new ApiError(400, 'invalid_option', `${optionName}: choice values must be strings`);
      }
      return { name, value };
    }
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      throw new ApiError(400, 'invalid_option', `${optionName}: choice values must be numbers`);
    }
    if (type === 'integer' && !Number.isInteger(value)) {
      throw new ApiError(400, 'invalid_option', `${optionName}: choice values must be integers`);
    }
    return { name, value };
  });
}

/**
 * Validate a submitted option array (already-parsed JSON or a JSON string) into
 * the normalised shape we persist.
 *
 * Normalising at write time is deliberate: the command palette, the interaction
 * validator and the webhook payload all read these blobs on the hot path, and
 * none of them should have to re-decide what a missing `required` means.
 */
export function validateCommandOptions(json: unknown): BotCommandOption[] {
  let raw = json;
  if (typeof raw === 'string') {
    if (!raw.trim()) return [];
    try {
      raw = JSON.parse(raw) as unknown;
    } catch {
      throw new ApiError(400, 'invalid_option', 'options must be valid JSON');
    }
  }
  if (raw === undefined || raw === null) return [];
  if (!Array.isArray(raw)) throw new ApiError(400, 'invalid_option', 'options must be an array');
  if (raw.length > MAX_COMMAND_OPTIONS) {
    throw new ApiError(400, 'too_many_options', `at most ${MAX_COMMAND_OPTIONS} options per command`);
  }

  const seen = new Set<string>();
  const options: BotCommandOption[] = [];
  for (const entry of raw) {
    if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) {
      throw new ApiError(400, 'invalid_option', 'each option must be an object');
    }
    const src = entry as Record<string, unknown>;
    const name = validateCommandName(src.name);
    if (seen.has(name)) {
      throw new ApiError(400, 'invalid_option', `duplicate option: ${name}`);
    }
    seen.add(name);

    const type = String(src.type ?? 'string') as BotOptionType;
    if (!OPTION_TYPES.includes(type)) {
      throw new ApiError(400, 'invalid_option', `${name}: unknown option type ${type}`);
    }

    const description = String(src.description ?? '').trim().slice(0, 200);
    if (!description) {
      throw new ApiError(400, 'invalid_option', `${name}: description is required`);
    }

    const option: BotCommandOption = {
      name,
      description,
      type,
      required: src.required === true,
    };

    const choices = validateChoices(src.choices, type, name);
    if (choices && choices.length > 0) option.choices = choices;

    for (const bound of ['min', 'max'] as const) {
      const value = src[bound];
      if (value === undefined || value === null) continue;
      if (typeof value !== 'number' || !Number.isFinite(value)) {
        throw new ApiError(400, 'invalid_option', `${name}: ${bound} must be a number`);
      }
      // min/max is a length bound on strings and a value bound on numbers; on
      // the remaining types it means nothing, so reject it rather than store a
      // constraint that would never be applied.
      if (type !== 'string' && type !== 'integer' && type !== 'number') {
        throw new ApiError(400, 'invalid_option', `${name}: ${bound} needs a string or numeric type`);
      }
      option[bound] = value;
    }
    if (option.min !== undefined && option.max !== undefined && option.min > option.max) {
      throw new ApiError(400, 'invalid_option', `${name}: min is greater than max`);
    }

    options.push(option);
  }
  return options;
}

const SNOWFLAKE_RE = /^[0-9]{1,20}$/;

/**
 * Validate and coerce one submitted value against its declared option.
 *
 * Returns `undefined` for an absent optional value so the caller can simply omit
 * it from the payload. Wire clients send everything as strings often enough
 * (a form field, a parsed slash-command argument) that numeric and boolean
 * options accept the string spelling too — the coercion is what makes the bot's
 * payload strongly typed regardless.
 */
export function coerceOption(
  value: unknown,
  option: BotCommandOption,
): string | number | boolean | undefined {
  const missing = value === undefined || value === null || value === '';
  if (missing) {
    if (option.required) {
      throw new ApiError(400, 'missing_option', `${option.name} is required`);
    }
    return undefined;
  }

  let coerced: string | number | boolean;
  switch (option.type) {
    case 'boolean': {
      if (typeof value === 'boolean') coerced = value;
      else if (value === 'true' || value === 'false') coerced = value === 'true';
      else throw new ApiError(400, 'invalid_option', `${option.name} must be a boolean`);
      break;
    }
    case 'integer':
    case 'number': {
      const num = typeof value === 'number' ? value : Number(String(value).trim());
      if (!Number.isFinite(num)) {
        throw new ApiError(400, 'invalid_option', `${option.name} must be a number`);
      }
      if (option.type === 'integer' && !Number.isInteger(num)) {
        throw new ApiError(400, 'invalid_option', `${option.name} must be a whole number`);
      }
      if (option.min !== undefined && num < option.min) {
        throw new ApiError(400, 'invalid_option', `${option.name} must be at least ${option.min}`);
      }
      if (option.max !== undefined && num > option.max) {
        throw new ApiError(400, 'invalid_option', `${option.name} must be at most ${option.max}`);
      }
      coerced = num;
      break;
    }
    case 'user':
    case 'channel': {
      const id = String(value).trim();
      if (!SNOWFLAKE_RE.test(id)) {
        throw new ApiError(400, 'invalid_option', `${option.name} must be a ${option.type} id`);
      }
      coerced = id;
      break;
    }
    default: {
      const text = String(value);
      if (option.min !== undefined && text.length < option.min) {
        throw new ApiError(400, 'invalid_option', `${option.name} must be at least ${option.min} characters`);
      }
      // Cap unbounded strings so one option can't carry a megabyte into the
      // interaction row (and from there into every webhook payload).
      const limit = option.max ?? 2000;
      if (text.length > limit) {
        throw new ApiError(400, 'invalid_option', `${option.name} must be at most ${limit} characters`);
      }
      coerced = text;
      break;
    }
  }

  if (option.choices && option.choices.length > 0) {
    if (!option.choices.some((choice) => choice.value === coerced)) {
      throw new ApiError(400, 'invalid_option', `${option.name} is not one of the allowed choices`);
    }
  }
  return coerced;
}

/**
 * `sha256=<hex>` HMAC over `<timestamp>.<body>`, sent as `X-Pigeon-Signature`.
 *
 * The timestamp is inside the signed string (not just a sibling header) so a
 * captured payload can't be replayed later with a fresh timestamp.
 */
export async function signPayload(
  secret: string,
  timestamp: number | string,
  body: string,
): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const mac = new Uint8Array(
    await crypto.subtle.sign('HMAC', key, encoder.encode(`${timestamp}.${body}`)),
  );
  const hex = [...mac].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `sha256=${hex}`;
}
