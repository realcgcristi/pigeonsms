import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { snowflake } from '../lib/ids';
import {
  Permission,
  permissionNames,
  parsePermissions,
  requirePermission,
  resolvePermissions,
} from '../lib/permissions';
import { readJsonBody } from '../lib/validate';
import type { AppEnv, AuthedUser } from '../types';

/**
 * Custom roles and per-channel permission overrides (2.9.5).
 *
 * Mounted under `/spaces` → `/spaces/:id/roles...` and
 * `/spaces/:id/channels/:channelId/overrides`.
 *
 * The pre-2.9.5 owner/admin/member column on `space_members` is untouched and
 * still the base layer; these roles stack on top of it. That means a nest that
 * never opens this screen behaves exactly as it did before, and the old base-role
 * endpoint in spaces.ts keeps working unchanged.
 *
 * ## Privilege escalation
 *
 * The rule enforced throughout: **you cannot grant a permission you do not hold.**
 * Without it, anyone with MANAGE_ROLES could mint a role with MANAGE_NEST, assign
 * it to themselves, and take over the nest. The owner bypasses this because they
 * already hold everything.
 */
const roles = new Hono<AppEnv>();
roles.use(requireAuth);

const MAX_ROLES_PER_NEST = 50;
const COLOR_RE = /^#[0-9a-fA-F]{6}$/;

interface RoleRow {
  id: string;
  space_id: string;
  name: string;
  color: string | null;
  position: number;
  permissions: number;
  created_at: number;
}

function serialize(row: RoleRow) {
  return {
    id: row.id,
    space_id: row.space_id,
    name: row.name,
    color: row.color,
    position: row.position,
    permissions: Number(row.permissions),
    permission_names: permissionNames(Number(row.permissions)),
    created_at: row.created_at,
  };
}

function normalizeName(raw: unknown): string {
  const name = String(raw ?? '').trim();
  if (!name || name.length > 40) throw new ApiError(400, 'bad_name', 'role name is 1-40 chars');
  return name;
}

function normalizeColor(raw: unknown): string | null {
  if (raw === undefined || raw === null || raw === '') return null;
  const color = String(raw).trim();
  if (!COLOR_RE.test(color)) throw new ApiError(400, 'bad_color', 'color must be #rrggbb');
  return color;
}

/**
 * Reject any attempt to hand out bits the actor doesn't have. Owners skip the
 * check — they hold ALL_PERMISSIONS, so the subset test would always pass anyway.
 */
function assertNoEscalation(actorPermissions: number, isOwner: boolean, requested: number): void {
  if (isOwner) return;
  const escalated = requested & ~actorPermissions;
  if (escalated !== 0) {
    throw new ApiError(
      403,
      'escalation',
      `you can't grant permissions you don't have: ${permissionNames(escalated).join(', ')}`,
    );
  }
}

/** GET /spaces/:id/roles — the nest's roles, highest rank first. */
roles.get('/:id/roles', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requirePermission(c.env, user.id, spaceId, Permission.VIEW_CHANNEL);

  const { results } = await c.env.DB.prepare(
    'SELECT * FROM space_roles WHERE space_id = ? ORDER BY position DESC, created_at ASC',
  )
    .bind(spaceId)
    .all<RoleRow>();
  return c.json({ roles: results.map(serialize) });
});

/** GET /spaces/:id/permissions — what the caller may do (optionally in a channel). */
roles.get('/:id/permissions', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const channelId = c.req.query('channel_id') || undefined;
  const resolved = await resolvePermissions(c.env, user.id, spaceId, channelId);
  return c.json({
    role: resolved.role,
    is_owner: resolved.isOwner,
    permissions: resolved.permissions,
    permission_names: permissionNames(resolved.permissions),
  });
});

