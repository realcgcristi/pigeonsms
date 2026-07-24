-- v07 (app v2.7.0): correctness + performance fixes.
--
-- This migration is additive and idempotent where SQLite allows it. It runs
-- exactly once per deploy pipeline, so the single non-idempotent step
-- (ALTER TABLE ... ADD COLUMN, which SQLite cannot guard with IF NOT EXISTS)
-- is safe. It is strictly NON-DESTRUCTIVE: no rows are deleted or merged.
--
-- Covers:
--   * #54 — messages had no index on author_id, so export / moderation-by-author
--     scanned the whole table. Add a composite (author_id, created_at) index.
--   * #39 — concurrent /dms/open could create duplicate DM channels because there
--     was no dedup key. Add channels.dm_key + a (non-unique for now) index; the
--     application-side fix populates it going forward and prefers the existing
--     channel. DM channels are identified by channels.kind = 'dm' (space_id NULL).

-- #54: author lookup index for messages.
CREATE INDEX IF NOT EXISTS idx_messages_author ON messages(author_id, created_at);

-- #39: DM dedup key. `dm_key` holds the two member user_ids sorted ascending and
-- joined with ':' (e.g. 'aaa:bbb'). The application will INSERT
-- [a, b].sort().join(':') into channels.dm_key. Column name is exactly `dm_key`
-- on table `channels`.
ALTER TABLE channels ADD COLUMN dm_key TEXT;

-- Backfill dm_key for existing 2-member DM channels. DM channels are those with
-- kind = 'dm'. We only backfill channels that have exactly two members, computing
-- MIN(user_id) || ':' || MAX(user_id) from channel_members (which orders the pair
-- ascending, matching [a, b].sort().join(':')). DM channels that somehow do not
-- have exactly two members are left with dm_key NULL (a comment, not a failure) —
-- they can be reconciled by hand later.
UPDATE channels
SET dm_key = (
  SELECT MIN(cm.user_id) || ':' || MAX(cm.user_id)
  FROM channel_members cm
  WHERE cm.channel_id = channels.id
)
WHERE channels.kind = 'dm'
  AND channels.dm_key IS NULL
  AND (
    SELECT COUNT(*) FROM channel_members cm WHERE cm.channel_id = channels.id
  ) = 2;

-- Index on dm_key. Intentionally NON-UNIQUE: if historical duplicate DM pairs
-- already exist, a UNIQUE index would fail to build and abort this migration.
-- The application-side #39 fix prevents new duplicates. Once any historical
-- duplicates are reconciled (verify with e.g.
--   SELECT dm_key, COUNT(*) FROM channels
--   WHERE dm_key IS NOT NULL AND deleted_at IS NULL
--   GROUP BY dm_key HAVING COUNT(*) > 1;
-- ), replace this with a partial UNIQUE index, e.g.
--   CREATE UNIQUE INDEX idx_channels_dm_key_unique
--     ON channels(dm_key) WHERE dm_key IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_channels_dm_key ON channels(dm_key);
