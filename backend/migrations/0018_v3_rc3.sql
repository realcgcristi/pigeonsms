ALTER TABLE key_envelopes ADD COLUMN key_id TEXT;

CREATE TABLE IF NOT EXISTS channel_key_sets (
  channel_id TEXT PRIMARY KEY,
  key_id TEXT NOT NULL,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_key_envelopes_key_device
  ON key_envelopes(channel_id, key_id, to_device);

CREATE TABLE IF NOT EXISTS device_sync_requests (
  device_id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_device_sync_requests_user
  ON device_sync_requests(user_id, created_at);
