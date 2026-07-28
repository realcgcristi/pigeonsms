ALTER TABLE users ADD COLUMN about TEXT;
ALTER TABLE users ADD COLUMN accent TEXT;
ALTER TABLE users ADD COLUMN avatar_key TEXT;
ALTER TABLE users ADD COLUMN banner_color TEXT;
ALTER TABLE users ADD COLUMN pronouns TEXT;
ALTER TABLE users ADD COLUMN status_text TEXT;
ALTER TABLE users ADD COLUMN last_online INTEGER;
ALTER TABLE users ADD COLUMN badges TEXT;
ALTER TABLE users ADD COLUMN totp_secret TEXT;
ALTER TABLE users ADD COLUMN totp_enabled INTEGER NOT NULL DEFAULT 0;

CREATE TABLE recovery_codes (
  user_id TEXT NOT NULL,
  code_hash TEXT NOT NULL,
  used_at INTEGER,
  PRIMARY KEY (user_id, code_hash)
);

CREATE TABLE friends (
  requester TEXT NOT NULL,
  addressee TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending', -- pending | accepted
  note TEXT,
  close_friend INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (requester, addressee)
);
CREATE INDEX idx_friends_addressee ON friends(addressee);

CREATE TABLE blocks (
  blocker TEXT NOT NULL,
  blocked TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (blocker, blocked)
);

CREATE TABLE spaces (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  icon_key TEXT,
  created_at INTEGER NOT NULL,
  deleted_at INTEGER
);

CREATE TABLE space_members (
  space_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'member', -- owner | admin | member
  joined_at INTEGER NOT NULL,
  PRIMARY KEY (space_id, user_id)
);
CREATE INDEX idx_space_members_user ON space_members(user_id);

CREATE TABLE space_invites (
  code TEXT PRIMARY KEY,
  space_id TEXT NOT NULL,
  created_by TEXT NOT NULL,
  max_uses INTEGER,
  uses INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER,
  created_at INTEGER NOT NULL
);

CREATE TABLE channels (
  id TEXT PRIMARY KEY,
  space_id TEXT,
  name TEXT,
  topic TEXT,
  kind TEXT NOT NULL DEFAULT 'text', -- text | dm
  last_seq INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  deleted_at INTEGER
);
CREATE INDEX idx_channels_space ON channels(space_id);

CREATE TABLE channel_members (
  channel_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  last_read_seq INTEGER NOT NULL DEFAULT 0,
  joined_at INTEGER NOT NULL,
  PRIMARY KEY (channel_id, user_id)
);
CREATE INDEX idx_channel_members_user ON channel_members(user_id);

CREATE TABLE messages (
  id TEXT PRIMARY KEY,
  channel_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  author_id TEXT NOT NULL,
  content TEXT NOT NULL,
  reply_to TEXT,
  nonce TEXT,
  attachment_key TEXT,
  attachment_name TEXT,
  attachment_type TEXT,
  attachment_size INTEGER,
  created_at INTEGER NOT NULL,
  edited_at INTEGER,
  deleted_at INTEGER
);
CREATE UNIQUE INDEX idx_messages_channel_seq ON messages(channel_id, seq);
CREATE UNIQUE INDEX idx_messages_nonce ON messages(channel_id, author_id, nonce);

CREATE TABLE message_revisions (
  id TEXT PRIMARY KEY,
  message_id TEXT NOT NULL,
  content TEXT NOT NULL,
  edited_at INTEGER NOT NULL
);
CREATE INDEX idx_revisions_message ON message_revisions(message_id);

CREATE TABLE reactions (
  message_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  emoji TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (message_id, user_id, emoji)
);

CREATE TABLE pins (
  channel_id TEXT NOT NULL,
  message_id TEXT NOT NULL,
  pinned_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (channel_id, message_id)
);

CREATE TABLE push_tokens (
  token TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT 'android',
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_push_tokens_user ON push_tokens(user_id);

CREATE TABLE app_releases (
  version_code INTEGER PRIMARY KEY,
  version_name TEXT NOT NULL,
  url TEXT NOT NULL,
  notes TEXT,
  created_at INTEGER NOT NULL
);
