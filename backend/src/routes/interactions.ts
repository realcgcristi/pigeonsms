import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { assertChannelAccess, fanout, type ChannelRow, type WaitUntilCtx } from '../lib/channels';
import { Permission, requirePermission } from '../lib/permissions';
import { enforceRateLimit } from '../middleware/ratelimit';
import { snowflake } from '../lib/ids';
import { sha256Hex, timingSafeEqualStrings } from '../lib/crypto';
import { assertOwnedAttachment, type AttachmentInput } from '../lib/media';
import { readJsonBody } from '../lib/validate';
import type { MessageKind } from '../lib/messageFeatures';
import {
  coerceOption,
  loadBot,
  randomSecret,
  signPayload,
  validateCommandOptions,
  type BotCommandOption,
  type BotInteractionRow,
  type BotRow,
} from '../lib/bots';
import { deliverMessage } from './messages';
import type { AppEnv, AuthedUser, Env } from '../types';

/**
 * The interactions engine: what a *human* sees (the command palette, invoking a
 * command) and what a *bot* sees (the long poll, the callback).
 *
 * ## Why the invocation is a real message
 *
 * `/roll sides:20` is inserted through the same `deliverMessage` core as any
 * other send, so it allocates a seq, fans out to every gateway and lands in the
 * scrollback in order. A parallel "invocation" concept would have needed its own
 * ordering, its own fanout and its own pagination — and would still have looked
 * like a message to the reader. The only difference is `kind: "command"` plus the
 * metadata a client renders the chip from.
 *
 * ## Why a bot failure is never a 500
 *
 * The caller did nothing wrong when a bot's webhook times out or answers
 * garbage. The interaction is marked `failed`, the error travels back in the
 * response body, and the invocation message stays — so the client can show
 * "the bot didn't answer" under the chip instead of a red toast about our API.
 */

const interactions = new Hono<AppEnv>();
// scoped: this router mounts at '/', a bare use() would guard the whole app
interactions.use('/channels/*', requireAuth);
interactions.use('/bots/me/updates', requireAuth);
interactions.use('/interactions/*', requireAuth);

/** Same cap the message send path applies to user content. */
const MAX_CONTENT = 4000;
/** An unanswered interaction is swept to `expired` after this long (spec: 15 min). */
export const INTERACTION_TTL_MS = 15 * 60_000;
const WEBHOOK_TIMEOUT_MS = 3000;
const MAX_POLL_WAIT_S = 25;
const POLL_STEP_MS = 200;
const MAX_UPDATES_PER_POLL = 50;
/** Serialized size cap for one invocation's coerced options. */
const MAX_OPTIONS_JSON = 4000;

/**
 * `command` is not in `MESSAGE_KINDS`: that list gates what a *client* may send
 * on the normal message endpoint, and nobody should be able to forge an
 * invocation chip by hand. The column is plain TEXT, so the server stamps the
 * kind directly here.
 */
const COMMAND_KIND = 'command' as unknown as MessageKind;

interface CommandRow {
  id: string;
  bot_id: string;
  space_id: string | null;
  name: string;
  description: string;
  options: string;
  dm_enabled: number;
  bot_user_id: string;
  username: string;
  display_name: string | null;
  avatar_key: string | null;
}

const COMMAND_SELECT =
  `bc.id, bc.bot_id, bc.space_id, bc.name, bc.description, bc.options, bc.dm_enabled,
   b.user_id AS bot_user_id, u.username, u.display_name, u.avatar_key`;

function serializeCommand(row: CommandRow): Record<string, unknown> {
  return {
    id: row.id,
    bot: {
      id: row.bot_id,
      user_id: row.bot_user_id,
      username: row.username,
      display_name: row.display_name,
      avatar_key: row.avatar_key,
    },
    name: row.name,
    description: row.description,
    options: parseOptions(row.options),
  };
}

/**
 * Stored option blobs were normalised by `validateCommandOptions` when the bot
 * registered them, so re-running it here is a cheap re-validation — and a row
 * written before a schema tweak degrades to "no options" instead of 500ing the
 * whole palette.
 */
function parseOptions(raw: string): BotCommandOption[] {
  try {
    return validateCommandOptions(raw);
  } catch {
    return [];
  }
}

