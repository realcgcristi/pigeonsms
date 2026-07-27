-- v08 (app v2.8.0): disappearing messages, full-text search, scheduled sends,
-- multi-device E2EE key transport, and encrypted key backups.
--
-- Additive and idempotent where SQLite allows it (CREATE ... IF NOT EXISTS). The
-- only non-idempotent steps are the two ALTER TABLE ... ADD COLUMN statements,
-- which SQLite cannot guard with IF NOT EXISTS; this migration runs exactly once
-- per deploy pipeline, so that is safe. Strictly NON-DESTRUCTIVE: no rows are
-- deleted or merged.
--
-- Times are unix epoch milliseconds (INTEGER); IDs are snowflake strings —
-- consistent with all prior migrations.

-- ---------------------------------------------------------------------------
-- Disappearing + E2EE flags on messages.
-- ---------------------------------------------------------------------------
-- `expires_at`: when set, the message is soft-deleted by the cron once
-- expires_at < now. Reads must exclude expired rows (expires_at IS NOT NULL AND
-- expires_at < now) in addition to the existing deleted_at filter.
ALTER TABLE messages ADD COLUMN expires_at INTEGER;

-- `encrypted`: 1 means `content` carries base64 E2EE ciphertext the server never
-- decrypts. FTS and push previews must skip encrypted messages.
ALTER TABLE messages ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------------
-- Full-text search over plaintext message bodies (FTS5).
-- ---------------------------------------------------------------------------
-- message_id + channel_id are UNINDEXED (stored, not tokenized) so we can join
-- back to messages and filter by channel without a separate lookup. Only
-- non-deleted, non-encrypted rows are ever mirrored here.
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
  message_id UNINDEXED,
  channel_id UNINDEXED,
  content
);

-- Keep messages_fts in sync. A row belongs in the index only while it is both
-- live (deleted_at IS NULL) and plaintext (encrypted = 0). Because messages_fts
-- is an external-content-less (contentless-ish) plain FTS5 table, we manage rows
-- explicitly rather than via the FTS5 'delete' command pattern: on any change we
-- first purge the existing entry by message_id, then re-insert if it qualifies.

CREATE TRIGGER IF NOT EXISTS messages_fts_ai AFTER INSERT ON messages
BEGIN
  INSERT INTO messages_fts(message_id, channel_id, content)
  SELECT new.id, new.channel_id, new.content
  WHERE new.deleted_at IS NULL AND new.encrypted = 0;
END;

CREATE TRIGGER IF NOT EXISTS messages_fts_au AFTER UPDATE ON messages
BEGIN
  DELETE FROM messages_fts WHERE message_id = old.id;
  INSERT INTO messages_fts(message_id, channel_id, content)
  SELECT new.id, new.channel_id, new.content
  WHERE new.deleted_at IS NULL AND new.encrypted = 0;
END;

CREATE TRIGGER IF NOT EXISTS messages_fts_ad AFTER DELETE ON messages
BEGIN
  DELETE FROM messages_fts WHERE message_id = old.id;
END;

-- Backfill existing plaintext, non-deleted messages. Guarded so re-running the
-- migration cannot create duplicate FTS rows.
INSERT INTO messages_fts(message_id, channel_id, content)
SELECT m.id, m.channel_id, m.content
FROM messages m
WHERE m.deleted_at IS NULL
  AND m.encrypted = 0
  AND NOT EXISTS (
    SELECT 1 FROM messages_fts f WHERE f.message_id = m.id
  );

-- ---------------------------------------------------------------------------
-- Scheduled messages: sends queued for a future send_at, dispatched by cron.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS scheduled_messages (
  id TEXT PRIMARY KEY,
  channel_id TEXT NOT NULL,
  author_id TEXT NOT NULL,
  content TEXT NOT NULL,
  metadata TEXT,
  nonce TEXT,
  encrypted INTEGER NOT NULL DEFAULT 0,
  send_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_scheduled_messages_send_at
  ON scheduled_messages(send_at);

-- ---------------------------------------------------------------------------
-- E2EE device registry: one row per user device identity (X25519 public key).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_devices (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  pub_key TEXT NOT NULL,
  name TEXT,
  created_at INTEGER NOT NULL,
  last_seen INTEGER
);
CREATE INDEX IF NOT EXISTS idx_user_devices_user ON user_devices(user_id);

-- ---------------------------------------------------------------------------
-- Encrypted key backup: password-derived (Argon2id/scrypt) blob for multi-device
-- recovery. One row per user; the server stores it opaquely.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS key_backups (
  user_id TEXT PRIMARY KEY,
  blob TEXT NOT NULL,
  kdf_salt TEXT NOT NULL,
  kdf_params TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- Key envelopes: per-DM symmetric keys wrapped (libsodium sealed box) to each
-- recipient device's public key and delivered out-of-band.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS key_envelopes (
  id TEXT PRIMARY KEY,
  channel_id TEXT NOT NULL,
  to_device TEXT NOT NULL,
  from_user TEXT NOT NULL,
  wrapped_key TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_key_envelopes_channel_device
  ON key_envelopes(channel_id, to_device);
