-- 3.0 — bots.
--
-- A bot is a real `users` row with the BOT flag (flags & 1) plus a row here. It
-- has no password and no sessions; it authenticates with a bot token whose only
-- stored form is the SHA-256 of the whole `PGB.<bot_id>.<secret>` string. Making
-- the bot a user is what lets every existing surface — DMs, space membership,
-- messages, reactions, attachments, profiles — work for it unchanged.
--
-- `signing_secret` signs outbound webhook payloads (HMAC-SHA256 over
-- "<timestamp>.<body>"), so a bot author can prove a request came from us.
-- `interactions_url` being NULL is the polling mode: interactions queue up for
-- /bots/me/updates instead of being pushed.
CREATE TABLE IF NOT EXISTS bots (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  owner_id TEXT NOT NULL REFERENCES users(id),
  name TEXT NOT NULL,
  description TEXT,
  token_hash TEXT NOT NULL,
  interactions_url TEXT,
  signing_secret TEXT NOT NULL,
  public INTEGER NOT NULL DEFAULT 0,
  dm_enabled INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER
);
-- One bot per bot-user, and the token hash is the auth lookup key, so both are
-- unique indexes rather than plain ones: the uniqueness is a correctness
-- guarantee (a duplicate token hash would make auth ambiguous), not a hint.
CREATE UNIQUE INDEX IF NOT EXISTS idx_bots_user ON bots(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_bots_token ON bots(token_hash);
CREATE INDEX IF NOT EXISTS idx_bots_owner ON bots(owner_id);

-- Slash commands. `space_id` NULL means global: usable in every nest the bot is
-- a member of, plus DMs when `dm_enabled`. A scoped command exists only in its
-- own nest, which is how a bot ships nest-specific verbs without polluting
-- everyone else's palette.
CREATE TABLE IF NOT EXISTS bot_commands (
  id TEXT PRIMARY KEY,
  bot_id TEXT NOT NULL REFERENCES bots(id),
  space_id TEXT,                        -- NULL = global (every nest the bot is in + DMs)
  name TEXT NOT NULL,                   -- ^[a-z0-9_-]{1,32}$
  description TEXT NOT NULL,
  options TEXT NOT NULL DEFAULT '[]',   -- JSON array of option descriptors
  dm_enabled INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL
);
-- IFNULL(space_id,'') because SQLite treats NULLs as distinct in a unique index,
-- which would otherwise let a bot register /roll globally any number of times.
CREATE UNIQUE INDEX IF NOT EXISTS idx_bot_commands_name ON bot_commands(bot_id, IFNULL(space_id,''), name);
CREATE INDEX IF NOT EXISTS idx_bot_commands_bot ON bot_commands(bot_id);

-- One row per invocation. It outlives the request because a bot may defer and
-- answer later through /interactions/:id/callback, and a polling bot only learns
-- about it on its next /bots/me/updates. `callback_token_hash` is the second
-- factor on that callback: holding the bot token is not enough, the caller also
-- has to present the token handed out with this specific interaction.
CREATE TABLE IF NOT EXISTS bot_interactions (
  id TEXT PRIMARY KEY,
  bot_id TEXT NOT NULL REFERENCES bots(id),
  command TEXT NOT NULL,
  options TEXT NOT NULL DEFAULT '{}',
  user_id TEXT NOT NULL REFERENCES users(id),
  channel_id TEXT NOT NULL,
  space_id TEXT,
  is_dm INTEGER NOT NULL DEFAULT 0,
  state TEXT NOT NULL DEFAULT 'pending', -- pending | delivered | done | failed | expired
  delivery TEXT NOT NULL,                -- webhook | poll
  callback_token_hash TEXT NOT NULL,
  response TEXT,
  error TEXT,
  created_at INTEGER NOT NULL,
  delivered_at INTEGER,
  responded_at INTEGER
);
-- The poll index is (bot, state, created_at) because that is exactly the
-- long-poll query: this bot's pending rows, oldest first. The channel index
-- serves the sweep and per-channel lookups.
CREATE INDEX IF NOT EXISTS idx_bot_interactions_poll ON bot_interactions(bot_id, state, created_at);
CREATE INDEX IF NOT EXISTS idx_bot_interactions_channel ON bot_interactions(channel_id, created_at);

-- Deliberately NOT adding `space_members.added_by`: SQLite cannot guard an
-- ALTER TABLE ADD COLUMN, so a re-run would abort the whole file on a duplicate
-- column. Nothing in the bot flow reads it — who added a bot is recoverable from
-- the audit log — so the column stays out rather than making this migration
-- non-idempotent.
