-- v06: forum enrichments — tag definitions, per-post tag reference, likes, and a
-- generic "marked" flag whose button label is driven by the tag's mark_label.
-- All additive; older forum posts (no tag, no likes) keep rendering.

-- Tag definitions live per forum channel. `mark_label` opts a tag into the
-- "mark as <label>" affordance; NULL means the tag is not markable.
CREATE TABLE forum_tags (
  id TEXT PRIMARY KEY,
  channel_id TEXT NOT NULL,
  name TEXT NOT NULL,
  mark_label TEXT,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_forum_tags_channel ON forum_tags(channel_id, created_at);

-- A forum post may reference one tag definition, and may be "marked" (e.g.
-- completed/fixed/gooned) when its tag is markable.
ALTER TABLE messages ADD COLUMN forum_tag_id TEXT;
ALTER TABLE messages ADD COLUMN marked_at INTEGER;

-- Likes: one row per (post, user). Any channel member may like a forum post.
CREATE TABLE forum_likes (
  message_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (message_id, user_id)
);
CREATE INDEX idx_forum_likes_message ON forum_likes(message_id);
