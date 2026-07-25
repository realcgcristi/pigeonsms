import { Hono } from 'hono';
import type { Context } from 'hono';
import { requireAuth } from '../middleware/auth';
import { ApiError } from '../middleware/errors';
import { serializeMessages, type MessageRow } from './messages';
import type { AppEnv, AuthedUser } from '../types';

/**
 * Message search (2.8.0, substantially upgraded in 2.9.5).
 *
 * Root-mounted, because it now owns two paths:
 *   - `GET /spaces/:id/search` — within one nest (the original endpoint)
 *   - `GET /search`            — across every nest you're in, plus your DMs
 *
 * ## Query sanitisation (the reason this was rewritten)
 *
 * The 2.8.0 version passed the raw query straight into `MATCH`. FTS5's query
 * language treats `"`, `*`, `:`, `^`, `(`, `)`, `-`, `AND`, `OR`, `NOT` and `NEAR`
 * as syntax, so an ordinary search like `foo"bar`, `C++ (fast)` or even the word
 * `and` on its own raised an FTS syntax error that surfaced as a 500. Every term
 * is now quoted as a literal phrase, which makes any input safe and makes
 * operators behave like the words they look like.
 *
 * Prefix search is preserved deliberately: a trailing `*` on a term becomes an
 * FTS prefix query, because typing `pigeo*` and getting "pigeon" is the one piece
 * of query syntax people actually reach for.
 */
const search = new Hono<AppEnv>();

const PAGE = 50;
const MAX_TERMS = 16;

/**
 * Turn free text into a safe FTS5 MATCH expression.
 *
 * Each whitespace-separated term is wrapped in double quotes (with any embedded
 * quote doubled, which is how FTS5 escapes them), so it matches literally. Terms
 * are ANDed, which is what people expect from a search box. Returns null when
 * nothing searchable survives, so callers can answer with an empty result rather
 * than handing FTS an empty MATCH — itself a syntax error.
 */
export function sanitizeFtsQuery(raw: string): string | null {
  const terms = raw
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, MAX_TERMS)
    .map((term) => {
      const prefix = term.endsWith('*');
      // Strip the marker before escaping so the `*` isn't quoted into a literal.
      const bare = (prefix ? term.slice(0, -1) : term).replace(/"/g, '""');
      if (!bare.trim()) return null;
      return prefix ? `"${bare}"*` : `"${bare}"`;
    })
    .filter((term): term is string => term !== null);

  return terms.length ? terms.join(' AND ') : null;
}

/** Shared shape for both endpoints. */
interface SearchHit extends MessageRow {
  snippet: string | null;
  space_id: string | null;
  channel_name: string | null;
}

async function respond(c: Context<AppEnv>, rows: SearchHit[], user: AuthedUser) {
  const serialized = await serializeMessages(c.env, rows, user.id, user.isAdmin);
  // Graft the search-only fields back on; serializeMessages is deliberately
  // ignorant of them so message rendering stays identical everywhere.
  const results = serialized.map((message, index) => ({
    ...message,
    snippet: rows[index]?.snippet ?? null,
    space_id: rows[index]?.space_id ?? null,
    channel_name: rows[index]?.channel_name ?? null,
  }));
  return c.json({
    results,
    next_before: rows.length === PAGE ? (rows.at(-1)?.created_at ?? null) : null,
  });
}

/**
 * GET /spaces/:id/search?q=&before=&channel_id=&from=
 *
 * Full-text search over one nest. Deleted, encrypted and expired rows are
 * excluded — the FTS triggers already skip them, and the joins re-check so a
 * stale index row can never leak content.
 */
