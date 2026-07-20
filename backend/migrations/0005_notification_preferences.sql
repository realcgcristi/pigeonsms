CREATE TABLE IF NOT EXISTS notification_preferences (
  user_id TEXT NOT NULL,
  scope_type TEXT NOT NULL, -- global | user | channel | space
  scope_id TEXT NOT NULL DEFAULT '',
  mode TEXT NOT NULL DEFAULT 'all', -- all | mentions | mute
  sound INTEGER NOT NULL DEFAULT 1,
  vibration INTEGER NOT NULL DEFAULT 1,
  badge INTEGER NOT NULL DEFAULT 1,
  quiet_start TEXT,
  quiet_end TEXT,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, scope_type, scope_id)
);
CREATE INDEX IF NOT EXISTS idx_notification_preferences_user
  ON notification_preferences(user_id, scope_type, scope_id);
