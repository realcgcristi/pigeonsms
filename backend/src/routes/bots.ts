import { Hono } from 'hono';
import type { Context } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth, invalidateBotCache } from '../middleware/auth';
import { snowflake } from '../lib/ids';
import { fanout } from '../lib/channels';
import { readJsonBody } from '../lib/validate';
import { Permission, has, resolvePermissions } from '../lib/permissions';
import {
  BOT_USER_FLAG,
  MAX_COMMANDS_PER_BOT,
  mintBotToken,
  randomSecret,
  loadBot,
  requireBotOwner,
  validateCommandName,
  validateCommandOptions,
} from '../lib/bots';
import type { BotRow, BotCommandRow } from '../lib/bots';
import type { AppEnv, AuthedUser } from '../types';

/**
 * Owner-facing bot management (3.0), mounted at `/bots`.
 *
 * Everything here except `GET /bots/me` is operated by a human with a session:
 * a bot token authenticates the bot itself, never its owner's control plane, so
 * bot callers are rejected outright rather than being allowed to mint siblings
 * or rotate their own credentials.
 */
const bots = new Hono<AppEnv>();
bots.use(requireAuth);

/**
 * A soft cap, not a business rule: it exists so one account cannot mint bot
 * users (and therefore usernames) without bound.
 */
const MAX_BOTS_PER_OWNER = 25;

interface BotUserFields {
  username: string;
  display_name: string | null;
  avatar_key: string | null;
  avatar_square_key: string | null;
}

function requireHuman(user: AuthedUser): void {
  if (user.isBot) throw new ApiError(403, 'forbidden', 'bots cannot manage bots');
}

/**
 * The bot id behind a bot token, for the `/bots/me/*` routes.
 *
 * A bot author's deploy script knows its token but not its bot id (the id is
 * only ever shown in the owner's dashboard), so every self-service surface has a
 * `/me` spelling that skips the id entirely.
 */
function meBotId(user: AuthedUser): string {
  if (!user.isBot || !user.botId) throw new ApiError(403, 'forbidden', 'bot token required');
  return user.botId;
}

async function requireMeBot(env: AppEnv['Bindings'], user: AuthedUser): Promise<BotRow> {
  const bot = await loadBot(env, meBotId(user));
  if (!bot) throw new ApiError(404, 'not_found', 'bot not found');
  return bot;
}

/**
 * Commands are the one surface a bot may manage with its OWN token: registering
 * them belongs in the bot's deploy script, not in a human's session. Every other
 * bot-management route stays owner-only.
 */
async function requireBotSelfOrOwner(
  env: AppEnv['Bindings'],
  botId: string,
  user: AuthedUser,
): Promise<BotRow> {
  if (user.isBot) {
    if (user.botId !== botId) throw new ApiError(403, 'forbidden', 'not your bot');
    const bot = await loadBot(env, botId);
    if (!bot) throw new ApiError(404, 'not_found', 'bot not found');
    return bot;
  }
  return requireBotOwner(env, botId, user.id);
}

function audit(c: Context<AppEnv>, actor: string, action: string, target: string): void {
  c.executionCtx.waitUntil(
    c.env.DB.prepare(
      'INSERT INTO audit_log (id, actor_id, action, target, created_at) VALUES (?, ?, ?, ?, ?)',
    )
      .bind(snowflake(), actor, action, target, Date.now())
      .run(),
  );
}

function serializeBot(bot: BotRow, botUser: BotUserFields | null) {
  return {
    id: bot.id,
    user_id: bot.user_id,
    owner_id: bot.owner_id,
    name: bot.name,
    description: bot.description,
    interactions_url: bot.interactions_url,
    public: bot.public === 1,
    dm_enabled: bot.dm_enabled === 1,
    encryption_mode: bot.encryption_mode ?? 'none',
    encryption_public_key: bot.encryption_public_key ?? null,
    created_at: bot.created_at,
    updated_at: bot.updated_at,
    username: botUser?.username ?? null,
    display_name: botUser?.display_name ?? null,
    avatar_key: botUser?.avatar_key ?? null,
    avatar_square_key: botUser?.avatar_square_key ?? null,
  };
}

async function botWithUser(c: Context<AppEnv>, bot: BotRow) {
  const botUser = await c.env.DB.prepare(
    'SELECT username, display_name, avatar_key, avatar_square_key FROM users WHERE id = ?',
  )
    .bind(bot.user_id)
    .first<BotUserFields>();
  return serializeBot(bot, botUser);
}