/**
 * Channel lookup for the command palette: not-a-member reads as not-found.
 *
 * `assertChannelAccess` distinguishes 404 (no such channel) from 403 (yours to
 * see, someone else's channel). For a read-only listing that distinction only
 * tells a prober which channel ids exist, so both collapse to 404 here.
 */
async function visibleChannel(env: Env, userId: string, channelId: string): Promise<ChannelRow> {
  try {
    return await assertChannelAccess(env, userId, channelId);
  } catch {
    throw new ApiError(404, 'not_found', 'no such channel');
  }
}

/**
 * Every command invocable in [channel].
 *
 * In a nest: each bot that is a *member* contributes its global commands plus
 * the ones scoped to this nest. In a DM: the peer, if it is a bot with DMs
 * enabled, contributes its global `dm_enabled` commands — a nest-scoped command
 * has no meaning outside its nest, so it never appears here.
 */
async function channelCommands(
  env: Env,
  channel: ChannelRow,
  viewerId: string,
): Promise<CommandRow[]> {
  if (channel.space_id) {
    const { results } = await env.DB.prepare(
      `SELECT ${COMMAND_SELECT}
       FROM bot_commands bc
       JOIN bots b ON b.id = bc.bot_id AND b.deleted_at IS NULL
       JOIN space_members sm ON sm.space_id = ? AND sm.user_id = b.user_id
       JOIN users u ON u.id = b.user_id AND u.deleted_at IS NULL
       WHERE bc.space_id IS NULL OR bc.space_id = ?
       ORDER BY bc.name, bc.bot_id`,
    )
      .bind(channel.space_id, channel.space_id)
      .all<CommandRow>();
    return results;
  }
  const { results } = await env.DB.prepare(
    `SELECT ${COMMAND_SELECT}
     FROM bot_commands bc
     JOIN bots b ON b.id = bc.bot_id AND b.deleted_at IS NULL AND b.dm_enabled = 1
     JOIN channel_members cm ON cm.channel_id = ? AND cm.user_id = b.user_id
     JOIN users u ON u.id = b.user_id AND u.deleted_at IS NULL
     WHERE cm.user_id != ? AND bc.dm_enabled = 1 AND bc.space_id IS NULL
     ORDER BY bc.name, bc.bot_id`,
  )
    .bind(channel.id, viewerId)
    .all<CommandRow>();
  return results;
}

/** GET /channels/:id/commands — the palette a client opens on `/`. */
interactions.get('/channels/:id/commands', async (c) => {
  const user = c.get('user') as AuthedUser;
  const channel = await visibleChannel(c.env, user.id, c.req.param('id'));
  const rows = await channelCommands(c.env, channel, user.id);
  return c.json({ commands: rows.map(serializeCommand) });
});

function serializeInteraction(row: BotInteractionRow): Record<string, unknown> {
  return {
    id: row.id,
    bot_id: row.bot_id,
    command: row.command,
    options: JSON.parse(row.options) as Record<string, unknown>,
    user_id: row.user_id,
    channel_id: row.channel_id,
    space_id: row.space_id,
    is_dm: row.is_dm === 1,
    state: row.state,
    delivery: row.delivery,
    error: row.error,
    created_at: row.created_at,
    delivered_at: row.delivered_at,
    responded_at: row.responded_at,
  };
}

/** The `/name arg:value` line that lands in the transcript. */
function invocationText(name: string, defs: BotCommandOption[], options: Record<string, unknown>): string {
  const parts = [`/${name}`];
  for (const def of defs) {
    const value = options[def.name];
    if (value === undefined) continue;
    const text = String(value);
    // Quote anything with whitespace so `text:hello world` still reads as one
    // argument when a client re-parses the line it is rendering.
    parts.push(`${def.name}:${/\s/.test(text) ? JSON.stringify(text) : text}`);
  }
  return parts.join(' ').slice(0, MAX_CONTENT);
}

function objectBody(raw: unknown): Record<string, unknown> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
  return raw as Record<string, unknown>;
}

