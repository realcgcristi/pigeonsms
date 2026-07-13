import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import type { AppEnv, AuthedUser } from '../types';

const users = new Hono<AppEnv>();
users.use(requireAuth);

const HEX = /^#[0-9a-fA-F]{6}$/;

users.get('/search', async (c) => {
  const q = (c.req.query('q') ?? '').trim().toLowerCase();
  if (q.length < 2) return c.json({ users: [] });
  const { results } = await c.env.DB.prepare(
    `SELECT id, username, display_name, avatar_key, avatar_original_key, avatar_square_key, accent FROM users
     WHERE username LIKE ? AND deleted_at IS NULL LIMIT 10`,
  )
    .bind(`${q.replaceAll('%', '')}%`)
    .all();
  return c.json({ users: results });
});

users.get('/:id/profile', async (c) => {
  const viewer = c.get('user') as AuthedUser;
  const profileId = c.req.param('id');
  const row = await c.env.DB.prepare(
    `SELECT id, username, display_name, about, accent, avatar_key,
            avatar_original_key, avatar_square_key, banner_key, banner_color, pronouns,
            status_text, badges, last_online, created_at
     FROM users WHERE id = ? AND deleted_at IS NULL`,
  )
    .bind(profileId)
    .first();
  if (!row) throw new ApiError(404, 'not_found', 'no such user');
  const mutualSpaces = (
    await c.env.DB.prepare(
      `SELECT s.id, s.name, s.description, s.icon_key, s.icon_square_key,
              (SELECT COUNT(*) FROM space_members x WHERE x.space_id = s.id) AS member_count
       FROM spaces s
       JOIN space_members target ON target.space_id = s.id AND target.user_id = ?
       JOIN space_members viewer ON viewer.space_id = s.id AND viewer.user_id = ?
       WHERE s.deleted_at IS NULL ORDER BY s.name LIMIT 100`,
    )
      .bind(profileId, viewer.id)
      .all()
  ).results;
  let badges: unknown[] = [];
  try {
    const parsed = JSON.parse((row['badges'] as string) ?? '[]') as unknown;
    if (Array.isArray(parsed)) badges = parsed;
  } catch {
    // malformed stored value renders as no badges instead of a 500
  }
  return c.json({
    profile: { ...row, badges },
    mutual_spaces: mutualSpaces.map((space) => ({ ...space, member_count: Number(space['member_count']) })),
  });
});

users.patch('/me', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({}) as Record<string, unknown>);

  const fields: Record<string, { max: number; hex?: boolean }> = {
    display_name: { max: 48 },
    about: { max: 500 },
    accent: { max: 7, hex: true },
    banner_color: { max: 7, hex: true },
    pronouns: { max: 32 },
    status_text: { max: 80 },
  };
  const sets: string[] = [];
  const binds: unknown[] = [];
  for (const [field, rule] of Object.entries(fields)) {
    if (body[field] === undefined) continue;
    const value = body[field] === null ? null : String(body[field]).slice(0, rule.max);
    if (value && rule.hex && !HEX.test(value)) {
      throw new ApiError(400, 'bad_color', `${field} must be #rrggbb`);
    }
    sets.push(`${field} = ?`);
    binds.push(value);
  }
  if (sets.length === 0) return c.json({ ok: true });
  await c.env.DB.prepare(`UPDATE users SET ${sets.join(', ')} WHERE id = ?`)
    .bind(...binds, user.id)
    .run();
  return c.json({ ok: true });
});

export default users;
