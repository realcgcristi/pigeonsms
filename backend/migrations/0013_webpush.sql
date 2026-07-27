-- v3: browser push subscriptions.
--
-- Payloads are deliberately NOT encrypted into the push message: we send a bare
-- "tickle" and the service worker fetches the notification over the normal API.
-- That keeps message content off third-party push endpoints entirely, and keeps
-- the worker free of an aes128gcm implementation.
CREATE TABLE IF NOT EXISTS web_push_subscriptions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  endpoint TEXT NOT NULL,
  p256dh TEXT NOT NULL,
  auth TEXT NOT NULL,
  user_agent TEXT,
  created_at INTEGER NOT NULL,
  last_used INTEGER,
  failures INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_web_push_endpoint ON web_push_subscriptions(endpoint);
CREATE INDEX IF NOT EXISTS idx_web_push_user ON web_push_subscriptions(user_id);