async function loadChannel(env: Env, channelId: string): Promise<ChannelRow | null> {
  return await env.DB.prepare(
    `SELECT ch.id, ch.space_id, ch.name, ch.topic, ch.kind, ch.last_seq
     FROM channels ch LEFT JOIN spaces s ON s.id = ch.space_id
     WHERE ch.id = ? AND ch.deleted_at IS NULL
       AND (ch.space_id IS NULL OR s.deleted_at IS NULL)`,
  )
    .bind(channelId)
    .first<ChannelRow>();
}

/** The bot's `users` row, shaped as the author `deliverMessage` expects. */
async function botAuthor(env: Env, bot: BotRow): Promise<AuthedUser> {
  const row = await env.DB.prepare(
    'SELECT id, username, email, display_name FROM users WHERE id = ? AND deleted_at IS NULL',
  )
    .bind(bot.user_id)
    .first<{ id: string; username: string; email: string; display_name: string | null }>();
  if (!row) throw new ApiError(404, 'not_found', 'bot user is gone');
  return {
    id: row.id,
    username: row.username,
    email: row.email,
    displayName: row.display_name,
    isAdmin: false,
    isBot: true,
    botId: bot.id,
  };
}

interface BotReply {
  content: string;
  attachment: AttachmentInput | null;
  ephemeral: boolean;
}

/**
 * Normalise a `{ type: "message" }` body from either dispatch path.
 *
 * Throws `ApiError` on anything unusable so the webhook path can turn it into a
 * `failed` interaction and the callback path into a 400 — same validation, two
 * very different consequences.
 */
async function readBotReply(env: Env, bot: BotRow, raw: Record<string, unknown>): Promise<BotReply> {
  const content = String(raw['content'] ?? '').slice(0, MAX_CONTENT);
  const rawAttachment = raw['attachment'] as
    | { key?: string; name?: string; type?: string; size?: number }
    | undefined;
  const attachment = rawAttachment?.key
    ? await assertOwnedAttachment(env, bot.user_id, rawAttachment)
    : null;
  if (!content.trim() && !attachment) {
    throw new ApiError(400, 'empty_message', 'a message response needs content or an attachment');
  }
  return { content, attachment, ephemeral: raw['ephemeral'] === true };
}

/**
 * Post the bot's answer.
 *
 * An ephemeral reply is deliberately NOT a message row: there is no such thing
 * as a message only one member can read in this schema, and inventing one would
 * leak through search, exports and the next page fetch. It is delivered as a
 * gateway event to the invoker alone and never persisted.
 */
async function postBotReply(
  env: Env,
  executionCtx: WaitUntilCtx,
  bot: BotRow,
  interaction: BotInteractionRow,
  reply: BotReply,
): Promise<Record<string, unknown> | null> {
  if (reply.ephemeral) {
    fanout(env, executionCtx, [interaction.user_id], {
      t: 'interaction.response',
      d: {
        interaction_id: interaction.id,
        channel_id: interaction.channel_id,
        bot_id: bot.id,
        command: interaction.command,
        content: reply.content,
        attachment: reply.attachment,
        ephemeral: true,
      },
    });
    return null;
  }
  const channel = await loadChannel(env, interaction.channel_id);
  if (!channel) throw new ApiError(404, 'not_found', 'that channel is gone');
  const author = await botAuthor(env, bot);
  return await deliverMessage(env, executionCtx, channel, author, {
    content: reply.content,
    replyTo: null,
    threadId: null,
    nonce: null,
    attachment: reply.attachment,
    kind: 'text',
    metadata: { interaction_id: interaction.id, command: interaction.command },
    poll: null,
    ttlMs: null,
    encrypted: false,
  });
}

async function markInteraction(
  env: Env,
  id: string,
  state: 'delivered' | 'done' | 'failed',
  fields: { response?: Record<string, unknown> | null; error?: string | null } = {},
): Promise<void> {
  const now = Date.now();
  await env.DB.prepare(
    `UPDATE bot_interactions
     SET state = ?,
         response = COALESCE(?, response),
         error = COALESCE(?, error),
         delivered_at = COALESCE(delivered_at, ?),
         responded_at = CASE WHEN ? IN ('done', 'failed') THEN ? ELSE responded_at END
     WHERE id = ?`,
  )
    .bind(
      state,
      fields.response ? JSON.stringify(fields.response) : null,
      fields.error ?? null,
      now,
      state,
      now,
      id,
    )
    .run();
}

