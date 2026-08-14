-- v3.0.1: app-wide "PigeonSMS" emoji every user can use everywhere, independent
-- of nest membership — a deliberately separate table rather than space_emojis
-- with a fake space_id, so none of the nest-membership/permission plumbing
-- (space_members joins, UNIQUE(space_id, name), MANAGE_EMOJI checks) has to
-- special-case a row that doesn't really belong to a nest.
CREATE TABLE IF NOT EXISTS global_emojis (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  kind TEXT NOT NULL DEFAULT 'emoji',
  media_key TEXT NOT NULL,
  content_type TEXT NOT NULL DEFAULT 'image/png',
  animated INTEGER NOT NULL DEFAULT 0,
  created_by TEXT NOT NULL DEFAULT 'system',
  created_at INTEGER NOT NULL
);

INSERT INTO global_emojis (id, name, media_key, created_at) VALUES
  ('ge_cold', 'cold', 'ge/cold.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_angel', 'angel', 'ge/angel.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_sweaty', 'sweaty', 'ge/sweaty.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_shock', 'shock', 'ge/shock.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_programmer', 'programmer', 'ge/programmer.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_hot', 'hot', 'ge/hot.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_cry', 'cry', 'ge/cry.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_inlove', 'inlove', 'ge/inlove.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_happy', 'happy', 'ge/happy.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_dead', 'dead', 'ge/dead.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_content', 'content', 'ge/content.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_confused', 'confused', 'ge/confused.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_reallyhappy', 'reallyhappy', 'ge/reallyhappy.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_angry', 'angry', 'ge/angry.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_angrier', 'angrier', 'ge/angrier.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_unamused', 'unamused', 'ge/unamused.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_thinking', 'thinking', 'ge/thinking.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_surprised', 'surprised', 'ge/surprised.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_sob', 'sob', 'ge/sob.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_sad', 'sad', 'ge/sad.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_rollingeyes', 'rollingeyes', 'ge/rollingeyes.png', CAST(strftime('%s','now') AS INTEGER) * 1000),
  ('ge_larpingthelogo', 'larpingthelogo', 'ge/larpingthelogo.png', CAST(strftime('%s','now') AS INTEGER) * 1000);
