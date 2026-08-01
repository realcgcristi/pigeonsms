import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { sha256Hex } from '../lib/crypto';
import { snowflake } from '../lib/ids';
import { Permission, requirePermission } from '../lib/permissions';
import { readJsonBody } from '../lib/validate';
import type { AppEnv, AuthedUser, Env } from '../types';

const migrations = new Hono<AppEnv>();
const MIGRATED_USER_FLAG = 2;

interface MigrationBundle {
  format: 'pigeon-migration';
  version: '1.0';
  exported_at: number;
  source: { server: string; space_id: string };
  data: Record<string, Record<string, unknown>[]> & { space: Record<string, unknown>[] };
}

async function rows(env: Env, sql: string, ...binds: unknown[]) {
  return (await env.DB.prepare(sql).bind(...binds).all<Record<string, unknown>>()).results;
}

async function exportBundle(env: Env, spaceId: string): Promise<MigrationBundle> {
  const base = (env.PUBLIC_BASE_URL ?? '').replace(/\/$/, '');
  const [space, members, categories, channels, roles, memberRoles, overrides, emoji, messages, revisions, reactions, pins, threads, followers, forumTags, forumLikes, polls, pollOptions, pollVotes, media] = await Promise.all([
    rows(env, 'SELECT * FROM spaces WHERE id = ? AND deleted_at IS NULL', spaceId),
    rows(env, `SELECT sm.*, u.username, u.display_name, u.avatar_key, u.accent, u.flags
      FROM space_members sm JOIN users u ON u.id = sm.user_id WHERE sm.space_id = ?`, spaceId),
    rows(env, 'SELECT * FROM channel_categories WHERE space_id = ? ORDER BY position, created_at', spaceId),
    rows(env, 'SELECT * FROM channels WHERE space_id = ? AND deleted_at IS NULL ORDER BY created_at', spaceId),
    rows(env, 'SELECT * FROM space_roles WHERE space_id = ? ORDER BY position, created_at', spaceId),
    rows(env, 'SELECT * FROM space_member_roles WHERE space_id = ?', spaceId),
    rows(env, `SELECT co.* FROM channel_overrides co JOIN channels ch ON ch.id = co.channel_id
      WHERE ch.space_id = ?`, spaceId),
    rows(env, 'SELECT * FROM space_emojis WHERE space_id = ? ORDER BY created_at', spaceId),
    rows(env, `SELECT m.* FROM messages m JOIN channels ch ON ch.id = m.channel_id
      WHERE ch.space_id = ? ORDER BY ch.created_at, m.seq`, spaceId),
    rows(env, `SELECT r.* FROM message_revisions r JOIN messages m ON m.id = r.message_id
      JOIN channels ch ON ch.id = m.channel_id WHERE ch.space_id = ?`, spaceId),
    rows(env, `SELECT r.* FROM reactions r JOIN messages m ON m.id = r.message_id
      JOIN channels ch ON ch.id = m.channel_id WHERE ch.space_id = ?`, spaceId),
    rows(env, `SELECT p.* FROM pins p JOIN channels ch ON ch.id = p.channel_id WHERE ch.space_id = ?`, spaceId),
    rows(env, `SELECT t.* FROM threads t JOIN channels ch ON ch.id = t.channel_id WHERE ch.space_id = ?`, spaceId),
    rows(env, `SELECT f.* FROM thread_followers f JOIN threads t ON t.id = f.thread_id
      JOIN channels ch ON ch.id = t.channel_id WHERE ch.space_id = ?`, spaceId),
    rows(env, `SELECT f.* FROM forum_tags f JOIN channels ch ON ch.id = f.channel_id WHERE ch.space_id = ?`, spaceId),
    rows(env, `SELECT l.* FROM forum_likes l JOIN messages m ON m.id = l.message_id
      JOIN channels ch ON ch.id = m.channel_id WHERE ch.space_id = ?`, spaceId),
    rows(env, `SELECT p.* FROM polls p JOIN messages m ON m.id = p.message_id
      JOIN channels ch ON ch.id = m.channel_id WHERE ch.space_id = ?`, spaceId),
    rows(env, `SELECT o.* FROM poll_options o JOIN messages m ON m.id = o.message_id
      JOIN channels ch ON ch.id = m.channel_id WHERE ch.space_id = ?`, spaceId),
    rows(env, `SELECT v.* FROM poll_votes v JOIN messages m ON m.id = v.message_id
      JOIN channels ch ON ch.id = m.channel_id WHERE ch.space_id = ?`, spaceId),
    rows(env, `SELECT DISTINCT mo.*, ? || '/media/' || mo.key AS source_url FROM media_objects mo
      WHERE mo.key IN (
        SELECT m.attachment_key FROM messages m JOIN channels ch ON ch.id = m.channel_id
          WHERE ch.space_id = ? AND m.attachment_key IS NOT NULL
        UNION SELECT se.media_key FROM space_emojis se WHERE se.space_id = ?
        UNION SELECT s.icon_key FROM spaces s WHERE s.id = ? AND s.icon_key IS NOT NULL
        UNION SELECT s.icon_original_key FROM spaces s WHERE s.id = ? AND s.icon_original_key IS NOT NULL
        UNION SELECT s.icon_square_key FROM spaces s WHERE s.id = ? AND s.icon_square_key IS NOT NULL
      )`, base, spaceId, spaceId, spaceId, spaceId, spaceId),
  ]);
  return {
    format: 'pigeon-migration',
    version: '1.0',
    exported_at: Date.now(),
    source: { server: base, space_id: spaceId },
    data: {
      space, members, categories, channels, roles, member_roles: memberRoles,
      overrides, emoji, messages, revisions, reactions, pins, threads, followers,
      forum_tags: forumTags, forum_likes: forumLikes, polls, poll_options: pollOptions,
      poll_votes: pollVotes, media,
    },
  };
}

