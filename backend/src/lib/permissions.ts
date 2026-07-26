import { ApiError } from '../middleware/errors';
import type { Env } from '../types';

/**
 * Nest roles and per-channel permission overrides (2.9.5).
 *
 * ## Why a bitfield
 *
 * Permissions are checked on the hot path — every send, pin, edit and delete —
 * so the resolved set has to be cheap. One integer per role means resolving a
 * member is a single query plus a fold, and the answer is an integer AND rather
 * than a row lookup per capability.
 *
 * ## How resolution works
 *
 * 1. Start from the member's **base role** on `space_members` (owner / admin /
 *    member). This is the pre-2.9.5 model and remains the floor, so a nest that
 *    never creates a custom role behaves exactly as it always did.
 * 2. Union in every custom role the member holds (`space_member_roles`).
 * 3. Apply the channel's overrides, least-specific first: role overrides, then a
 *    member-specific override. Within each, **deny is applied after allow**, so a
 *    deny always wins over an allow at the same specificity — the rule people
 *    expect from every other chat app.
 *
 * The owner short-circuits to ALL: a nest must never be able to lock out the
 * person who owns it, which is exactly what a mis-set deny override would
 * otherwise do.
 */

export const Permission = {
  VIEW_CHANNEL: 1 << 0,
  SEND_MESSAGES: 1 << 1,
  ATTACH_FILES: 1 << 2,
  ADD_REACTIONS: 1 << 3,
  MENTION_EVERYONE: 1 << 4,
  MANAGE_MESSAGES: 1 << 5, // delete/pin anyone's message
  MANAGE_CHANNELS: 1 << 6,
  MANAGE_ROLES: 1 << 7,
  MANAGE_EMOJI: 1 << 8,
  MANAGE_NEST: 1 << 9, // rename/icon/description
  KICK_MEMBERS: 1 << 10,
  CREATE_INVITES: 1 << 11,
  CREATE_THREADS: 1 << 12,
  MANAGE_THREADS: 1 << 13, // archive/rename anyone's thread
} as const;

export type PermissionName = keyof typeof Permission;

/** Every bit set — the owner's effective permissions. */
export const ALL_PERMISSIONS = Object.values(Permission).reduce((a, b) => a | b, 0);

/**
 * What a plain member can do out of the box. Deliberately generous: this
 * reproduces the pre-2.9.5 behaviour where any member could talk, react and
 * attach in any channel they could see. Tightening the default would silently
 * change what existing nests permit the moment they upgrade.
 */
export const DEFAULT_MEMBER_PERMISSIONS =
  Permission.VIEW_CHANNEL |
  Permission.SEND_MESSAGES |
  Permission.ATTACH_FILES |
  Permission.ADD_REACTIONS |
  Permission.CREATE_INVITES |
  Permission.CREATE_THREADS;

/** Admins get everything except the owner-only levers. */
export const DEFAULT_ADMIN_PERMISSIONS =
  DEFAULT_MEMBER_PERMISSIONS |
  Permission.MENTION_EVERYONE |
  Permission.MANAGE_MESSAGES |
  Permission.MANAGE_CHANNELS |
  Permission.MANAGE_EMOJI |
  Permission.MANAGE_THREADS |
  Permission.KICK_MEMBERS;

function basePermissions(role: string): number {
  if (role === 'owner') return ALL_PERMISSIONS;
  if (role === 'admin') return DEFAULT_ADMIN_PERMISSIONS;
  return DEFAULT_MEMBER_PERMISSIONS;
}

export interface ResolvedMember {
  /** Base role from space_members. */
  role: string;
  /** Effective permission bits for the channel (or the nest, if no channel). */
  permissions: number;
  isOwner: boolean;
}

/**
 * Resolve what [userId] may do in [channelId] (or nest-wide when the channel is
 * omitted). Throws 403 if they are not a member of the nest at all.
 *
 * A DM channel has no nest and therefore no roles: both participants get the
 * default member set, which is what every pre-2.9.5 DM check assumed.
 */
