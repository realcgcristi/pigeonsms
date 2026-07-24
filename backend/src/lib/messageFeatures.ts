import { ApiError } from '../middleware/errors';

export const MESSAGE_KINDS = [
  'text',
  'poll',
  'event',
  'sticker',
  'forum_post',
  'forum_reply',
] as const;

export type MessageKind = (typeof MESSAGE_KINDS)[number];

export interface MentionTokens {
  everyone: boolean;
  usernames: string[];
}

export interface PollInput {
  question: string;
  options: string[];
  anonymous: boolean;
  multipleChoice: false;
}

const USER_MENTION = /(^|[^a-z0-9_.])@([a-z0-9_.]{3,20}|everyone)\b/giu;

/** Parse unique, normalized mention tokens without trusting them as recipients. */
export function parseMentionTokens(content: string): MentionTokens {
  const usernames = new Set<string>();
  let everyone = false;
  for (const match of content.matchAll(USER_MENTION)) {
    const username = match[2]?.toLowerCase();
    if (!username) continue;
    if (username === 'everyone') everyone = true;
    else usernames.add(username);
  }
  return { everyone, usernames: [...usernames] };
}

export function normalizeMessageKind(raw: unknown, internal = false): MessageKind {
  const kind = String(raw ?? 'text').trim().toLowerCase() as MessageKind;
  if (!MESSAGE_KINDS.includes(kind)) {
    throw new ApiError(400, 'bad_message_kind', 'unsupported message kind');
  }
  if (!internal && (kind === 'forum_post' || kind === 'forum_reply')) {
    throw new ApiError(400, 'bad_message_kind', 'use the forum endpoints');
  }
  return kind;
}

function objectBody(raw: unknown): Record<string, unknown> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
  return raw as Record<string, unknown>;
}

const MAX_METADATA_JSON = 2048;

/** Kinds that pass client metadata through must still stay bounded on disk. */
function capMetadata(value: Record<string, unknown>): Record<string, unknown> {
  if (JSON.stringify(value).length > MAX_METADATA_JSON) {
    throw new ApiError(400, 'bad_metadata', `metadata must serialize to ${MAX_METADATA_JSON} characters or fewer`);
  }
  return value;
}

/** Validate structured metadata and return a JSON-safe object. */
export function normalizeMessageMetadata(
  kind: MessageKind,
  raw: unknown,
  isSpace: boolean,
): Record<string, unknown> | null {
  const input = objectBody(raw);
  if (kind === 'text' || kind === 'poll') return Object.keys(input).length ? capMetadata(input) : null;

  if (kind === 'event') {
    if (!isSpace) throw new ApiError(400, 'space_only', 'events are available in spaces only');
    const title = String(input['title'] ?? '').trim().slice(0, 120);
    const startsAt = Number(input['starts_at']);
    const endsAt = input['ends_at'] === undefined || input['ends_at'] === null
      ? null
      : Number(input['ends_at']);
    if (!title || !Number.isFinite(startsAt) || startsAt <= 0) {
      throw new ApiError(400, 'bad_event', 'event title and starts_at are required');
    }
    if (endsAt !== null && (!Number.isFinite(endsAt) || endsAt < startsAt)) {
      throw new ApiError(400, 'bad_event', 'ends_at must be after starts_at');
    }
    return {
      title,
      starts_at: startsAt,
      ends_at: endsAt,
      location: input['location'] === undefined ? null : String(input['location']).trim().slice(0, 200),
      description: input['description'] === undefined
        ? null
        : String(input['description']).trim().slice(0, 1000),
    };
  }

  if (kind === 'sticker') {
    const stickerId = String(input['sticker_id'] ?? '').trim().slice(0, 128);
    const alt = String(input['alt'] ?? 'sticker').trim().slice(0, 120);
    return { sticker_id: stickerId || null, alt: alt || 'sticker' };
  }

  if (kind === 'forum_post') {
    const title = String(input['title'] ?? '').trim().slice(0, 160);
    if (!title) throw new ApiError(400, 'bad_forum_post', 'post title is required');
    // Only echo the metadata keys the forum actually uses. Spreading raw client
    // input let arbitrary keys ride through and get served to every viewer
    // (stored XSS surface). Structured forum features (tag, pinned, likes, mark)
    // live in dedicated columns/tables, not here — so title/description suffice.
    const metadata: Record<string, unknown> = { title };
    if (input['description'] !== undefined && input['description'] !== null) {
      metadata['description'] = String(input['description']).trim().slice(0, 1000);
    }
    return capMetadata(metadata);
  }

  return Object.keys(input).length ? capMetadata(input) : null;
}

export function normalizePoll(raw: unknown): PollInput {
  const input = objectBody(raw);
  const question = String(input['question'] ?? '').trim().slice(0, 300);
  const rawOptions = Array.isArray(input['options']) ? input['options'] : [];
  const options = rawOptions.map((value) => String(value).trim().slice(0, 120)).filter(Boolean);
  if (!question) throw new ApiError(400, 'bad_poll', 'poll question is required');
  if (options.length < 2 || options.length > 10) {
    throw new ApiError(400, 'bad_poll', 'polls need 2 to 10 options');
  }
  if (new Set(options.map((option) => option.toLocaleLowerCase())).size !== options.length) {
    throw new ApiError(400, 'bad_poll', 'poll options must be unique');
  }
  if (input['multiple_choice'] === true) {
    throw new ApiError(400, 'bad_poll', 'this server supports one choice per poll');
  }
  return {
    question,
    options,
    anonymous: input['anonymous'] === true,
    multipleChoice: false,
  };
}

export function parseStoredMetadata(raw: string | null): Record<string, unknown> | null {
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as unknown;
    return value && typeof value === 'object' && !Array.isArray(value)
      ? value as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}