function serializeCommand(row: BotCommandRow) {
  let options: unknown[] = [];
  try {
    const parsed = JSON.parse(row.options ?? '[]') as unknown;
    if (Array.isArray(parsed)) options = parsed;
  } catch {
    // A malformed stored blob renders as a command with no options instead of
    // 500ing the whole list; the owner can repair it with another PUT.
  }
  return {
    id: row.id,
    bot_id: row.bot_id,
    space_id: row.space_id,
    name: row.name,
    description: row.description,
    options,
    dm_enabled: row.dm_enabled === 1,
    created_at: row.created_at,
  };
}

function validateBotName(raw: unknown): string {
  const name = String(raw ?? '').trim().slice(0, 32);
  if (name.length < 2) throw new ApiError(400, 'bad_name', 'name needs at least 2 characters');
  return name;
}

function validateDescription(raw: unknown): string | null {
  if (raw === undefined || raw === null) return null;
  const description = String(raw).trim().slice(0, 500);
  return description || null;
}

/**
 * `interactions_url` is where we POST signed payloads, so http:// is refused
 * outright: the payload carries a callback token, and sending it in the clear
 * would hand anyone on the path the ability to answer as the bot.
 */
function validateInteractionsUrl(raw: unknown): string | null {
  if (raw === undefined || raw === null) return null;
  const value = String(raw).trim();
  if (!value) return null;
  if (value.length > 512) throw new ApiError(400, 'bad_url', 'interactions_url is too long');
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new ApiError(400, 'bad_url', 'interactions_url must be a URL');
  }
  if (parsed.protocol !== 'https:') {
    throw new ApiError(400, 'bad_url', 'interactions_url must be https');
  }
  return parsed.toString();
}

/**
 * Turn a display name into a username a human can never register.
 *
 * Human usernames are `[a-z0-9_.]{3,20}` (see `validateUsername`), so the `-N`
 * de-duplication suffix doubles as an impersonation guard: `weather-2` is not a
 * name any person could have taken first, and the reserved-word list therefore
 * doesn't need a bot-specific copy.
 */
function derivedUsernameBase(name: string): string {
  const base = name
    .toLowerCase()
    .replace(/[^a-z0-9_]+/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_|_$/g, '')
    .slice(0, 16);
  return base || 'bot';
}

async function firstFreeUsername(c: Context<AppEnv>, base: string): Promise<string> {
  const { results } = await c.env.DB.prepare(
    "SELECT username FROM users WHERE username = ? OR username LIKE ? ESCAPE '\\'",
  )
    .bind(base, `${base.replaceAll('_', '\\_')}-%`)
    .all<{ username: string }>();
  const taken = new Set(results.map((row) => row.username.toLowerCase()));
  let username = base;
  for (let n = 2; taken.has(username); n++) username = `${base}-${n}`;
  return username;
}

/** GET /bots — the caller's own bots. */
bots.get('/', async (c) => {
  const user = c.get('user') as AuthedUser;
  requireHuman(user);
  const { results } = await c.env.DB.prepare(
    `SELECT b.id, b.user_id, b.owner_id, b.name, b.description, b.token_hash,
            b.interactions_url, b.signing_secret, b.public, b.dm_enabled,
            b.encryption_mode, b.encryption_public_key,
            b.created_at, b.updated_at, b.deleted_at,
            u.username, u.display_name, u.avatar_key, u.avatar_square_key
     FROM bots b JOIN users u ON u.id = b.user_id
     WHERE b.owner_id = ? AND b.deleted_at IS NULL
     ORDER BY b.created_at`,
  )
    .bind(user.id)
    .all<BotRow & BotUserFields>();
  return c.json({
    bots: results.map((row) =>
      serializeBot(row, {
        username: row.username,
        display_name: row.display_name,
        avatar_key: row.avatar_key,
        avatar_square_key: row.avatar_square_key,
      }),
    ),
  });
});

/**
 * GET /bots/me — identity for the token holder.
 *
 * Registered before `/:id` so the literal path is never swallowed by the param
 * route, and it is the one endpoint here a bot token may call.
 */
bots.get('/me', async (c) => {
  const bot = await requireMeBot(c.env, c.get('user') as AuthedUser);
  return c.json({ bot: await botWithUser(c, bot) });
});