export async function resolvePermissions(
  env: Env,
  userId: string,
  spaceId: string | null,
  channelId?: string,
): Promise<ResolvedMember> {
  if (!spaceId) {
    return { role: 'member', permissions: DEFAULT_MEMBER_PERMISSIONS, isOwner: false };
  }

  const membership = await env.DB.prepare(
    'SELECT role FROM space_members WHERE space_id = ? AND user_id = ?',
  )
    .bind(spaceId, userId)
    .first<{ role: string }>();
  if (!membership) throw new ApiError(403, 'forbidden', 'not a member');

  const isOwner = membership.role === 'owner';
  if (isOwner) return { role: 'owner', permissions: ALL_PERMISSIONS, isOwner: true };

  let permissions = basePermissions(membership.role);

  // Custom roles are additive: holding a role can only grant, never remove.
  // Removal is what channel overrides are for.
  const { results: roleRows } = await env.DB.prepare(
    `SELECT r.permissions FROM space_member_roles mr
     JOIN space_roles r ON r.id = mr.role_id
     WHERE mr.space_id = ? AND mr.user_id = ?`,
  )
    .bind(spaceId, userId)
    .all<{ permissions: number }>();
  for (const row of roleRows) permissions |= Number(row.permissions ?? 0);

  if (!channelId) return { role: membership.role, permissions, isOwner };

  // Channel overrides. Role-level first, then the member-specific one, so the
  // more specific override is applied last and therefore wins.
  const { results: overrides } = await env.DB.prepare(
    `SELECT co.allow, co.deny, co.user_id
     FROM channel_overrides co
     WHERE co.channel_id = ?
       AND (
         co.user_id = ?
         OR co.role_id IN (SELECT role_id FROM space_member_roles WHERE space_id = ? AND user_id = ?)
       )
     ORDER BY CASE WHEN co.user_id IS NULL THEN 0 ELSE 1 END`,
  )
    .bind(channelId, userId, spaceId, userId)
    .all<{ allow: number; deny: number; user_id: string | null }>();

  for (const override of overrides) {
    // Allow then deny: at equal specificity a deny always beats an allow.
    permissions |= Number(override.allow ?? 0);
    permissions &= ~Number(override.deny ?? 0);
  }

  return { role: membership.role, permissions, isOwner };
}

/** True when [permissions] contains every bit in [required]. */
export function has(permissions: number, required: number): boolean {
  return (permissions & required) === required;
}

/**
 * Resolve and assert in one step. Use this at the top of a handler that mutates
 * something; it throws the same 403 shape the old `requireRole` did, so clients
 * see no behavioural change beyond newly-possible grants.
 */
export async function requirePermission(
  env: Env,
  userId: string,
  spaceId: string | null,
  required: number,
  channelId?: string,
): Promise<ResolvedMember> {
  const resolved = await resolvePermissions(env, userId, spaceId, channelId);
  if (!has(resolved.permissions, required)) {
    throw new ApiError(403, 'forbidden', 'not allowed');
  }
  return resolved;
}

/** Serialise a bitfield into the flag names a client can render. */
export function permissionNames(permissions: number): PermissionName[] {
  return (Object.keys(Permission) as PermissionName[]).filter(
    (name) => (permissions & Permission[name]) !== 0,
  );
}

/**
 * Parse a client-supplied permission value: either a bitfield integer or an
 * array of flag names. Unknown names are rejected rather than ignored — silently
 * dropping a typo'd permission would hand back a role that looks right in the
 * request and behaves differently in practice.
 */
export function parsePermissions(raw: unknown): number {
  if (typeof raw === 'number' && Number.isInteger(raw) && raw >= 0) {
    return raw & ALL_PERMISSIONS;
  }
  if (Array.isArray(raw)) {
    let bits = 0;
    for (const entry of raw) {
      const name = String(entry) as PermissionName;
      if (!(name in Permission)) {
        throw new ApiError(400, 'bad_permission', `unknown permission: ${name}`);
      }
      bits |= Permission[name];
    }
    return bits;
  }
  throw new ApiError(400, 'bad_permission', 'permissions must be an integer or an array of names');
}
