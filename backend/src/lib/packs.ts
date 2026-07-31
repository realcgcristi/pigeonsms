import { BOT_USER_FLAG, mintBotToken, randomSecret } from './bots';
import { sha256Hex } from './crypto';
import { snowflake } from './ids';
import type { Env } from '../types';

export interface PigeonPackManifest {
  format: 'pigeon-pack';
  version: '1.0';
  name: string;
  description: string | null;
  categories: Record<string, unknown>[];
  channels: Record<string, unknown>[];
  roles: Record<string, unknown>[];
  overrides: Record<string, unknown>[];
  bots: Record<string, unknown>[];
  theme: Record<string, unknown> | null;
}

export async function packFromSpace(env: Env, spaceId: string): Promise<PigeonPackManifest | null> {
  const space = await env.DB.prepare('SELECT id, name, description FROM spaces WHERE id = ? AND deleted_at IS NULL')
    .bind(spaceId).first<Record<string, unknown>>();
  if (!space) return null;
  const [categories, channels, roles, overrides, bots, commands] = await Promise.all([
    env.DB.prepare('SELECT id, name, position, collapsed FROM channel_categories WHERE space_id = ? ORDER BY position, created_at').bind(spaceId).all(),
    env.DB.prepare('SELECT id, name, topic, kind, category_id FROM channels WHERE space_id = ? AND deleted_at IS NULL ORDER BY created_at').bind(spaceId).all(),
    env.DB.prepare('SELECT id, name, color, position, permissions FROM space_roles WHERE space_id = ? ORDER BY position, created_at').bind(spaceId).all(),
    env.DB.prepare(
      `SELECT co.channel_id, co.role_id, co.allow, co.deny FROM channel_overrides co
       JOIN channels ch ON ch.id = co.channel_id WHERE ch.space_id = ? AND co.role_id IS NOT NULL`,
    ).bind(spaceId).all(),
    env.DB.prepare(
      `SELECT b.id, b.name, b.description, b.dm_enabled FROM bots b
       JOIN space_members sm ON sm.user_id = b.user_id
       WHERE sm.space_id = ? AND b.deleted_at IS NULL ORDER BY b.created_at`,
    ).bind(spaceId).all(),
    env.DB.prepare(
      `SELECT bc.bot_id, bc.name, bc.description, bc.options, bc.dm_enabled FROM bot_commands bc
       JOIN bots b ON b.id = bc.bot_id JOIN space_members sm ON sm.user_id = b.user_id
       WHERE sm.space_id = ? AND b.deleted_at IS NULL AND (bc.space_id IS NULL OR bc.space_id = ?)`,
    ).bind(spaceId, spaceId).all(),
  ]);
  return {
    format: 'pigeon-pack',
    version: '1.0',
    name: String(space['name']),
    description: space['description'] === null ? null : String(space['description'] ?? ''),
    categories: categories.results,
    channels: channels.results,
    roles: roles.results,
    overrides: overrides.results,
    bots: bots.results.map((bot) => ({
      ...bot,
      commands: commands.results.filter((command) => command['bot_id'] === bot['id']).map((command) => ({
        name: command['name'],
        description: command['description'],
        options: JSON.parse(String(command['options'] ?? '[]')),
        dm_enabled: Number(command['dm_enabled']) === 1,
      })),
    })),
    theme: null,
  };
}

export function parsePack(value: unknown): PigeonPackManifest {
  if (!value || typeof value !== 'object') throw new Error('pack must be an object');
  const raw = value as Record<string, unknown>;
  if (raw['format'] !== 'pigeon-pack' || raw['version'] !== '1.0') throw new Error('unsupported pack format');
  const list = (key: string, limit: number) => {
    const rows = Array.isArray(raw[key]) ? raw[key] as Record<string, unknown>[] : [];
    if (rows.length > limit) throw new Error(`pack has too many ${key}`);
    return rows;
  };
  return {
    format: 'pigeon-pack',
    version: '1.0',
    name: String(raw['name'] ?? 'Pigeon Pack').trim().slice(0, 64) || 'Pigeon Pack',
    description: raw['description'] == null ? null : String(raw['description']).slice(0, 500),
    categories: list('categories', 50),
    channels: list('channels', 100),
    roles: list('roles', 50),
    overrides: list('overrides', 250),
    bots: list('bots', 10),
    theme: raw['theme'] && typeof raw['theme'] === 'object' ? raw['theme'] as Record<string, unknown> : null,
  };
}

export async function packDigest(pack: PigeonPackManifest): Promise<string> {
  return sha256Hex(JSON.stringify(pack));
}

async function runBatches(env: Env, statements: D1PreparedStatement[]) {
  for (let index = 0; index < statements.length; index += 75) {
    await env.DB.batch(statements.slice(index, index + 75));
  }
}