interface DispatchOutcome {
  /** What the interaction row ended up as. */
  state: 'pending' | 'delivered' | 'done' | 'failed';
  /** Echoed to the caller so the client can render the answer immediately. */
  response?: Record<string, unknown>;
  error?: string;
}

function errorText(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  if (err instanceof Error) return err.message;
  return String(err);
}

/**
 * Hand the interaction to the bot.
 *
 * Webhook mode signs the payload and waits up to 3 s. Polling mode does nothing
 * at all — the row is already `pending`, and `/bots/me/updates` will pick it up.
 * Every failure path returns instead of throwing: the invoker's request has
 * already succeeded by this point.
 */
async function dispatchInteraction(
  env: Env,
  executionCtx: WaitUntilCtx,
  bot: BotRow,
  interaction: BotInteractionRow,
  callbackToken: string,
  invoker: AuthedUser,
): Promise<DispatchOutcome> {
  const payload = {
    interaction_id: interaction.id,
    callback_token: callbackToken,
    bot_id: bot.id,
    command: interaction.command,
    options: JSON.parse(interaction.options) as Record<string, unknown>,
    user: { id: invoker.id, username: invoker.username, display_name: invoker.displayName },
    channel_id: interaction.channel_id,
    space_id: interaction.space_id,
    is_dm: interaction.is_dm === 1,
    created_at: interaction.created_at,
  };
  // Polling mode still gets a gateway push: a bot connected over WS answers in
  // milliseconds instead of waiting out the long poll's step. /bots/me/updates
  // stays the safety net for bots that are not connected (and for anything the
  // socket missed), so this is additive, never the only delivery path.
  if (!bot.interactions_url) {
    fanout(env, executionCtx, [bot.user_id], {
      t: 'interaction.create',
      d: payload as unknown as Record<string, unknown>,
    } as never);
    return { state: 'pending' };
  }

  const body = JSON.stringify(payload);
  const timestamp = Date.now();

  let parsed: Record<string, unknown>;
  try {
    const res = await fetch(bot.interactions_url, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-pigeon-signature': await signPayload(bot.signing_secret, timestamp, body),
        'x-pigeon-timestamp': String(timestamp),
        'user-agent': 'pigeonsms-interactions/1',
      },
      body,
      // A bot that cannot answer in 3 s must defer; blocking the invoker's
      // request any longer would make the whole client feel broken.
      signal: AbortSignal.timeout(WEBHOOK_TIMEOUT_MS),
    });
    if (!res.ok) {
      const error = `bot webhook returned ${res.status}`;
      await markInteraction(env, interaction.id, 'failed', { error });
      return { state: 'failed', error };
    }
    parsed = objectBody(await res.json());
  } catch (err) {
    const error = `bot webhook unreachable: ${errorText(err)}`;
    await markInteraction(env, interaction.id, 'failed', { error });
    return { state: 'failed', error };
  }

  const type = String(parsed['type'] ?? '').toLowerCase();
  if (type === 'defer') {
    // Deliberately left open: the bot answers later through the callback, and
    // the sweep expires it if it never does.
    await markInteraction(env, interaction.id, 'delivered');
    return { state: 'delivered', response: { type: 'defer' } };
  }
  if (type === 'noop') {
    await markInteraction(env, interaction.id, 'done', { response: { type: 'noop' } });
    return { state: 'done', response: { type: 'noop' } };
  }
  if (type !== 'message') {
    const error = `bot webhook returned an unknown response type: ${type || '(none)'}`;
    await markInteraction(env, interaction.id, 'failed', { error });
    return { state: 'failed', error };
  }

  try {
    const reply = await readBotReply(env, bot, parsed);
    // The reply is fanned out on its own (it is a real message), so the caller
    // only needs to know the interaction resolved.
    await postBotReply(env, executionCtx, bot, interaction, reply);
    const response = { type: 'message', content: reply.content, ephemeral: reply.ephemeral };
    await markInteraction(env, interaction.id, 'done', { response });
    return { state: 'done', response };
  } catch (err) {
    const error = `bot response rejected: ${errorText(err)}`;
    await markInteraction(env, interaction.id, 'failed', { error });
    return { state: 'failed', error };
  }
}