/**
 * POST /bots { name, description?, dm_enabled? }
 *
 * Creates the bot's `users` row alongside the `bots` row: a bot is a user, so
 * every existing surface (DMs, membership, messages, profiles) works for it with
 * no special cases. The raw token is returned here and never again — only its
 * SHA-256 is stored.
 */
bots.post('/', async (c) => {
  const user = c.get('user') as AuthedUser;
  requireHuman(user);
  const body = await readJsonBody(c);
  const name = validateBotName(body['name']);
  const description = validateDescription(body['description']);
  const dmEnabled = body['dm_enabled'] === undefined ? 1 : body['dm_enabled'] ? 1 : 0;
  const encryptionMode = ['local', 'enclave'].includes(String(body['encryption_mode']))
    ? String(body['encryption_mode'])
    : 'none';
  const encryptionPublicKey = body['encryption_public_key'] == null
    ? null
    : String(body['encryption_public_key']).trim().slice(0, 512);
  if (encryptionMode !== 'none' && !encryptionPublicKey) {
    throw new ApiError(400, 'missing_key', 'encrypted bots need an encryption_public_key');
  }

  const owned = await c.env.DB.prepare(
    'SELECT COUNT(*) AS count FROM bots WHERE owner_id = ? AND deleted_at IS NULL',
  )
    .bind(user.id)
    .first<{ count: number }>();
  if (Number(owned?.count ?? 0) >= MAX_BOTS_PER_OWNER) {
    throw new ApiError(409, 'too_many_bots', `at most ${MAX_BOTS_PER_OWNER} bots per account`);
  }

  const botId = snowflake();
  const userId = snowflake();
  const now = Date.now();
  const { token, hash } = await mintBotToken(botId);
  const signingSecret = randomSecret();

  const base = derivedUsernameBase(name);
  let username = await firstFreeUsername(c, base);
  // `users.email` is UNIQUE and a bot has no mailbox. The contract's empty
  // string only fits one row, so a collision falls back to an unroutable
  // per-bot address (.invalid, RFC 6761 — it can never receive mail, and
  // `password_hash = ''` means it can never sign in either way).
  let email = '';
  let suffix = 2;

  // The free-username read above is not atomic with the insert, so a concurrent
  // create can still win the unique index. Retry against the *reported* column
  // rather than blindly, so one conflict never silently rewrites the other field.
  for (let attempt = 0; ; attempt++) {
    try {
      await c.env.DB.batch([
        c.env.DB.prepare(
          `INSERT INTO users (id, username, email, display_name, password_hash, flags, created_at)
           VALUES (?, ?, ?, ?, '', ?, ?)`,
        ).bind(userId, username, email, name, BOT_USER_FLAG, now),
        c.env.DB.prepare(
          `INSERT INTO bots (id, user_id, owner_id, name, description, token_hash,
                             signing_secret, public, dm_enabled, encryption_mode,
                             encryption_public_key, created_at, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)`,
        ).bind(botId, userId, user.id, name, description, hash, signingSecret, dmEnabled,
          encryptionMode, encryptionPublicKey, now, now),
        ...(encryptionPublicKey ? [c.env.DB.prepare(
          `INSERT INTO user_devices (id, user_id, pub_key, name, created_at, last_seen)
           VALUES (?, ?, ?, 'encrypted bot runtime', ?, ?)`,
        ).bind(snowflake(), userId, encryptionPublicKey, now, now)] : []),
      ]);
      break;
    } catch (err) {
      const message = err instanceof Error ? err.message : '';
      if (attempt >= 4 || !message.includes('UNIQUE')) throw err;
      if (message.includes('users.email')) email = `bot.${botId}@bots.invalid`;
      else username = `${base}-${suffix++}`;
    }
  }

  audit(c, user.id, 'bot.create', botId);
  const bot: BotRow = {
    id: botId,
    user_id: userId,
    owner_id: user.id,
    name,
    description,
    token_hash: hash,
    interactions_url: null,
    signing_secret: signingSecret,
    public: 0,
    dm_enabled: dmEnabled,
    encryption_mode: encryptionMode,
    encryption_public_key: encryptionPublicKey,
    created_at: now,
    updated_at: now,
    deleted_at: null,
  };
  return c.json(
    {
      bot: serializeBot(bot, {
        username,
        display_name: name,
        avatar_key: null,
        avatar_square_key: null,
      }),
      token,
      signing_secret: signingSecret,
    },
    201,
  );
});