export async function installPack(env: Env, spaceId: string, ownerId: string, pack: PigeonPackManifest) {
  const now = Date.now();
  const categoryIds = new Map<string, string>();
  const channelIds = new Map<string, string>();
  const roleIds = new Map<string, string>();
  const statements: D1PreparedStatement[] = [];

  for (const category of pack.categories) {
    const sourceId = String(category['id'] ?? category['name'] ?? snowflake());
    const categoryName = String(category['name'] ?? 'category').slice(0, 64);
    const existing = await env.DB.prepare(
      'SELECT id FROM channel_categories WHERE space_id = ? AND name = ? COLLATE NOCASE',
    ).bind(spaceId, categoryName).first<{ id: string }>();
    if (existing) {
      categoryIds.set(sourceId, existing.id);
      continue;
    }
    const id = snowflake();
    categoryIds.set(sourceId, id);
    statements.push(env.DB.prepare(
      'INSERT INTO channel_categories (id, space_id, name, position, collapsed, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(id, spaceId, categoryName, Number(category['position'] ?? 0), category['collapsed'] ? 1 : 0, now));
  }
  for (const role of pack.roles) {
    const sourceId = String(role['id'] ?? role['name'] ?? snowflake());
    const id = snowflake();
    roleIds.set(sourceId, id);
    statements.push(env.DB.prepare(
      'INSERT INTO space_roles (id, space_id, name, color, position, permissions, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    ).bind(id, spaceId, String(role['name'] ?? 'role').slice(0, 48), role['color'] ? String(role['color']).slice(0, 32) : null, Number(role['position'] ?? 0), Number(role['permissions'] ?? 0), now));
  }
  for (const channel of pack.channels) {
    const sourceId = String(channel['id'] ?? channel['name'] ?? snowflake());
    const id = snowflake();
    channelIds.set(sourceId, id);
    const sourceCategory = channel['category_id'] == null ? null : String(channel['category_id']);
    const kind = ['text', 'voice', 'forum'].includes(String(channel['kind'])) ? String(channel['kind']) : 'text';
    statements.push(env.DB.prepare(
      `INSERT INTO channels (id, space_id, name, topic, kind, category_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).bind(id, spaceId, String(channel['name'] ?? 'channel').slice(0, 48), channel['topic'] ? String(channel['topic']).slice(0, 500) : null, kind, sourceCategory ? categoryIds.get(sourceCategory) ?? null : null, now));
  }
  for (const override of pack.overrides) {
    const channelId = channelIds.get(String(override['channel_id'] ?? ''));
    const roleId = roleIds.get(String(override['role_id'] ?? ''));
    if (!channelId || !roleId) continue;
    statements.push(env.DB.prepare(
      'INSERT INTO channel_overrides (id, channel_id, role_id, allow, deny, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(snowflake(), channelId, roleId, Number(override['allow'] ?? 0), Number(override['deny'] ?? 0), now));
  }
  await runBatches(env, statements);

  const credentials: { id: string; name: string; token: string }[] = [];
  for (const descriptor of pack.bots) {
    const botId = snowflake();
    const userId = snowflake();
    const name = String(descriptor['name'] ?? 'Pack bot').trim().slice(0, 32) || 'Pack bot';
    const { token, hash } = await mintBotToken(botId);
    const signingSecret = randomSecret();
    const botStatements = [
      env.DB.prepare(
        `INSERT INTO users (id, username, email, display_name, password_hash, flags, created_at)
         VALUES (?, ?, ?, ?, '', ?, ?)`,
      ).bind(userId, `packbot_${botId.slice(-10)}`, `bot.${botId}@bots.invalid`, name, BOT_USER_FLAG, now),
      env.DB.prepare(
        `INSERT INTO bots (id, user_id, owner_id, name, description, token_hash, signing_secret, public, dm_enabled, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)`,
      ).bind(botId, userId, ownerId, name, descriptor['description'] ? String(descriptor['description']).slice(0, 500) : null, hash, signingSecret, descriptor['dm_enabled'] === false ? 0 : 1, now, now),
      env.DB.prepare("INSERT INTO space_members (space_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)")
        .bind(spaceId, userId, now),
    ];
    const commands = Array.isArray(descriptor['commands']) ? descriptor['commands'] as Record<string, unknown>[] : [];
    for (const command of commands.slice(0, 100)) {
      const commandName = String(command['name'] ?? '').toLowerCase().replace(/[^a-z0-9_-]/g, '').slice(0, 32);
      if (!commandName) continue;
      botStatements.push(env.DB.prepare(
        `INSERT INTO bot_commands (id, bot_id, space_id, name, description, options, dm_enabled, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(snowflake(), botId, spaceId, commandName, String(command['description'] ?? commandName).slice(0, 100), JSON.stringify(Array.isArray(command['options']) ? command['options'].slice(0, 25) : []), command['dm_enabled'] === false ? 0 : 1, now));
    }
    await runBatches(env, botStatements);
    credentials.push({ id: botId, name, token });
  }
  return { categoryIds, channelIds, roleIds, botCredentials: credentials, theme: pack.theme };
}