search.get('/spaces/:id/search', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');

  const match = sanitizeFtsQuery(c.req.query('q') ?? '');
  if (!match) return c.json({ results: [], next_before: null });

  const member = await c.env.DB.prepare(
    `SELECT 1 FROM space_members sm JOIN spaces s ON s.id = sm.space_id
     WHERE sm.space_id = ? AND sm.user_id = ? AND s.deleted_at IS NULL`,
  )
    .bind(spaceId, user.id)
    .first();
  if (!member) throw new ApiError(403, 'forbidden', 'not a member');

  const before = parseInt(c.req.query('before') ?? '', 10);
  const channelId = c.req.query('channel_id') || null;
  const from = c.req.query('from') || null;
  const now = Date.now();

  const binds: unknown[] = [match, spaceId, now];
  if (channelId) binds.push(channelId);
  if (from) binds.push(from);
  if (Number.isInteger(before)) binds.push(before);
  binds.push(PAGE);

  const { results } = await c.env.DB.prepare(
    `SELECT m.*, ch.space_id AS space_id, ch.name AS channel_name,
            snippet(messages_fts, 0, '[', ']', '…', 12) AS snippet
     FROM messages_fts fts
     JOIN messages m ON m.id = fts.message_id
     JOIN channels ch ON ch.id = m.channel_id AND ch.deleted_at IS NULL
     JOIN spaces s ON s.id = ch.space_id AND s.deleted_at IS NULL
     WHERE messages_fts MATCH ?
       AND s.id = ?
       AND m.deleted_at IS NULL
       AND m.encrypted = 0
       AND (m.expires_at IS NULL OR m.expires_at >= ?)
       ${channelId ? 'AND m.channel_id = ?' : ''}
       ${from ? 'AND m.author_id = ?' : ''}
       ${Number.isInteger(before) ? 'AND m.created_at < ?' : ''}
     ORDER BY m.created_at DESC
     LIMIT ?`,
  )
    .bind(...binds)
    .all<SearchHit>();

  return respond(c, results, user);
});

/**
 * GET /search?q=&before=&from=
 *
 * Everything the caller can read: every nest they belong to, plus every DM they
 * are a member of. Scoping lives in the WHERE clause rather than a pre-fetched
 * channel list, so a user in a hundred nests still costs one query.
 */
search.get('/search', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;

  const match = sanitizeFtsQuery(c.req.query('q') ?? '');
  if (!match) return c.json({ results: [], next_before: null });

  const before = parseInt(c.req.query('before') ?? '', 10);
  const from = c.req.query('from') || null;
  const now = Date.now();

  // Membership is expressed twice because the two channel kinds are governed by
  // different tables: nest channels by space_members, DMs by channel_members.
  const binds: unknown[] = [match, now, user.id, user.id];
  if (from) binds.push(from);
  if (Number.isInteger(before)) binds.push(before);
  binds.push(PAGE);

  const { results } = await c.env.DB.prepare(
    `SELECT m.*, ch.space_id AS space_id, ch.name AS channel_name,
            snippet(messages_fts, 0, '[', ']', '…', 12) AS snippet
     FROM messages_fts fts
     JOIN messages m ON m.id = fts.message_id
     JOIN channels ch ON ch.id = m.channel_id AND ch.deleted_at IS NULL
     LEFT JOIN spaces s ON s.id = ch.space_id
     WHERE messages_fts MATCH ?
       AND m.deleted_at IS NULL
       AND m.encrypted = 0
       AND (m.expires_at IS NULL OR m.expires_at >= ?)
       AND (
         (ch.space_id IS NOT NULL AND s.deleted_at IS NULL
            AND ch.space_id IN (SELECT space_id FROM space_members WHERE user_id = ?))
         OR
         (ch.space_id IS NULL
            AND ch.id IN (SELECT channel_id FROM channel_members WHERE user_id = ?))
       )
       ${from ? 'AND m.author_id = ?' : ''}
       ${Number.isInteger(before) ? 'AND m.created_at < ?' : ''}
     ORDER BY m.created_at DESC
     LIMIT ?`,
  )
    .bind(...binds)
    .all<SearchHit>();

  return respond(c, results, user);
});

export default search;
