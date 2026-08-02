CREATE TABLE IF NOT EXISTS space_shield_settings (
  space_id TEXT PRIMARY KEY,
  enabled INTEGER NOT NULL DEFAULT 0,
  anti_raid INTEGER NOT NULL DEFAULT 1,
  raid_join_limit INTEGER NOT NULL DEFAULT 12,
  raid_window_seconds INTEGER NOT NULL DEFAULT 60,
  automod_enabled INTEGER NOT NULL DEFAULT 1,
  blocked_terms TEXT NOT NULL DEFAULT '[]',
  block_external_invites INTEGER NOT NULL DEFAULT 1,
  block_spam INTEGER NOT NULL DEFAULT 1,
  mention_limit INTEGER NOT NULL DEFAULT 8,
  default_slowmode_seconds INTEGER NOT NULL DEFAULT 0,
  lockdown INTEGER NOT NULL DEFAULT 0,
  updated_by TEXT,
  updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS channel_shield_settings (
  channel_id TEXT PRIMARY KEY,
  slowmode_seconds INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS space_member_timeouts (
  space_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  until_at INTEGER NOT NULL,
  reason TEXT,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (space_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_space_member_timeouts_until
  ON space_member_timeouts(space_id, until_at);

CREATE TABLE IF NOT EXISTS moderation_reports (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL,
  channel_id TEXT NOT NULL,
  message_id TEXT NOT NULL,
  reporter_id TEXT NOT NULL,
  reported_user_id TEXT NOT NULL,
  category TEXT NOT NULL,
  reason TEXT,
  status TEXT NOT NULL DEFAULT 'open',
  evidence_json TEXT NOT NULL,
  evidence_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  resolved_by TEXT,
  resolved_at INTEGER,
  resolution TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_moderation_reports_reporter_message
  ON moderation_reports(reporter_id, message_id);

CREATE INDEX IF NOT EXISTS idx_moderation_reports_space_status
  ON moderation_reports(space_id, status, created_at);

CREATE TABLE IF NOT EXISTS shield_actions (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL,
  channel_id TEXT,
  user_id TEXT,
  kind TEXT NOT NULL,
  detail TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_shield_actions_space
  ON shield_actions(space_id, created_at);

CREATE TABLE IF NOT EXISTS space_join_events (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_space_join_events_window
  ON space_join_events(space_id, created_at);
