import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { serializeMessages, type MessageRow } from './messages';
import type { AppEnv, AuthedUser } from '../types';

const search = new Hono<AppEnv>();
search.use(requireAuth);

const PAGE = 50;

/**
 * GET /spaces/:id/search?q=&before=
 *
 * Full-text search over a space's plaintext message history. Matches
 * `messages_fts.content` (which the triggers keep populated ONLY for rows that
 * are neither deleted nor encrypted), joins back to `messages`, and constrains
 * to channels of space `:id` that the caller can actually read (i.e. they are a
 * member of the space). Deleted, encrypted, and expired rows are excluded so a
 * stale FTS row can never leak content. Cursor is `created_at`-based: pass the
 * returned `next_before` back as `before` to page older results.
 */
search.get('/:id/search', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');

  const query = (c.req.query('q') ?? '').trim();
  if (!query) return c.json({ results: [], next_before: null });

  // Membership check: only space members may search a space's channels. A
  // non-member (or a deleted space) yields an empty result rather than 403 so
  // search stays quiet about spaces the caller can't see — but since the
  // channel list is also constrained below, an explicit guard keeps intent clear.
  const member = await c.env.DB.prepare(
    `SELECT 1 FROM space_members sm JOIN spaces s ON s.id = sm.space_id
     WHERE sm.space_id = ? AND sm.user_id = ? AND s.deleted_at IS NULL`,
  )
    .bind(spaceId, user.id)
    .first();
  if (!member) throw new ApiError(403, 'forbidden', 'not a member');

  const before = parseInt(c.req.query('before') ?? '', 10);
  const now = Date.now();

  // FTS5 MATCH against messages_fts, joined to the live message rows and their
  // channels. Filters: channel belongs to this (live) space, message is not
  // deleted / not encrypted / not expired. Order newest-first with a
  // created_at cursor; over-fetch by one is unnecessary since we derive the
  // cursor from the last row and only advertise it when the page is full.
  const rows = (
    await c.env.DB.prepare(
      `SELECT m.* FROM messages_fts fts
       JOIN messages m ON m.id = fts.message_id
       JOIN channels ch ON ch.id = m.channel_id AND ch.deleted_at IS NULL
       JOIN spaces s ON s.id = ch.space_id AND s.deleted_at IS NULL
       WHERE messages_fts MATCH ?
         AND s.id = ?
         AND m.deleted_at IS NULL
         AND m.encrypted = 0
         AND (m.expires_at IS NULL OR m.expires_at >= ?)
         ${Number.isInteger(before) ? 'AND m.created_at < ?' : ''}
       ORDER BY m.created_at DESC
       LIMIT ?`,
    )
      .bind(
        ...(Number.isInteger(before)
          ? [query, spaceId, now, before, PAGE]
          : [query, spaceId, now, PAGE]),
      )
      .all<MessageRow>()
  ).results;

  const results = await serializeMessages(c.env, rows, user.id, user.isAdmin);
  const next_before = rows.length === PAGE ? (rows.at(-1)?.created_at ?? null) : null;

  return c.json({ results, next_before });
});

export default search;
