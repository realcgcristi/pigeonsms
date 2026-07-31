ALTER TABLE channels ADD COLUMN category_id TEXT;

CREATE TABLE IF NOT EXISTS channel_categories (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL,
  name TEXT NOT NULL,
  position INTEGER NOT NULL DEFAULT 0,
  collapsed INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  UNIQUE (space_id, name)
);

CREATE INDEX IF NOT EXISTS idx_channel_categories_space ON channel_categories(space_id, position);
CREATE INDEX IF NOT EXISTS idx_channels_category ON channels(category_id);