/** POST /spaces/:id/roles { name, color?, permissions, position? } */
roles.post('/:id/roles', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const actor = await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_ROLES);

  const body = await readJsonBody(c);
  const name = normalizeName(body['name']);
  const color = normalizeColor(body['color']);
  const permissions = parsePermissions(body['permissions'] ?? 0);
  assertNoEscalation(actor.permissions, actor.isOwner, permissions);

  const count = await c.env.DB.prepare('SELECT COUNT(*) AS n FROM space_roles WHERE space_id = ?')
    .bind(spaceId)
    .first<{ n: number }>();
  if (Number(count?.n ?? 0) >= MAX_ROLES_PER_NEST) {
    throw new ApiError(400, 'too_many', `a nest can hold ${MAX_ROLES_PER_NEST} roles`);
  }

  const position = Number.isInteger(body['position']) ? Number(body['position']) : 0;
  const id = snowflake();
  const now = Date.now();
  await c.env.DB.prepare(
    `INSERT INTO space_roles (id, space_id, name, color, position, permissions, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(id, spaceId, name, color, position, permissions, now)
    .run();

  return c.json({
    role: serialize({ id, space_id: spaceId, name, color, position, permissions, created_at: now }),
  }, 201);
});

/** PATCH /spaces/:id/roles/:roleId { name?, color?, permissions?, position? } */
roles.patch('/:id/roles/:roleId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const roleId = c.req.param('roleId');
  const actor = await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_ROLES);

  const existing = await c.env.DB.prepare(
    'SELECT * FROM space_roles WHERE id = ? AND space_id = ?',
  )
    .bind(roleId, spaceId)
    .first<RoleRow>();
  if (!existing) throw new ApiError(404, 'not_found', 'no such role');

  const body = await readJsonBody(c);
  const name = body['name'] === undefined ? existing.name : normalizeName(body['name']);
  const color = body['color'] === undefined ? existing.color : normalizeColor(body['color']);
  const position = Number.isInteger(body['position']) ? Number(body['position']) : existing.position;
  let permissions = Number(existing.permissions);
  if (body['permissions'] !== undefined) {
    permissions = parsePermissions(body['permissions']);
    // Check both directions: granting bits you lack is escalation, and *removing*
    // bits you lack would let a lesser role quietly defang a stronger one.
    assertNoEscalation(actor.permissions, actor.isOwner, permissions);
    assertNoEscalation(actor.permissions, actor.isOwner, Number(existing.permissions));
  }

  await c.env.DB.prepare(
    'UPDATE space_roles SET name = ?, color = ?, position = ?, permissions = ? WHERE id = ? AND space_id = ?',
  )
    .bind(name, color, position, permissions, roleId, spaceId)
    .run();

  return c.json({ role: serialize({ ...existing, name, color, position, permissions }) });
});

/** DELETE /spaces/:id/roles/:roleId — also drops assignments and overrides. */
roles.delete('/:id/roles/:roleId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const roleId = c.req.param('roleId');
  const actor = await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_ROLES);

  const existing = await c.env.DB.prepare(
    'SELECT permissions FROM space_roles WHERE id = ? AND space_id = ?',
  )
    .bind(roleId, spaceId)
    .first<{ permissions: number }>();
  if (!existing) throw new ApiError(404, 'not_found', 'no such role');
  // Deleting a role you couldn't have created is the same escalation in reverse.
  assertNoEscalation(actor.permissions, actor.isOwner, Number(existing.permissions));

  // Assignments and channel overrides are meaningless without the role, and
  // leaving them would silently re-apply if the id were ever reused.
  await c.env.DB.batch([
    c.env.DB.prepare('DELETE FROM space_member_roles WHERE role_id = ?').bind(roleId),
    c.env.DB.prepare('DELETE FROM channel_overrides WHERE role_id = ?').bind(roleId),
    c.env.DB.prepare('DELETE FROM space_roles WHERE id = ? AND space_id = ?').bind(roleId, spaceId),
  ]);
  return c.json({ ok: true });
});

/** PUT /spaces/:id/members/:userId/roles { role_ids: [] } — replace assignments. */
roles.put('/:id/members/:userId/roles', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const targetId = c.req.param('userId');
  const actor = await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_ROLES);

  const member = await c.env.DB.prepare(
    'SELECT 1 FROM space_members WHERE space_id = ? AND user_id = ?',
  )
    .bind(spaceId, targetId)
    .first();
  if (!member) throw new ApiError(404, 'not_found', 'not a member of this nest');

  const body = await readJsonBody(c);
  const roleIds = Array.isArray(body['role_ids']) ? body['role_ids'].map(String) : [];
  if (roleIds.length > MAX_ROLES_PER_NEST) {
    throw new ApiError(400, 'too_many', 'too many roles');
  }

  // Every requested role must exist in THIS nest — otherwise a caller could
  // assign a role id belonging to a nest they don't administer.
  const granted: number[] = [];
  for (const roleId of roleIds) {
    const row = await c.env.DB.prepare(
      'SELECT permissions FROM space_roles WHERE id = ? AND space_id = ?',
    )
      .bind(roleId, spaceId)
      .first<{ permissions: number }>();
    if (!row) throw new ApiError(400, 'bad_role', `role ${roleId} is not in this nest`);
    granted.push(Number(row.permissions));
  }
  assertNoEscalation(actor.permissions, actor.isOwner, granted.reduce((a, b) => a | b, 0));

  const now = Date.now();
  await c.env.DB.batch([
    c.env.DB.prepare('DELETE FROM space_member_roles WHERE space_id = ? AND user_id = ?')
      .bind(spaceId, targetId),
    ...roleIds.map((roleId) => c.env.DB.prepare(
      'INSERT INTO space_member_roles (space_id, user_id, role_id, assigned_at) VALUES (?, ?, ?, ?)',
    ).bind(spaceId, targetId, roleId, now)),
  ]);
  return c.json({ ok: true, role_ids: roleIds });
});

/** GET /spaces/:id/channels/:channelId/overrides */
roles.get('/:id/channels/:channelId/overrides', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_CHANNELS);

  const { results } = await c.env.DB.prepare(
    'SELECT * FROM channel_overrides WHERE channel_id = ? ORDER BY created_at',
  )
    .bind(c.req.param('channelId'))
    .all<{
      id: string; channel_id: string; role_id: string | null;
      user_id: string | null; allow: number; deny: number; created_at: number;
    }>();

  return c.json({
    overrides: results.map((row) => ({
      id: row.id,
      channel_id: row.channel_id,
      role_id: row.role_id,
      user_id: row.user_id,
      allow: Number(row.allow),
      deny: Number(row.deny),
      allow_names: permissionNames(Number(row.allow)),
      deny_names: permissionNames(Number(row.deny)),
      created_at: row.created_at,
    })),
  });
});

/**
 * PUT /spaces/:id/channels/:channelId/overrides { role_id? | user_id?, allow, deny }
 *
 * Upsert: one override per (channel, role) and per (channel, member), enforced by
 * the partial unique indexes in migration 0010.
 */
roles.put('/:id/channels/:channelId/overrides', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  const channelId = c.req.param('channelId');
  const actor = await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_CHANNELS);

  // The channel must belong to this nest; otherwise nest A's admin could rewrite
  // permissions in nest B by passing a foreign channel id.
  const channel = await c.env.DB.prepare(
    'SELECT id FROM channels WHERE id = ? AND space_id = ? AND deleted_at IS NULL',
  )
    .bind(channelId, spaceId)
    .first();
  if (!channel) throw new ApiError(404, 'not_found', 'no such channel in this nest');

  const body = await readJsonBody(c);
  const roleId = body['role_id'] === undefined || body['role_id'] === null
    ? null
    : String(body['role_id']);
  const targetUserId = body['user_id'] === undefined || body['user_id'] === null
    ? null
    : String(body['user_id']);
  if ((roleId === null) === (targetUserId === null)) {
    throw new ApiError(400, 'bad_target', 'set exactly one of role_id or user_id');
  }

  const allow = parsePermissions(body['allow'] ?? 0);
  const deny = parsePermissions(body['deny'] ?? 0);
  if ((allow & deny) !== 0) {
    throw new ApiError(400, 'bad_override', 'a permission cannot be both allowed and denied');
  }
  // Touching bits you don't hold — in either direction — is escalation.
  assertNoEscalation(actor.permissions, actor.isOwner, allow | deny);

  if (roleId) {
    const role = await c.env.DB.prepare('SELECT 1 FROM space_roles WHERE id = ? AND space_id = ?')
      .bind(roleId, spaceId)
      .first();
    if (!role) throw new ApiError(400, 'bad_role', 'role is not in this nest');
  }

  const now = Date.now();
  // Clear then insert: SQLite's ON CONFLICT can't target a partial index, and the
  // pair is small enough that a delete+insert is simpler than emulating an upsert.
  await c.env.DB.batch([
    roleId
      ? c.env.DB.prepare('DELETE FROM channel_overrides WHERE channel_id = ? AND role_id = ?')
          .bind(channelId, roleId)
      : c.env.DB.prepare('DELETE FROM channel_overrides WHERE channel_id = ? AND user_id = ?')
          .bind(channelId, targetUserId),
    c.env.DB.prepare(
      `INSERT INTO channel_overrides (id, channel_id, role_id, user_id, allow, deny, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).bind(snowflake(), channelId, roleId, targetUserId, allow, deny, now),
  ]);
  return c.json({ ok: true });
});

/** DELETE /spaces/:id/channels/:channelId/overrides/:overrideId */
roles.delete('/:id/channels/:channelId/overrides/:overrideId', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('id');
  await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_CHANNELS);

  const result = await c.env.DB.prepare(
    'DELETE FROM channel_overrides WHERE id = ? AND channel_id = ?',
  )
    .bind(c.req.param('overrideId'), c.req.param('channelId'))
    .run();
  if (result.meta.changes === 0) throw new ApiError(404, 'not_found', 'no such override');
  return c.json({ ok: true });
});

export default roles;