/**
 * GET /bots/:id — owner only.
 *
 * Carries `signing_secret` because a webhook bot has no other way to obtain the
 * key it needs to verify `X-Pigeon-Signature`; the list endpoint deliberately
 * omits it, so the secret only travels on a single-bot fetch by its owner.
 */
bots.get('/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  requireHuman(user);
  const bot = await requireBotOwner(c.env, c.req.param('id'), user.id);
  return c.json({ bot: await botWithUser(c, bot), signing_secret: bot.signing_secret });
});

/** POST /bots/:id/secret — mint a fresh webhook signing secret. */
bots.post('/:id/secret', async (c) => {
  const user = c.get('user') as AuthedUser;
  requireHuman(user);
  const bot = await requireBotOwner(c.env, c.req.param('id'), user.id);
  const signingSecret = randomSecret();
  await c.env.DB.prepare('UPDATE bots SET signing_secret = ?, updated_at = ? WHERE id = ?')
    .bind(signingSecret, Date.now(), bot.id)
    .run();
  audit(c, user.id, 'bot.secret.rotate', bot.id);
  return c.json({ signing_secret: signingSecret });
});

/** PATCH /bots/:id { name?, description?, interactions_url?, public?, dm_enabled? } */
bots.patch('/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  requireHuman(user);
  const bot = await requireBotOwner(c.env, c.req.param('id'), user.id);
  const body = await readJsonBody(c);

  const sets: string[] = [];
  const binds: unknown[] = [];
  let renamed: string | null = null;
  if (body['name'] !== undefined) {
    renamed = validateBotName(body['name']);
    sets.push('name = ?');
    binds.push(renamed);
  }
  if (body['description'] !== undefined) {
    sets.push('description = ?');
    binds.push(validateDescription(body['description']));
  }
  if (body['interactions_url'] !== undefined) {
    sets.push('interactions_url = ?');
    binds.push(validateInteractionsUrl(body['interactions_url']));
  }
  if (body['public'] !== undefined) {
    sets.push('public = ?');
    binds.push(body['public'] ? 1 : 0);
  }
  if (body['dm_enabled'] !== undefined) {
    sets.push('dm_enabled = ?');
    binds.push(body['dm_enabled'] ? 1 : 0);
  }
  if (body['encryption_mode'] !== undefined || body['encryption_public_key'] !== undefined) {
    const mode = body['encryption_mode'] === undefined ? bot.encryption_mode : String(body['encryption_mode']);
    const key = body['encryption_public_key'] === undefined
      ? bot.encryption_public_key
      : (body['encryption_public_key'] == null ? null : String(body['encryption_public_key']).trim().slice(0, 512));
    if (!['none', 'local', 'enclave'].includes(mode) || (mode !== 'none' && !key)) {
      throw new ApiError(400, 'bad_encryption', 'choose none, local, or enclave and provide a public key');
    }
    sets.push('encryption_mode = ?', 'encryption_public_key = ?');
    binds.push(mode, key);
  }
  if (sets.length === 0) return c.json({ bot: await botWithUser(c, bot) });

  const now = Date.now();
  sets.push('updated_at = ?');
  binds.push(now);
  const statements = [
    c.env.DB.prepare(`UPDATE bots SET ${sets.join(', ')} WHERE id = ? AND deleted_at IS NULL`).bind(
      ...binds,
      bot.id,
    ),
  ];
  // The bot's display name lives on its user row (that is what every member
  // list, message header and profile reads), so a rename has to reach both.
  // The username is deliberately NOT re-derived: it is an identity other people
  // may already have typed into a mention or a script.
  if (renamed !== null) {
    statements.push(
      c.env.DB.prepare('UPDATE users SET display_name = ? WHERE id = ?').bind(renamed, bot.user_id),
    );
  }
  await c.env.DB.batch(statements);

  if (body['encryption_public_key'] !== undefined) {
    const key = body['encryption_public_key'] == null ? null : String(body['encryption_public_key']).trim().slice(0, 512);
    await c.env.DB.prepare("DELETE FROM user_devices WHERE user_id = ? AND name = 'encrypted bot runtime'")
      .bind(bot.user_id).run();
    if (key) {
      await c.env.DB.prepare(
        `INSERT INTO user_devices (id, user_id, pub_key, name, created_at, last_seen)
         VALUES (?, ?, ?, 'encrypted bot runtime', ?, ?)`,
      ).bind(snowflake(), bot.user_id, key, now, now).run();
    }
  }

  const updated = await loadBot(c.env, bot.id);
  if (!updated) throw new ApiError(404, 'not_found', 'bot not found');
  return c.json({ bot: await botWithUser(c, updated) });
});

