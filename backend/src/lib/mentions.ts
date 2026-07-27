import { ApiError } from '../middleware/errors';
import type { AuthedUser, Env } from '../types';
import type { ChannelRow } from './channels';
import { parseMentionTokens } from './messageFeatures';

export interface ResolvedMention {
  userId: string;
  kind: 'user' | 'everyone';
}

interface MentionUserRow {
  id: string;
  username: string;
}

async function channelUsers(env: Env, channel: ChannelRow): Promise<MentionUserRow[]> {
  const { results } = channel.space_id
    ? await env.DB.prepare(
        `SELECT u.id, u.username FROM space_members sm
         JOIN users u ON u.id = sm.user_id
         WHERE sm.space_id = ? AND u.deleted_at IS NULL`,
      ).bind(channel.space_id).all<MentionUserRow>()
    : await env.DB.prepare(
        `SELECT u.id, u.username FROM channel_members cm
         JOIN users u ON u.id = cm.user_id
         WHERE cm.channel_id = ? AND u.deleted_at IS NULL`,
      ).bind(channel.id).all<MentionUserRow>();
  return results;
}

/**
 * Members whose lowercased username is one of `usernames`. Filters in SQL so a
 * mention resolution touches only the handful of tagged rows, not the whole
 * space membership. Returns [] for an empty list without querying.
 */
async function channelUsersByName(
  env: Env,
  channel: ChannelRow,
  usernames: string[],
): Promise<MentionUserRow[]> {
  if (usernames.length === 0) return [];
  const placeholders = usernames.map(() => '?').join(', ');
  const { results } = channel.space_id
    ? await env.DB.prepare(
        `SELECT u.id, u.username FROM space_members sm
         JOIN users u ON u.id = sm.user_id
         WHERE sm.space_id = ? AND u.deleted_at IS NULL
           AND LOWER(u.username) IN (${placeholders})`,
      ).bind(channel.space_id, ...usernames).all<MentionUserRow>()
    : await env.DB.prepare(
        `SELECT u.id, u.username FROM channel_members cm
         JOIN users u ON u.id = cm.user_id
         WHERE cm.channel_id = ? AND u.deleted_at IS NULL
           AND LOWER(u.username) IN (${placeholders})`,
      ).bind(channel.id, ...usernames).all<MentionUserRow>();
  return results;
}

/** Members whose username starts with `prefix`, capped for autocomplete. */
async function channelUsersByPrefix(
  env: Env,
  channel: ChannelRow,
  prefix: string,
  limit: number,
): Promise<MentionUserRow[]> {
  const like = `${prefix}%`;
  const { results } = channel.space_id
    ? await env.DB.prepare(
        `SELECT u.id, u.username FROM space_members sm
         JOIN users u ON u.id = sm.user_id
         WHERE sm.space_id = ? AND u.deleted_at IS NULL
           AND LOWER(u.username) LIKE ?
         ORDER BY u.username LIMIT ?`,
      ).bind(channel.space_id, like, limit).all<MentionUserRow>()
    : await env.DB.prepare(
        `SELECT u.id, u.username FROM channel_members cm
         JOIN users u ON u.id = cm.user_id
         WHERE cm.channel_id = ? AND u.deleted_at IS NULL
           AND LOWER(u.username) LIKE ?
         ORDER BY u.username LIMIT ?`,
      ).bind(channel.id, like, limit).all<MentionUserRow>();
  return results;
}

/** Resolve and validate mentions against the actual channel membership. */
export async function resolveMentions(
  env: Env,
  channel: ChannelRow,
  actor: AuthedUser,
  content: string,
): Promise<ResolvedMention[]> {
  const parsed = parseMentionTokens(content);
  if (!parsed.everyone && parsed.usernames.length === 0) return [];

  // @everyone still needs the full roster to fan out to; a plain mention only
  // needs the tagged rows, so filter those in SQL instead of loading everyone.
  const members = parsed.everyone
    ? await channelUsers(env, channel)
    : await channelUsersByName(env, channel, parsed.usernames);
  const byName = new Map(members.map((member) => [member.username.toLowerCase(), member]));
  const invalid = parsed.usernames.filter((username) => !byName.has(username));
  if (invalid.length) {
    throw new ApiError(400, 'invalid_mentions', `not in this channel: ${invalid.join(', ')}`);
  }

  if (parsed.everyone) {
    if (!channel.space_id) {
      throw new ApiError(400, 'space_only', '@everyone is available in spaces only');
    }
    const membership = await env.DB.prepare(
      'SELECT role FROM space_members WHERE space_id = ? AND user_id = ?',
    )
      .bind(channel.space_id, actor.id)
      .first<{ role: string }>();
    if (membership?.role !== 'owner' && membership?.role !== 'admin') {
      throw new ApiError(403, 'mention_forbidden', 'only space admins can use @everyone');
    }
  }

  const resolved = new Map<string, ResolvedMention>();
  if (parsed.everyone) {
    for (const member of members) {
      resolved.set(member.id, { userId: member.id, kind: 'everyone' });
    }
  }
  for (const username of parsed.usernames) {
    const member = byName.get(username);
    if (member && !resolved.has(member.id)) {
      resolved.set(member.id, { userId: member.id, kind: 'user' });
    }
  }
  return [...resolved.values()];
}

export async function autocompleteMentions(
  env: Env,
  channel: ChannelRow,
  query: string,
): Promise<MentionUserRow[]> {
  const q = query.trim().toLowerCase().replaceAll('%', '').replaceAll('_', '').slice(0, 20);
  if (!q) return [];
  // Filter + cap in SQL so a keystroke touches only matching rows, not the whole
  // space membership. Wildcards are already stripped above, so `q%` is a literal
  // prefix match. Over-fetch by one so an exact-order localeCompare still yields
  // a stable top 10 close to the previous client-side sort.
  const users = await channelUsersByPrefix(env, channel, q, 11);
  return users
    .sort((a, b) => a.username.localeCompare(b.username))
    .slice(0, 10);
}