/**
 * POST /channels/:id/interactions { command, bot_id?, options }
 *
 * Returns `{ interaction, message, response? }`. `response` is present only when
 * a webhook bot answered inline; a polling bot (or a deferring one) leaves it
 * absent and the answer arrives later over the gateway.
 */
interactions.post('/channels/:id/interactions', async (c) => {
  const user = c.get('user') as AuthedUser;
  // A bot invoking a bot is how you build an infinite loop by accident; bots
  // talk with plain messages instead.
  if (user.isBot) throw new ApiError(403, 'forbidden', 'bots cannot invoke commands');
  const channel = await assertChannelAccess(c.env, user.id, c.req.param('id'));
  await enforceRateLimit(c.env.RL_GENERAL, `interaction:${user.id}:${channel.id}`);
  await requirePermission(c.env, user.id, channel.space_id, Permission.SEND_MESSAGES, channel.id);

  const body = await readJsonBody(c);
  const name = String(body['command'] ?? '').trim().toLowerCase().replace(/^\//, '');
  if (!/^[a-z0-9_-]{1,32}$/.test(name)) {
    throw new ApiError(400, 'bad_command', 'that is not a command name');
  }
  const wantedBot = body['bot_id'] ? String(body['bot_id']) : null;

  const candidates = (await channelCommands(c.env, channel, user.id)).filter(
    (row) => row.name === name && (!wantedBot || row.bot_id === wantedBot),
  );
  if (!candidates.length) throw new ApiError(404, 'unknown_command', `no /${name} here`);
  // A bot may register the same name globally AND scoped to this nest; the
  // scoped one is the more specific definition, so it wins.
  const perBot = new Map<string, CommandRow>();
  for (const row of candidates) {
    const existing = perBot.get(row.bot_id);
    if (!existing || (row.space_id !== null && existing.space_id === null)) perBot.set(row.bot_id, row);
  }
  const picked = [...perBot.values()];
  if (picked.length > 1) {
    throw new ApiError(409, 'ambiguous_command', 'several bots offer that command — pass bot_id');
  }
  const command = picked[0] as CommandRow;

  const bot = await loadBot(c.env, command.bot_id);
  if (!bot) throw new ApiError(404, 'unknown_command', `no /${name} here`);

  // Only declared options survive: an undeclared key would ride into the
  // webhook payload unvalidated, and the bot has no schema to check it against.
  const submitted = objectBody(body['options']);
  const defs = parseOptions(command.options);
  const options: Record<string, unknown> = {};
  for (const def of defs) {
    const value = coerceOption(submitted[def.name], def);
    if (value !== undefined) options[def.name] = value;
  }
  // Each option is individually bounded, but 25 of them are not: the blob is
  // stored twice (the interaction row and the message metadata) and shipped in
  // every webhook payload, so cap the whole thing.
  const optionsJson = JSON.stringify(options);
  if (optionsJson.length > MAX_OPTIONS_JSON) {
    throw new ApiError(400, 'options_too_large', 'those options are too long');
  }

  const interactionId = snowflake();
  // The raw callback token is handed out exactly once (in the webhook payload or
  // the poll update) and only its hash is stored, same rule as every other token.
  const callbackToken = `PGI.${interactionId}.${randomSecret()}`;
  const now = Date.now();
  const row: BotInteractionRow = {
    id: interactionId,
    bot_id: bot.id,
    command: command.name,
    options: optionsJson,
    user_id: user.id,
    channel_id: channel.id,
    space_id: channel.space_id,
    is_dm: channel.space_id ? 0 : 1,
    state: 'pending',
    delivery: bot.interactions_url ? 'webhook' : 'poll',
    callback_token_hash: await sha256Hex(callbackToken),
    response: null,
    error: null,
    created_at: now,
    delivered_at: null,
    responded_at: null,
  };
  await c.env.DB.prepare(
    `INSERT INTO bot_interactions
      (id, bot_id, command, options, user_id, channel_id, space_id, is_dm, state, delivery,
       callback_token_hash, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(
      row.id, row.bot_id, row.command, row.options, row.user_id, row.channel_id, row.space_id,
      row.is_dm, row.state, row.delivery, row.callback_token_hash, row.created_at,
    )
    .run();

  // The invocation goes through the normal delivery core, so it gets a seq and
  // fans out exactly like a typed message.
  const message = await deliverMessage(c.env, c.executionCtx, channel, user, {
    content: invocationText(command.name, defs, options),
    replyTo: null,
    threadId: null,
    nonce: body['nonce'] ? String(body['nonce']).slice(0, 64) : null,
    attachment: null,
    kind: COMMAND_KIND,
    metadata: {
      command: command.name,
      options,
      bot_id: bot.id,
      interaction_id: interactionId,
    },
    poll: null,
    ttlMs: null,
    encrypted: false,
  });

  const outcome = await dispatchInteraction(
    c.env, c.executionCtx, bot, row, callbackToken, user,
  );
  row.state = outcome.state;
  row.error = outcome.error ?? null;

  return c.json(
    {
      interaction: serializeInteraction(row),
      message,
      ...(outcome.response ? { response: outcome.response } : {}),
      ...(outcome.error ? { error: outcome.error } : {}),
    },
    201,
  );
});

function requireBot(user: AuthedUser): string {
  if (!user.isBot || !user.botId) {
    throw new ApiError(401, 'bot_token_required', 'this endpoint needs a bot token');
  }
  return user.botId;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

interface PollRow extends BotInteractionRow {
  username: string | null;
  user_display_name: string | null;
}

/**
 * Snowflakes are decimal strings of growing length, so a TEXT `>` would order
 * '9...' after '10...'. Cast both sides for the comparison the caller means.
 */
async function pendingInteractions(env: Env, botId: string, after: string): Promise<PollRow[]> {
  const { results } = await env.DB.prepare(
    `SELECT bi.*, u.username, u.display_name AS user_display_name
     FROM bot_interactions bi
     LEFT JOIN users u ON u.id = bi.user_id
     WHERE bi.bot_id = ? AND bi.state = 'pending' AND bi.delivery = 'poll'
       AND (? = '' OR CAST(bi.id AS INTEGER) > CAST(? AS INTEGER))
     ORDER BY CAST(bi.id AS INTEGER)
     LIMIT ?`,
  )
    .bind(botId, after, after || '0', MAX_UPDATES_PER_POLL)
    .all<PollRow>();
  return results;
}

/**
 * GET /bots/me/updates?after=<id>&wait=<0..25> — Telegram-style long poll.
 *
 * Delivering an update ROTATES the callback token: the raw token minted at
 * invocation went nowhere (a polling bot has no webhook to receive it), and we
 * only ever store the hash, so the only way to hand a polling bot a usable
 * callback credential is to mint a fresh one as it collects the work.
 */
interactions.get('/bots/me/updates', async (c) => {
  const user = c.get('user') as AuthedUser;
  const botId = requireBot(user);
  const after = String(c.req.query('after') ?? '').trim();
  // A non-numeric cursor would CAST to 0 and silently replay everything, which
  // reads as "the server ignored my cursor" — say so instead.
  if (after && !/^[0-9]{1,20}$/.test(after)) {
    throw new ApiError(400, 'bad_cursor', 'after must be an interaction id');
  }
  const waitSeconds = Math.min(
    MAX_POLL_WAIT_S,
    Math.max(0, parseInt(c.req.query('wait') ?? '0', 10) || 0),
  );

  let rows = await pendingInteractions(c.env, botId, after);
  // Poll in short steps rather than one long sleep: a worker request has a wall
  // clock budget, and stepping lets us return the instant work shows up.
  const deadline = Date.now() + waitSeconds * 1000;
  while (!rows.length && Date.now() < deadline) {
    await sleep(POLL_STEP_MS);
    rows = await pendingInteractions(c.env, botId, after);
  }

  const now = Date.now();
  const tokens = new Map<string, string>();
  if (rows.length) {
    const statements = [];
    for (const row of rows) {
      const token = `PGI.${row.id}.${randomSecret()}`;
      tokens.set(row.id, token);
      statements.push(
        c.env.DB.prepare(
          `UPDATE bot_interactions SET state = 'delivered', delivered_at = ?, callback_token_hash = ?
           WHERE id = ? AND state = 'pending'`,
        ).bind(now, await sha256Hex(token), row.id),
      );
    }
    await c.env.DB.batch(statements);
  }

  return c.json({
    updates: rows.map((row) => ({
      ...serializeInteraction(row),
      state: 'delivered',
      delivered_at: now,
      callback_token: tokens.get(row.id),
      user: { id: row.user_id, username: row.username, display_name: row.user_display_name },
    })),
    cursor: rows.at(-1)?.id ?? (after || null),
  });
});

/**
 * POST /interactions/:id/callback — the bot's answer.
 *
 * Two credentials are required: the bot token proves *who* is answering, and
 * `X-Interaction-Token` proves it is answering the interaction it was actually
 * handed. Without the second one, a bot token leak would let anyone answer for
 * any of that bot's interactions.
 */
interactions.post('/interactions/:id/callback', async (c) => {
  const user = c.get('user') as AuthedUser;
  const botId = requireBot(user);
  const presented = c.req.header('x-interaction-token') ?? '';
  if (!presented) throw new ApiError(401, 'missing_interaction_token', 'X-Interaction-Token is required');

  const row = await c.env.DB.prepare('SELECT * FROM bot_interactions WHERE id = ?')
    .bind(c.req.param('id'))
    .first<BotInteractionRow>();
  // Someone else's interaction reads as not-found, same reasoning as the bot
  // lookups: never confirm an id exists to a caller who cannot act on it.
  if (!row || row.bot_id !== botId) throw new ApiError(404, 'not_found', 'no such interaction');
  if (!(await timingSafeEqualStrings(await sha256Hex(presented), row.callback_token_hash))) {
    throw new ApiError(403, 'bad_interaction_token', 'that token does not match this interaction');
  }

  if (row.state === 'done' || row.state === 'failed' || row.state === 'expired') {
    throw new ApiError(409, 'interaction_closed', `this interaction is already ${row.state}`);
  }
  // The sweep runs every 15 minutes, so an interaction can be past its TTL and
  // still say `pending`. Close it here rather than accept a stale answer.
  if (row.created_at + INTERACTION_TTL_MS < Date.now()) {
    await c.env.DB.prepare("UPDATE bot_interactions SET state = 'expired' WHERE id = ?")
      .bind(row.id)
      .run();
    throw new ApiError(409, 'interaction_closed', 'this interaction is already expired');
  }

  const bot = await loadBot(c.env, botId);
  if (!bot) throw new ApiError(404, 'not_found', 'no such bot');

  const body = await readJsonBody(c);
  const type = String(body['type'] ?? '').toLowerCase();

  if (type === 'defer') {
    await markInteraction(c.env, row.id, 'delivered');
    return c.json({ ok: true, interaction: serializeInteraction({ ...row, state: 'delivered' }) });
  }
  if (type === 'noop') {
    await markInteraction(c.env, row.id, 'done', { response: { type: 'noop' } });
    return c.json({ ok: true, interaction: serializeInteraction({ ...row, state: 'done' }) });
  }
  if (type !== 'message') {
    throw new ApiError(400, 'bad_response_type', 'type must be message, defer or noop');
  }

  const reply = await readBotReply(c.env, bot, body);
  const message = await postBotReply(c.env, c.executionCtx, bot, row, reply);
  const response = { type: 'message', content: reply.content, ephemeral: reply.ephemeral };
  await markInteraction(c.env, row.id, 'done', { response });
  return c.json({
    ok: true,
    interaction: serializeInteraction({ ...row, state: 'done' }),
    message,
  });
});

/**
 * Cron sweep: an interaction nobody answered stops being answerable.
 *
 * Both `pending` (never collected) and `delivered` (collected, deferred, then
 * forgotten) expire — otherwise a bot that crashes mid-defer would leave a
 * callback token valid forever.
 */
export async function expireStaleInteractions(env: Env): Promise<number> {
  const cutoff = Date.now() - INTERACTION_TTL_MS;
  const result = await env.DB.prepare(
    `UPDATE bot_interactions SET state = 'expired'
     WHERE state IN ('pending', 'delivered') AND created_at < ?`,
  )
    .bind(cutoff)
    .run();
  return result.meta.changes ?? 0;
}

export default interactions;