function parseBundle(value: unknown): MigrationBundle {
  if (!value || typeof value !== 'object') throw new ApiError(400, 'bad_bundle', 'migration bundle required');
  const bundle = value as MigrationBundle;
  if (bundle.format !== 'pigeon-migration' || bundle.version !== '1.0' || !bundle.data?.space?.[0]) {
    throw new ApiError(400, 'bad_bundle', 'unsupported migration bundle');
  }
  if ((bundle.data.channels?.length ?? 0) > 250 || (bundle.data.messages?.length ?? 0) > 100_000) {
    throw new ApiError(413, 'bundle_too_large', 'bundle exceeds migration limits');
  }
  return bundle;
}

async function runBatches(env: Env, statements: D1PreparedStatement[]) {
  for (let index = 0; index < statements.length; index += 75) {
    await env.DB.batch(statements.slice(index, index + 75));
  }
}

function cleanTime(value: unknown, fallback: number) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? Math.floor(number) : fallback;
}

migrations.get('/spaces/:spaceId/migration', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('spaceId');
  await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_NEST);
  const bundle = await exportBundle(c.env, spaceId);
  c.header('Content-Disposition', `attachment; filename="${String(bundle.data.space[0]?.['name'] ?? 'nest').replace(/[^a-z0-9_-]/gi, '_')}.pigeon.json"`);
  return c.json({ bundle, digest: await sha256Hex(JSON.stringify(bundle)) });
});