/** POST /bots/:id/token — rotate; the new raw token is shown exactly once. */
bots.post('/:id/token', async (c) => {
  const user = c.get('user') as AuthedUser;
  requireHuman(user);
  const bot = await requireBotOwner(c.env, c.req.param('id'), user.id);
  const { token, hash } = await mintBotToken(bot.id);
  await c.env.DB.prepare('UPDATE bots SET token_hash = ?, updated_at = ? WHERE id = ?')
    .bind(hash, Date.now(), bot.id)
    .run();
  // Drop the superseded token from this isolate's auth cache immediately; other
  // isolates age it out on their own short TTL.
  invalidateBotCache(bot.token_hash);
  audit(c, user.id, 'bot.token.rotate', bot.id);
  return c.json({ token, signing_secret: bot.signing_secret });
});

/**
 * DELETE /bots/:id — soft-delete the bot and its user row.
 *
 * Memberships and commands are hard-deleted: the bot vanishes from every nest
 * immediately, while the soft-deleted user row keeps its id resolvable so old
 * messages it authored still render.
 */
bots.delete('/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  requireHuman(user);
  const bot = await requireBotOwner(c.env, c.req.param('id'), user.id);
  const now = Date.now();
  await c.env.DB.batch([
    c.env.DB.prepare('UPDATE bots SET deleted_at = ?, updated_at = ? WHERE id = ?').bind(now, now, bot.id),
    c.env.DB.prepare('UPDATE users SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL').bind(now, bot.user_id),
    c.env.DB.prepare('DELETE FROM bot_commands WHERE bot_id = ?').bind(bot.id),
    c.env.DB.prepare('DELETE FROM space_members WHERE user_id = ?').bind(bot.user_id),
    c.env.DB.prepare('DELETE FROM space_member_roles WHERE user_id = ?').bind(bot.user_id),
  ]);
  invalidateBotCache(bot.token_hash);
  audit(c, user.id, 'bot.delete', bot.id);
  return c.json({ ok: true });
});

/** GET /bots/:id/commands */
async function listBotCommands(c: Context<AppEnv>, bot: BotRow) {
  const { results } = await c.env.DB.prepare(
    `SELECT id, bot_id, space_id, name, description, options, dm_enabled, created_at
     FROM bot_commands WHERE bot_id = ? ORDER BY IFNULL(space_id, ''), name`,
  )
    .bind(bot.id)
    .all<BotCommandRow>();
  return c.json({ commands: results.map(serializeCommand) });
}

/** DELETE /bots/:id/commands/:commandId */
async function deleteBotCommand(c: Context<AppEnv>, bot: BotRow) {
  const result = await c.env.DB.prepare('DELETE FROM bot_commands WHERE id = ? AND bot_id = ?')
    .bind(c.req.param('commandId'), bot.id)
    .run();
  if (result.meta.changes === 0) throw new ApiError(404, 'not_found', 'no such command');
  return c.json({ ok: true });
}

