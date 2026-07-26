-- 2.9.7 — nest bans.
--
-- Kicking is just removing the membership row (already possible); a ban has to
-- outlive the removal, otherwise the person rejoins with the next invite link.
CREATE TABLE IF NOT EXISTS space_bans (
  space_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  banned_by TEXT NOT NULL,
  reason TEXT,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (space_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_space_bans_user ON space_bans(user_id);