migrations.post('/spaces/migrate', requireAuth, async (c) => {
  const user = c.get('user') as AuthedUser;
  if (user.isBot) throw new ApiError(403, 'forbidden', 'bots cannot import nests');
  const body = await readJsonBody(c);
  const bundle = parseBundle(body['bundle']);
  const force = body['force'] === true;
  const digest = await sha256Hex(JSON.stringify(bundle));
  const previous = force ? null : await c.env.DB.prepare(
    'SELECT space_id FROM migration_imports WHERE imported_by = ? AND digest = ?',
  ).bind(user.id, digest).first<{ space_id: string }>();
  if (previous) return c.json({ ok: true, duplicate: true, space_id: previous.space_id });

  const sourceSpace = bundle.data.space[0] ?? {};
  const importId = snowflake();
  const spaceId = snowflake();
  const now = Date.now();
  const userIds = new Map<string, string>();
  const categoryIds = new Map<string, string>();
  const channelIds = new Map<string, string>();
  const roleIds = new Map<string, string>();
  const messageIds = new Map<string, string>();
  const threadIds = new Map<string, string>();
  const tagIds = new Map<string, string>();
  const mediaKeys = new Map<string, string>();
  const optionIds = new Map<string, string>();

  const sourceOwner = String(sourceSpace['owner_id'] ?? '');
  userIds.set(sourceOwner, user.id);
  const identityStatements: D1PreparedStatement[] = [];
  for (const member of bundle.data.members ?? []) {
    const sourceId = String(member['user_id'] ?? '');
    if (!sourceId || userIds.has(sourceId)) continue;
    const username = String(member['username'] ?? `legacy_${sourceId.slice(-8)}`).toLowerCase();
    const localId = snowflake();
    userIds.set(sourceId, localId);
    identityStatements.push(c.env.DB.prepare(
      `INSERT INTO users (id, username, email, display_name, password_hash, flags, created_at)
       VALUES (?, ?, ?, ?, '', ?, ?)`,
    ).bind(localId, `legacy_${importId.slice(-6)}_${userIds.size}`.slice(0, 20), `legacy.${localId}@migration.invalid`, String(member['display_name'] ?? member['username'] ?? 'Migrated member').slice(0, 64), MIGRATED_USER_FLAG, now));
    identityStatements.push(c.env.DB.prepare(
      'INSERT INTO migration_identities (import_id, source_user_id, local_user_id, source_username) VALUES (?, ?, ?, ?)',
    ).bind(importId, sourceId, localId, username));
  }
  await runBatches(c.env, identityStatements);

  for (const category of bundle.data.categories ?? []) categoryIds.set(String(category['id']), snowflake());
  for (const channel of bundle.data.channels ?? []) channelIds.set(String(channel['id']), snowflake());
  for (const role of bundle.data.roles ?? []) roleIds.set(String(role['id']), snowflake());
  for (const message of bundle.data.messages ?? []) messageIds.set(String(message['id']), snowflake());
  for (const thread of bundle.data.threads ?? []) threadIds.set(String(thread['id']), snowflake());
  for (const tag of bundle.data.forum_tags ?? []) tagIds.set(String(tag['id']), snowflake());
  for (const option of bundle.data.poll_options ?? []) optionIds.set(String(option['id']), snowflake());
  for (const media of bundle.data.media ?? []) {
    const sourceKey = String(media['key'] ?? '');
    if (sourceKey) mediaKeys.set(sourceKey, `m/${spaceId}/${snowflake()}/${sourceKey.split('/').at(-1) ?? 'file'}`);
  }

  const statements: D1PreparedStatement[] = [
    c.env.DB.prepare(
      `INSERT INTO spaces
       (id, name, description, owner_id, creation_nonce, icon_key, icon_original_key, icon_square_key, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(spaceId, String(body['name'] ?? sourceSpace['name'] ?? 'Migrated nest').trim().slice(0, 48), sourceSpace['description'] ? String(sourceSpace['description']).slice(0, 1000) : null, user.id, `migration:${digest}`,
      sourceSpace['icon_key'] ? mediaKeys.get(String(sourceSpace['icon_key'])) ?? null : null,
      sourceSpace['icon_original_key'] ? mediaKeys.get(String(sourceSpace['icon_original_key'])) ?? null : null,
      sourceSpace['icon_square_key'] ? mediaKeys.get(String(sourceSpace['icon_square_key'])) ?? null : null, now),
    c.env.DB.prepare("INSERT INTO space_members (space_id, user_id, role, joined_at) VALUES (?, ?, 'owner', ?)")
      .bind(spaceId, user.id, now),
    c.env.DB.prepare(
      'INSERT INTO migration_imports (id, space_id, imported_by, source_server, source_space_id, digest, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    ).bind(importId, spaceId, user.id, bundle.source?.server ?? null, bundle.source?.space_id ?? null, digest, now),
  ];

  for (const member of bundle.data.members ?? []) {
    const localId = userIds.get(String(member['user_id']));
    if (!localId || localId === user.id) continue;
    const role = ['admin', 'member'].includes(String(member['role'])) ? String(member['role']) : 'member';
    statements.push(c.env.DB.prepare(
      'INSERT OR IGNORE INTO space_members (space_id, user_id, role, joined_at) VALUES (?, ?, ?, ?)',
    ).bind(spaceId, localId, role, cleanTime(member['joined_at'], now)));
  }
  for (const category of bundle.data.categories ?? []) {
    statements.push(c.env.DB.prepare(
      'INSERT INTO channel_categories (id, space_id, name, position, collapsed, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(categoryIds.get(String(category['id'])), spaceId, String(category['name'] ?? 'category').slice(0, 64), Number(category['position'] ?? 0), category['collapsed'] ? 1 : 0, cleanTime(category['created_at'], now)));
  }
  for (const role of bundle.data.roles ?? []) {
    statements.push(c.env.DB.prepare(
      'INSERT INTO space_roles (id, space_id, name, color, position, permissions, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    ).bind(roleIds.get(String(role['id'])), spaceId, String(role['name'] ?? 'role').slice(0, 48), role['color'] ?? null, Number(role['position'] ?? 0), Number(role['permissions'] ?? 0), cleanTime(role['created_at'], now)));
  }
  for (const channel of bundle.data.channels ?? []) {
    const categoryId = channel['category_id'] ? categoryIds.get(String(channel['category_id'])) ?? null : null;
    statements.push(c.env.DB.prepare(
      `INSERT INTO channels (id, space_id, name, topic, kind, last_seq, category_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(channelIds.get(String(channel['id'])), spaceId, String(channel['name'] ?? 'channel').slice(0, 48), channel['topic'] ? String(channel['topic']).slice(0, 500) : null, ['text', 'voice', 'forum'].includes(String(channel['kind'])) ? channel['kind'] : 'text', Number(channel['last_seq'] ?? 0), categoryId, cleanTime(channel['created_at'], now)));
  }
  for (const assignment of bundle.data.member_roles ?? []) {
    const localUser = userIds.get(String(assignment['user_id']));
    const localRole = roleIds.get(String(assignment['role_id']));
    if (localUser && localRole) statements.push(c.env.DB.prepare(
      'INSERT OR IGNORE INTO space_member_roles (space_id, user_id, role_id, assigned_at) VALUES (?, ?, ?, ?)',
    ).bind(spaceId, localUser, localRole, cleanTime(assignment['assigned_at'], now)));
  }
  for (const override of bundle.data.overrides ?? []) {
    const localChannel = channelIds.get(String(override['channel_id']));
    const localRole = override['role_id'] ? roleIds.get(String(override['role_id'])) : null;
    const localUser = override['user_id'] ? userIds.get(String(override['user_id'])) : null;
    if (localChannel && (localRole || localUser)) statements.push(c.env.DB.prepare(
      'INSERT INTO channel_overrides (id, channel_id, role_id, user_id, allow, deny, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    ).bind(snowflake(), localChannel, localRole, localUser, Number(override['allow'] ?? 0), Number(override['deny'] ?? 0), cleanTime(override['created_at'], now)));
  }
  for (const tag of bundle.data.forum_tags ?? []) {
    const localChannel = channelIds.get(String(tag['channel_id']));
    if (localChannel) statements.push(c.env.DB.prepare(
      'INSERT INTO forum_tags (id, channel_id, name, mark_label, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(tagIds.get(String(tag['id'])), localChannel, String(tag['name'] ?? 'tag').slice(0, 32), tag['mark_label'] ?? null, userIds.get(String(tag['created_by'])) ?? user.id, cleanTime(tag['created_at'], now)));
  }
  for (const item of bundle.data.emoji ?? []) {
    const localMedia = mediaKeys.get(String(item['media_key'] ?? ''));
    if (localMedia) statements.push(c.env.DB.prepare(
      `INSERT INTO space_emojis
       (id, space_id, name, kind, media_key, content_type, animated, created_by, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(snowflake(), spaceId, String(item['name'] ?? 'emoji').slice(0, 32), item['kind'] === 'sticker' ? 'sticker' : 'emoji', localMedia, item['content_type'] ?? null, item['animated'] ? 1 : 0, userIds.get(String(item['created_by'])) ?? user.id, cleanTime(item['created_at'], now)));
  }
  for (const message of bundle.data.messages ?? []) {
    const localChannel = channelIds.get(String(message['channel_id']));
    if (!localChannel) continue;
    statements.push(c.env.DB.prepare(
      `INSERT INTO messages
       (id, channel_id, seq, author_id, content, reply_to, nonce, attachment_key, attachment_name,
        attachment_type, attachment_size, created_at, edited_at, deleted_at, kind, metadata, thread_id,
        forum_tag_id, marked_at, expires_at, encrypted)
       VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(
      messageIds.get(String(message['id'])), localChannel, Number(message['seq'] ?? 0),
      userIds.get(String(message['author_id'])) ?? user.id, String(message['content'] ?? ''),
      message['reply_to'] ? messageIds.get(String(message['reply_to'])) ?? null : null,
      message['attachment_key'] ? mediaKeys.get(String(message['attachment_key'])) ?? null : null,
      message['attachment_name'] ?? null, message['attachment_type'] ?? null, message['attachment_size'] ?? null,
      cleanTime(message['created_at'], now), message['edited_at'] ?? null, message['deleted_at'] ?? null,
      message['kind'] ?? 'text', message['metadata'] ?? null,
      message['thread_id'] ? threadIds.get(String(message['thread_id'])) ?? null : null,
      message['forum_tag_id'] ? tagIds.get(String(message['forum_tag_id'])) ?? null : null,
      message['marked_at'] ?? null, message['expires_at'] ?? null, message['encrypted'] ? 1 : 0,
    ));
  }
  for (const thread of bundle.data.threads ?? []) {
    const localChannel = channelIds.get(String(thread['channel_id']));
    const root = messageIds.get(String(thread['root_message_id']));
    if (localChannel && root) statements.push(c.env.DB.prepare(
      `INSERT INTO threads
       (id, channel_id, root_message_id, title, created_by, reply_count, last_reply_at, created_at, archived_at, kind, expires_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(threadIds.get(String(thread['id'])), localChannel, root, thread['title'] ?? null, userIds.get(String(thread['created_by'])) ?? user.id, Number(thread['reply_count'] ?? 0), thread['last_reply_at'] ?? null, cleanTime(thread['created_at'], now), thread['archived_at'] ?? null, thread['kind'] ?? 'thread', thread['expires_at'] ?? null));
  }
  for (const reaction of bundle.data.reactions ?? []) {
    const messageId = messageIds.get(String(reaction['message_id']));
    const localUser = userIds.get(String(reaction['user_id']));
    if (messageId && localUser) statements.push(c.env.DB.prepare(
      'INSERT OR IGNORE INTO reactions (message_id, user_id, emoji, created_at) VALUES (?, ?, ?, ?)',
    ).bind(messageId, localUser, reaction['emoji'], cleanTime(reaction['created_at'], now)));
  }
  for (const revision of bundle.data.revisions ?? []) {
    const messageId = messageIds.get(String(revision['message_id']));
    if (messageId) statements.push(c.env.DB.prepare(
      'INSERT INTO message_revisions (id, message_id, content, edited_at) VALUES (?, ?, ?, ?)',
    ).bind(snowflake(), messageId, revision['content'] ?? '', cleanTime(revision['edited_at'], now)));
  }
  for (const follower of bundle.data.followers ?? []) {
    const threadId = threadIds.get(String(follower['thread_id']));
    const localUser = userIds.get(String(follower['user_id']));
    if (threadId && localUser) statements.push(c.env.DB.prepare(
      'INSERT OR IGNORE INTO thread_followers (thread_id, user_id, followed_at) VALUES (?, ?, ?)',
    ).bind(threadId, localUser, cleanTime(follower['followed_at'], now)));
  }
  for (const like of bundle.data.forum_likes ?? []) {
    const messageId = messageIds.get(String(like['message_id']));
    const localUser = userIds.get(String(like['user_id']));
    if (messageId && localUser) statements.push(c.env.DB.prepare(
      'INSERT OR IGNORE INTO forum_likes (message_id, user_id, created_at) VALUES (?, ?, ?)',
    ).bind(messageId, localUser, cleanTime(like['created_at'], now)));
  }
  for (const pin of bundle.data.pins ?? []) {
    const channelId = channelIds.get(String(pin['channel_id']));
    const messageId = messageIds.get(String(pin['message_id']));
    if (channelId && messageId) statements.push(c.env.DB.prepare(
      'INSERT OR IGNORE INTO pins (channel_id, message_id, pinned_by, created_at) VALUES (?, ?, ?, ?)',
    ).bind(channelId, messageId, userIds.get(String(pin['pinned_by'])) ?? user.id, cleanTime(pin['created_at'], now)));
  }
  for (const poll of bundle.data.polls ?? []) {
    const messageId = messageIds.get(String(poll['message_id']));
    if (messageId) statements.push(c.env.DB.prepare(
      'INSERT INTO polls (message_id, question, anonymous, multiple_choice, created_at) VALUES (?, ?, ?, ?, ?)',
    ).bind(messageId, poll['question'], poll['anonymous'] ? 1 : 0, poll['multiple_choice'] ? 1 : 0, cleanTime(poll['created_at'], now)));
  }
  for (const option of bundle.data.poll_options ?? []) {
    const messageId = messageIds.get(String(option['message_id']));
    if (messageId) statements.push(c.env.DB.prepare(
      'INSERT INTO poll_options (id, message_id, position, text) VALUES (?, ?, ?, ?)',
    ).bind(optionIds.get(String(option['id'])), messageId, Number(option['position'] ?? 0), option['text']));
  }
  for (const vote of bundle.data.poll_votes ?? []) {
    const messageId = messageIds.get(String(vote['message_id']));
    const optionId = optionIds.get(String(vote['option_id']));
    const localUser = userIds.get(String(vote['user_id']));
    if (messageId && optionId && localUser) statements.push(c.env.DB.prepare(
      'INSERT OR IGNORE INTO poll_votes (message_id, option_id, user_id, created_at) VALUES (?, ?, ?, ?)',
    ).bind(messageId, optionId, localUser, cleanTime(vote['created_at'], now)));
  }
  await runBatches(c.env, statements);

  const mediaTasks = (bundle.data.media ?? []).map(async (media) => {
    const sourceKey = String(media['key'] ?? '');
    const targetKey = mediaKeys.get(sourceKey);
    const sourceUrl = String(media['source_url'] ?? '');
    if (!targetKey || !sourceUrl.startsWith('http')) return false;
    const response = await fetch(sourceUrl);
    if (!response.ok || !response.body) return false;
    await c.env.MEDIA.put(targetKey, response.body, {
      httpMetadata: { contentType: String(media['content_type'] ?? response.headers.get('content-type') ?? 'application/octet-stream') },
      customMetadata: { uploader: user.id, migration: importId },
    });
    await c.env.DB.prepare(
      'INSERT OR IGNORE INTO media_objects (key, owner_id, purpose, content_type, size, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(targetKey, user.id, media['purpose'] ?? 'attachment', media['content_type'] ?? 'application/octet-stream', Number(media['size'] ?? 0), now).run();
    return true;
  });
  c.executionCtx.waitUntil(Promise.allSettled(mediaTasks));
  return c.json({
    ok: true,
    space_id: spaceId,
    digest,
    imported: {
      members: userIds.size,
      channels: channelIds.size,
      messages: messageIds.size,
      media_pending: mediaTasks.length,
    },
  }, 201);
});

export default migrations;
