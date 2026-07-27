-- 2.9.5 — the v3 "smaller wants" sweep.
--
-- Adds: per-nest custom emoji + stickers, a real role/permission model with
-- per-channel overrides, threads in ordinary text channels, reminders, and
-- resumable uploads. Everything is additive; no existing table is altered
-- destructively, and every new surface is opt-in so a client that knows nothing
-- about it keeps working exactly as before.

-- ---------------------------------------------------------------------------
-- 1. Custom emoji + stickers, scoped to a nest.
--
-- `media_key` points at a media_objects row uploaded through the normal
-- /media/upload path, so ownership, size caps and content-type sniffing are
-- already enforced by the time we get here. `name` is the :shortcode:, unique
-- per nest. `kind` separates inline emoji from stickers (sent as a whole
-- message) — same storage, different presentation, so one table serves both.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS space_emojis (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL,
  name TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT 'emoji',      -- 'emoji' | 'sticker'
  media_key TEXT NOT NULL,
  content_type TEXT,
  animated INTEGER NOT NULL DEFAULT 0,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_space_emojis_name ON space_emojis(space_id, name);
CREATE INDEX IF NOT EXISTS idx_space_emojis_space ON space_emojis(space_id, kind);

-- ---------------------------------------------------------------------------
-- 2. Roles and permissions.
--
-- Until now a member was owner | admin | member, hardcoded in requireRole().
-- That stays as the *base* role on space_members (so nothing breaks and there is
-- always a fallback), and custom roles layer on top.
--
-- `permissions` is a bitfield — see lib/permissions.ts for the flag values. A
-- bitfield rather than a join table because permission checks happen on every
-- message send: one integer AND beats a second query.
--
-- Ordering: `position` decides which role's colour/name wins in the member list.
-- Higher position = higher rank; the owner implicitly outranks everything.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS space_roles (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL,
  name TEXT NOT NULL,
  color TEXT,
  position INTEGER NOT NULL DEFAULT 0,
  permissions INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_space_roles_space ON space_roles(space_id, position);

CREATE TABLE IF NOT EXISTS space_member_roles (
  space_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  role_id TEXT NOT NULL,
  assigned_at INTEGER NOT NULL,
  PRIMARY KEY (space_id, user_id, role_id)
);
CREATE INDEX IF NOT EXISTS idx_space_member_roles_user ON space_member_roles(space_id, user_id);
CREATE INDEX IF NOT EXISTS idx_space_member_roles_role ON space_member_roles(role_id);

-- Per-channel allow/deny overrides, addressed to either a role or a single
-- member (exactly one of role_id / user_id is set). Deny wins over allow, and
-- a member-specific override wins over a role one — resolved in lib/permissions.ts.
CREATE TABLE IF NOT EXISTS channel_overrides (
  id TEXT PRIMARY KEY,
  channel_id TEXT NOT NULL,
  role_id TEXT,
  user_id TEXT,
  allow INTEGER NOT NULL DEFAULT 0,
  deny INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_channel_overrides_channel ON channel_overrides(channel_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_channel_overrides_role ON channel_overrides(channel_id, role_id)
  WHERE role_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_channel_overrides_user ON channel_overrides(channel_id, user_id)
  WHERE user_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Threads in ordinary text channels.
--
-- Forum channels already thread via messages.thread_id; this generalises it so
-- any message in a text channel can spawn a thread. The row is metadata only —
-- the replies are normal messages carrying thread_id, so they inherit sequencing,
-- fanout, search, E2EE and every other existing behaviour for free.
--
-- reply_count / last_reply_at are denormalised because the thread list renders
-- them for every thread on screen; recomputing with COUNT(*) per thread would be
-- a query per row.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS threads (
  id TEXT PRIMARY KEY,                     -- == the root message id
  channel_id TEXT NOT NULL,
  root_message_id TEXT NOT NULL,
  title TEXT,
  created_by TEXT NOT NULL,
  reply_count INTEGER NOT NULL DEFAULT 0,
  last_reply_at INTEGER,
  created_at INTEGER NOT NULL,
  archived_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_threads_channel ON threads(channel_id, last_reply_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_threads_root ON threads(root_message_id);

-- Who is following a thread (auto-follow on reply) so we can notify them.
CREATE TABLE IF NOT EXISTS thread_followers (
  thread_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  followed_at INTEGER NOT NULL,
  PRIMARY KEY (thread_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_thread_followers_user ON thread_followers(user_id);

-- ---------------------------------------------------------------------------
-- 4. Reminders.
--
-- "remind me about this message at T", plus freeform "remind me to X". Dispatched
-- by the existing cron; delivery is a notification row + push, NOT a message, so
-- a reminder never appears in anyone else's conversation.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reminders (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  message_id TEXT,                         -- optional: the message being remembered
  channel_id TEXT,                         -- optional: where to deep-link
  text TEXT NOT NULL,
  remind_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  fired_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_reminders_due ON reminders(remind_at) WHERE fired_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_reminders_user ON reminders(user_id, remind_at);

-- ---------------------------------------------------------------------------
-- 5. Resumable uploads.
--
-- /media/upload streams the whole body to R2 in one shot, so a dropped
-- connection at 95% means starting over — and the 50 MB cap exists partly
-- because of that. Sessions + parts back an R2 multipart upload: the client
-- uploads chunks independently, retries just the failed chunk, and resumes after
-- an app restart because the session is server-side state.
--
-- `r2_upload_id` is R2's own multipart id; parts record their etag because
-- completing a multipart upload requires handing every part's etag back.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS upload_sessions (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  key TEXT NOT NULL,
  r2_upload_id TEXT NOT NULL,
  filename TEXT,
  content_type TEXT NOT NULL,
  total_size INTEGER NOT NULL,
  part_size INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  completed_at INTEGER,
  aborted_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_upload_sessions_owner ON upload_sessions(owner_id, created_at);
CREATE INDEX IF NOT EXISTS idx_upload_sessions_open ON upload_sessions(created_at)
  WHERE completed_at IS NULL AND aborted_at IS NULL;

CREATE TABLE IF NOT EXISTS upload_parts (
  session_id TEXT NOT NULL,
  part_number INTEGER NOT NULL,
  etag TEXT NOT NULL,
  size INTEGER NOT NULL,
  uploaded_at INTEGER NOT NULL,
  PRIMARY KEY (session_id, part_number)
);
