-- 2.9.0 — referential-integrity cleanup (BUGS_AND_ISSUES #53).
--
-- ## The soft-delete contract (documented here because it is the actual design)
--
-- SQLite/D1 does not enforce the few `REFERENCES` clauses this schema declares
-- (`PRAGMA foreign_keys` is per-connection and D1 does not turn it on), and every
-- top-level entity deletes *softly*:
--
--   users.deleted_at, spaces.deleted_at, channels.deleted_at, messages.deleted_at
--
-- Nothing is ever hard-deleted at the top level, so there are no true orphans by
-- design — but dependent rows *linger* forever: memberships in a demolished nest,
-- push tokens and sessions for a deleted account, key envelopes addressed to a
-- device that no longer exists. That is what #53 flagged: not corruption, but an
-- unbounded pile of rows that no query will ever legitimately return.
--
-- 2.9.0 answers it the way the audit prescribed — document the contract and add
-- explicit cleanup — in three parts:
--   1. this one-time sweep of what has already accumulated,
--   2. `lib/purge.ts#purgeUserData`, called when an account is deleted, and
--   3. `lib/purge.ts#sweepLingeringRows`, run from the existing cron trigger.
--
-- Every statement below is idempotent and deletes only rows whose parent row is
-- absent outright. Rows belonging to soft-deleted-but-present parents are left to
-- the cron sweep, which applies a grace period.

-- ---------------------------------------------------------------------------
-- 1. Rows whose owning user row is gone entirely (only reachable via manual DB
--    surgery today, but these are exactly the orphans a future hard delete would
--    create, and leaving them would silently grant access by user-id collision).
-- ---------------------------------------------------------------------------
DELETE FROM sessions WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM login_history WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM push_tokens WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM recovery_codes WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM notification_preferences WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM notifications WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM space_members WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM channel_members WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM reactions WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM poll_votes WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM forum_likes WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM message_mentions WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM super_pin_dismissals WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM scheduled_messages WHERE author_id NOT IN (SELECT id FROM users);
DELETE FROM user_devices WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM key_backups WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM friends
  WHERE requester NOT IN (SELECT id FROM users)
     OR addressee NOT IN (SELECT id FROM users);
DELETE FROM blocks
  WHERE blocker NOT IN (SELECT id FROM users)
     OR blocked NOT IN (SELECT id FROM users);

-- ---------------------------------------------------------------------------
-- 2. Rows whose owning container/message is gone entirely.
-- ---------------------------------------------------------------------------
DELETE FROM channel_members WHERE channel_id NOT IN (SELECT id FROM channels);
DELETE FROM space_members WHERE space_id NOT IN (SELECT id FROM spaces);
DELETE FROM forum_tags WHERE channel_id NOT IN (SELECT id FROM channels);
DELETE FROM scheduled_messages WHERE channel_id NOT IN (SELECT id FROM channels);
DELETE FROM reactions WHERE message_id NOT IN (SELECT id FROM messages);
DELETE FROM message_mentions WHERE message_id NOT IN (SELECT id FROM messages);
DELETE FROM message_revisions WHERE message_id NOT IN (SELECT id FROM messages);
DELETE FROM forum_likes WHERE message_id NOT IN (SELECT id FROM messages);
DELETE FROM polls WHERE message_id NOT IN (SELECT id FROM messages);
DELETE FROM poll_options WHERE message_id NOT IN (SELECT id FROM messages);
DELETE FROM poll_votes WHERE option_id NOT IN (SELECT id FROM poll_options);
DELETE FROM pins WHERE message_id NOT IN (SELECT id FROM messages);
DELETE FROM super_pins WHERE message_id NOT IN (SELECT id FROM messages);

-- E2EE envelopes addressed to a device identity that no longer exists can never
-- be unwrapped by anyone — they are pure dead weight.
DELETE FROM key_envelopes WHERE to_device NOT IN (SELECT id FROM user_devices);

-- ---------------------------------------------------------------------------
-- 3. Indexes the purge/sweep paths need.
--
-- Without these, deleting one account's rows is a full table scan per table —
-- fine at today's size, quadratic as the instance grows, and the cron sweep runs
-- these same predicates on every tick.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_reactions_user ON reactions(user_id);
CREATE INDEX IF NOT EXISTS idx_poll_votes_user ON poll_votes(user_id);
CREATE INDEX IF NOT EXISTS idx_forum_likes_user ON forum_likes(user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_notification_prefs_user ON notification_preferences(user_id);
CREATE INDEX IF NOT EXISTS idx_recovery_codes_user ON recovery_codes(user_id);
CREATE INDEX IF NOT EXISTS idx_blocks_blocked ON blocks(blocked);
CREATE INDEX IF NOT EXISTS idx_friends_requester ON friends(requester);
CREATE INDEX IF NOT EXISTS idx_super_pin_dismissals_user ON super_pin_dismissals(user_id);
CREATE INDEX IF NOT EXISTS idx_scheduled_messages_author ON scheduled_messages(author_id);
CREATE INDEX IF NOT EXISTS idx_key_envelopes_device ON key_envelopes(to_device);
CREATE INDEX IF NOT EXISTS idx_channel_members_channel ON channel_members(channel_id);
CREATE INDEX IF NOT EXISTS idx_space_members_space ON space_members(space_id);
CREATE INDEX IF NOT EXISTS idx_channels_deleted ON channels(deleted_at);
CREATE INDEX IF NOT EXISTS idx_spaces_deleted ON spaces(deleted_at);
