-- v04: rich messages, mentions/notifications, polls, media ownership, and super pins.
-- Existing columns remain authoritative for older clients; new columns are additive.

ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text';
ALTER TABLE messages ADD COLUMN metadata TEXT;
ALTER TABLE messages ADD COLUMN thread_id TEXT;

ALTER TABLE spaces ADD COLUMN description TEXT;
ALTER TABLE spaces ADD COLUMN icon_original_key TEXT;
ALTER TABLE spaces ADD COLUMN icon_square_key TEXT;

ALTER TABLE users ADD COLUMN avatar_original_key TEXT;
ALTER TABLE users ADD COLUMN avatar_square_key TEXT;

CREATE INDEX idx_messages_thread_seq ON messages(channel_id, thread_id, seq);
CREATE INDEX idx_messages_kind_seq ON messages(channel_id, kind, seq);

-- Registry for every new upload. Legacy `m/`, `a/`, and `b/` objects remain
-- readable and attachable by their original uploader while clients migrate.
CREATE TABLE media_objects (
  key TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  purpose TEXT NOT NULL DEFAULT 'attachment',
  content_type TEXT NOT NULL,
  size INTEGER NOT NULL,
  original_key TEXT,
  width INTEGER,
  height INTEGER,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_media_objects_owner ON media_objects(owner_id, created_at);
CREATE INDEX idx_media_objects_original ON media_objects(original_key);

-- Mentions are expanded to concrete recipients at send time. `kind` is either
-- `user` or `everyone`, which preserves why a member received a notification.
CREATE TABLE message_mentions (
  message_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (message_id, user_id)
);
CREATE INDEX idx_message_mentions_user ON message_mentions(user_id, created_at);

CREATE TABLE notifications (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  message_id TEXT,
  channel_id TEXT,
  space_id TEXT,
  actor_id TEXT,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  data TEXT,
  read_at INTEGER,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_notifications_user_created ON notifications(user_id, created_at DESC);
CREATE INDEX idx_notifications_user_unread ON notifications(user_id, read_at, created_at DESC);
CREATE UNIQUE INDEX idx_notifications_message_kind
  ON notifications(user_id, message_id, kind)
  WHERE message_id IS NOT NULL;

CREATE TABLE polls (
  message_id TEXT PRIMARY KEY,
  question TEXT NOT NULL,
  anonymous INTEGER NOT NULL DEFAULT 0,
  multiple_choice INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

CREATE TABLE poll_options (
  id TEXT PRIMARY KEY,
  message_id TEXT NOT NULL,
  position INTEGER NOT NULL,
  text TEXT NOT NULL,
  UNIQUE (message_id, position)
);
CREATE INDEX idx_poll_options_message ON poll_options(message_id, position);

CREATE TABLE poll_votes (
  message_id TEXT NOT NULL,
  option_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (message_id, user_id)
);
CREATE INDEX idx_poll_votes_option ON poll_votes(option_id);

CREATE TABLE super_pins (
  channel_id TEXT PRIMARY KEY,
  message_id TEXT NOT NULL,
  pinned_by TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

-- A dismissal applies only to the message that was current when dismissed, so
-- replacing the Super Pin automatically makes the new one visible.
CREATE TABLE super_pin_dismissals (
  channel_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  message_id TEXT NOT NULL,
  dismissed_at INTEGER NOT NULL,
  PRIMARY KEY (channel_id, user_id)
);