async function replaceBotCommands(c: Context<AppEnv>, bot: BotRow) {
  const body = await readJsonBody(c);
  const submitted = body['commands'];
  if (!Array.isArray(submitted)) {
    throw new ApiError(400, 'bad_request', 'commands must be an array');
  }
  if (submitted.length > MAX_COMMANDS_PER_BOT) {
    throw new ApiError(400, 'too_many_commands', `at most ${MAX_COMMANDS_PER_BOT} commands per bot`);
  }

  const now = Date.now();
  const seen = new Set<string>();
  const rows: BotCommandRow[] = [];
  for (const entry of submitted) {
    if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) {
      throw new ApiError(400, 'bad_request', 'each command must be an object');
    }
    const source = entry as Record<string, unknown>;
    const name = validateCommandName(source['name']);
    const description = String(source['description'] ?? '').trim().slice(0, 200);
    if (!description) {
      throw new ApiError(400, 'bad_request', `${name}: description is required`);
    }
    const options = validateCommandOptions(source['options']);

    let spaceId: string | null = null;
    if (source['space_id'] !== undefined && source['space_id'] !== null) {
      spaceId = String(source['space_id']).trim() || null;
    }
    if (spaceId) {
      // A nest-scoped command that the bot cannot reach would never appear in
      // any palette, so refuse it here instead of storing a dead row.
      const member = await c.env.DB.prepare(
        `SELECT 1 FROM space_members sm JOIN spaces s ON s.id = sm.space_id
         WHERE sm.space_id = ? AND sm.user_id = ? AND s.deleted_at IS NULL`,
      )
        .bind(spaceId, bot.user_id)
        .first();
      if (!member) throw new ApiError(400, 'bot_not_in_nest', 'the bot is not in that nest');
    }

    const key = `${spaceId ?? ''}:${name}`;
    if (seen.has(key)) throw new ApiError(409, 'duplicate_command', `duplicate command: ${name}`);
    seen.add(key);

    rows.push({
      id: snowflake(),
      bot_id: bot.id,
      space_id: spaceId,
      name,
      description,
      options: JSON.stringify(options),
      // DM usability is meaningless for a nest-scoped command — there is no nest
      // in a DM — so the scope decides it rather than the submitted flag.
      dm_enabled: spaceId ? 0 : source['dm_enabled'] === undefined || source['dm_enabled'] ? 1 : 0,
      created_at: now,
    });
  }

  await c.env.DB.batch([
    c.env.DB.prepare('DELETE FROM bot_commands WHERE bot_id = ?').bind(bot.id),
    ...rows.map((row) =>
      c.env.DB.prepare(
        `INSERT INTO bot_commands (id, bot_id, space_id, name, description, options, dm_enabled, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        row.id,
        row.bot_id,
        row.space_id,
        row.name,
        row.description,
        row.options,
        row.dm_enabled,
        row.created_at,
      ),
    ),
  ]);
  return c.json({ commands: rows.map(serializeCommand) });
}

/**
 * POST /bots/:id/commands { name, description, options?, space_id?, dm_enabled? }
 *
 * The incremental counterpart to the replace path: it touches exactly one
 * `(space_id, name)` pair and leaves the rest alone, which is what an interactive
 * dashboard edit or a bot that registers a command lazily needs. Identity is the
 * scope+name pair, not the id — re-posting the same name updates in place (and
 * keeps the row id and `created_at`) rather than tripping the unique index.
 */
async function upsertBotCommand(c: Context<AppEnv>, bot: BotRow) {
  const body = await readJsonBody(c);
  const name = validateCommandName(body['name']);
  const description = String(body['description'] ?? '').trim().slice(0, 200);
  if (!description) {
    throw new ApiError(400, 'bad_request', `${name}: description is required`);
  }
  const options = validateCommandOptions(body['options']);

  let spaceId: string | null = null;
  if (body['space_id'] !== undefined && body['space_id'] !== null) {
    spaceId = String(body['space_id']).trim() || null;
  }
  if (spaceId) {
    const member = await c.env.DB.prepare(
      `SELECT 1 FROM space_members sm JOIN spaces s ON s.id = sm.space_id
       WHERE sm.space_id = ? AND sm.user_id = ? AND s.deleted_at IS NULL`,
    )
      .bind(spaceId, bot.user_id)
      .first();
    if (!member) throw new ApiError(400, 'bot_not_in_nest', 'the bot is not in that nest');
  }

  const existing = await c.env.DB.prepare(
    "SELECT id, created_at FROM bot_commands WHERE bot_id = ? AND IFNULL(space_id, '') = ? AND name = ?",
  )
    .bind(bot.id, spaceId ?? '', name)
    .first<{ id: string; created_at: number }>();

  if (!existing) {
    const count = await c.env.DB.prepare(
      'SELECT COUNT(*) AS n FROM bot_commands WHERE bot_id = ?',
    )
      .bind(bot.id)
      .first<{ n: number }>();
    if ((count?.n ?? 0) >= MAX_COMMANDS_PER_BOT) {
      throw new ApiError(400, 'too_many_commands', `at most ${MAX_COMMANDS_PER_BOT} commands per bot`);
    }
  }

  const row: BotCommandRow = {
    id: existing?.id ?? snowflake(),
    bot_id: bot.id,
    space_id: spaceId,
    name,
    description,
    options: JSON.stringify(options),
    // Same rule as the replace path: scope decides DM usability, not the flag.
    dm_enabled: spaceId ? 0 : body['dm_enabled'] === undefined || body['dm_enabled'] ? 1 : 0,
    created_at: existing?.created_at ?? Date.now(),
  };

  if (existing) {
    await c.env.DB.prepare(
      'UPDATE bot_commands SET description = ?, options = ?, dm_enabled = ? WHERE id = ?',
    )
      .bind(row.description, row.options, row.dm_enabled, row.id)
      .run();
  } else {
    await c.env.DB.prepare(
      `INSERT INTO bot_commands (id, bot_id, space_id, name, description, options, dm_enabled, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    )
      .bind(
        row.id,
        row.bot_id,
        row.space_id,
        row.name,
        row.description,
        row.options,
        row.dm_enabled,
        row.created_at,
      )
      .run();
  }
  return c.json({ command: serializeCommand(row) });
}

/**
 * The `/bots/me/*` command surface — identical handlers, no id in the URL, bot
 * token only. Registered before the `/:id` routes so the literal path wins.
 */
bots.get('/me/commands', async (c) => {
  return listBotCommands(c, await requireMeBot(c.env, c.get('user') as AuthedUser));
});

bots.put('/me/commands', async (c) => {
  return replaceBotCommands(c, await requireMeBot(c.env, c.get('user') as AuthedUser));
});

bots.post('/me/commands', async (c) => {
  return upsertBotCommand(c, await requireMeBot(c.env, c.get('user') as AuthedUser));
});

bots.delete('/me/commands/:commandId', async (c) => {
  const bot = await requireMeBot(c.env, c.get('user') as AuthedUser);
  return deleteBotCommand(c, bot);
});

/** GET /bots/me/spaces — nests the bot is in, with its own token. */
bots.get('/me/spaces', async (c) => {
  const bot = await requireMeBot(c.env, c.get('user') as AuthedUser);
  return listBotSpaces(c, bot);
});

bots.get('/:id/commands', async (c) => {
  const user = c.get('user') as AuthedUser;
  return listBotCommands(c, await requireBotSelfOrOwner(c.env, c.req.param('id'), user));
});

bots.put('/:id/commands', async (c) => {
  const user = c.get('user') as AuthedUser;
  return replaceBotCommands(c, await requireBotSelfOrOwner(c.env, c.req.param('id'), user));
});

bots.post('/:id/commands', async (c) => {
  const user = c.get('user') as AuthedUser;
  return upsertBotCommand(c, await requireBotSelfOrOwner(c.env, c.req.param('id'), user));
});

/**
 * PUT /bots/:id/commands { commands: [...] } — replaces the whole set.
 *
 * Replace rather than merge because that is the only shape that is idempotent
 * from a bot author's deploy script: the file in their repo is the truth, and a
 * command they deleted there has to disappear here without a second call.
 */


bots.delete('/:id/commands/:commandId', async (c) => {
  const user = c.get('user') as AuthedUser;
  return deleteBotCommand(c, await requireBotSelfOrOwner(c.env, c.req.param('id'), user));
});

/**
 * Adding or removing a bot is a nest-management action, not a bot-ownership one:
 * the caller has to own the bot *and* be allowed to change who is in the nest.
 * MANAGE_NEST or the nest owner, exactly like renaming it.
 */
async function requireNestManager(c: Context<AppEnv>, spaceId: string, userId: string): Promise<void> {
  const space = await c.env.DB.prepare('SELECT id FROM spaces WHERE id = ? AND deleted_at IS NULL')
    .bind(spaceId)
    .first();
  if (!space) throw new ApiError(404, 'not_found', 'no such nest');
  const resolved = await resolvePermissions(c.env, userId, spaceId);
  if (!resolved.isOwner && !has(resolved.permissions, Permission.MANAGE_NEST)) {
    throw new ApiError(403, 'forbidden', 'not allowed');
  }
}

/** POST /bots/:id/join { space_id } — add the bot to a nest. */
bots.post('/:id/join', async (c) => {
  const user = c.get('user') as AuthedUser;
  requireHuman(user);
  const bot = await requireBotOwner(c.env, c.req.param('id'), user.id);
  const body = await readJsonBody(c);
  const spaceId = String(body['space_id'] ?? '').trim();
  if (!spaceId) throw new ApiError(400, 'bad_request', 'space_id required');
  await requireNestManager(c, spaceId, user.id);

  // A ban has to survive being re-added by an owner's bot, same as it survives
  // an invite link.
  const banned = await c.env.DB.prepare('SELECT 1 FROM space_bans WHERE space_id = ? AND user_id = ?')
    .bind(spaceId, bot.user_id)
    .first();
  if (banned) throw new ApiError(403, 'banned', 'that bot is banned from this nest');

  const already = await c.env.DB.prepare(
    'SELECT 1 FROM space_members WHERE space_id = ? AND user_id = ?',
  )
    .bind(spaceId, bot.user_id)
    .first();
  if (already) return c.json({ space_id: spaceId, joined: false });

  const joinedAt = Date.now();
  await c.env.DB.prepare(
    "INSERT INTO space_members (space_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)",
  )
    .bind(spaceId, bot.user_id, joinedAt)
    .run();
  audit(c, user.id, 'bot.join', spaceId);

  const botUser = await c.env.DB.prepare(
    'SELECT username, display_name, avatar_key, avatar_square_key FROM users WHERE id = ?',
  )
    .bind(bot.user_id)
    .first<BotUserFields>();
  const members = (
    await c.env.DB.prepare('SELECT user_id FROM space_members WHERE space_id = ?')
      .bind(spaceId)
      .all<{ user_id: string }>()
  ).results.map((row) => row.user_id);
  fanout(c, members, {
    t: 'space.join',
    d: {
      space_id: spaceId,
      member: {
        id: bot.user_id,
        username: botUser?.username ?? null,
        display_name: botUser?.display_name ?? bot.name,
        avatar_key: botUser?.avatar_key ?? null,
        avatar_square_key: botUser?.avatar_square_key ?? null,
        role: 'member',
        joined_at: joinedAt,
        is_bot: true,
        bot_id: bot.id,
      },
    },
  });
  return c.json({ space_id: spaceId, joined: true });
});

/** DELETE /bots/:id/spaces/:spaceId — remove the bot from a nest. */
bots.delete('/:id/spaces/:spaceId', async (c) => {
  const user = c.get('user') as AuthedUser;
  requireHuman(user);
  const bot = await requireBotOwner(c.env, c.req.param('id'), user.id);
  const spaceId = c.req.param('spaceId');
  await requireNestManager(c, spaceId, user.id);

  const [removal] = await c.env.DB.batch([
    c.env.DB.prepare('DELETE FROM space_members WHERE space_id = ? AND user_id = ?').bind(
      spaceId,
      bot.user_id,
    ),
    c.env.DB.prepare('DELETE FROM space_member_roles WHERE space_id = ? AND user_id = ?').bind(
      spaceId,
      bot.user_id,
    ),
    // Its nest-scoped commands go with it: they can only ever surface here.
    c.env.DB.prepare('DELETE FROM bot_commands WHERE bot_id = ? AND space_id = ?').bind(
      bot.id,
      spaceId,
    ),
  ]);
  if ((removal?.meta.changes ?? 0) === 0) {
    throw new ApiError(404, 'not_found', 'that bot is not in this nest');
  }
  audit(c, user.id, 'bot.leave', spaceId);

  const members = (
    await c.env.DB.prepare('SELECT user_id FROM space_members WHERE space_id = ?')
      .bind(spaceId)
      .all<{ user_id: string }>()
  ).results.map((row) => row.user_id);
  fanout(c, members, {
    t: 'space.leave',
    d: { space_id: spaceId, user_id: bot.user_id, bot_id: bot.id },
  });
  return c.json({ ok: true });
});

/** GET /bots/:id/spaces — nests the bot is in. */
bots.get('/:id/spaces', async (c) => {
  const user = c.get('user') as AuthedUser;
  requireHuman(user);
  return listBotSpaces(c, await requireBotOwner(c.env, c.req.param('id'), user.id));
});

async function listBotSpaces(c: Context<AppEnv>, bot: BotRow) {
  const { results } = await c.env.DB.prepare(
    `SELECT s.id, s.name, s.icon_key, s.icon_square_key, sm.joined_at
     FROM space_members sm JOIN spaces s ON s.id = sm.space_id
     WHERE sm.user_id = ? AND s.deleted_at IS NULL
     ORDER BY s.name`,
  )
    .bind(bot.user_id)
    .all();
  return c.json({ spaces: results });
}

export default bots;
