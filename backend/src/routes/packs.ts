import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { snowflake } from '../lib/ids';
import { installPack, packDigest, packFromSpace, parsePack } from '../lib/packs';
import { Permission, requirePermission } from '../lib/permissions';
import { readJsonBody } from '../lib/validate';
import type { AppEnv, AuthedUser } from '../types';

const packs = new Hono<AppEnv>();
packs.use(requireAuth);

interface PackRow {
  id: string;
  owner_id: string;
  name: string;
  description: string | null;
  version: string;
  manifest: string;
  digest: string;
  public: number;
  created_at: number;
  updated_at: number;
}

function summary(row: PackRow) {
  return {
    id: row.id,
    owner_id: row.owner_id,
    name: row.name,
    description: row.description,
    version: row.version,
    digest: row.digest,
    public: row.public === 1,
    created_at: row.created_at,
    updated_at: row.updated_at,
  };
}

packs.get('/packs', async (c) => {
  const user = c.get('user') as AuthedUser;
  const { results } = await c.env.DB.prepare(
    'SELECT * FROM pigeon_packs WHERE public = 1 OR owner_id = ? ORDER BY updated_at DESC LIMIT 100',
  ).bind(user.id).all<PackRow>();
  return c.json({ packs: results.map(summary) });
});

packs.post('/packs', async (c) => {
  const user = c.get('user') as AuthedUser;
  if (user.isBot) throw new ApiError(403, 'forbidden', 'bots cannot publish packs');
  const body = await readJsonBody(c);
  let pack;
  if (body['source_space_id']) {
    const spaceId = String(body['source_space_id']);
    await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_NEST);
    pack = await packFromSpace(c.env, spaceId);
    if (!pack) throw new ApiError(404, 'not_found', 'nest not found');
  } else {
    try {
      pack = parsePack(body['pack']);
    } catch (error) {
      throw new ApiError(400, 'bad_pack', error instanceof Error ? error.message : 'invalid pack');
    }
  }
  const id = snowflake();
  const digest = await packDigest(pack);
  const now = Date.now();
  await c.env.DB.prepare(
    `INSERT INTO pigeon_packs
     (id, owner_id, name, description, version, manifest, digest, public, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(id, user.id, pack.name, pack.description, pack.version, JSON.stringify(pack), digest, body['public'] ? 1 : 0, now, now).run();
  return c.json({ pack: { id, owner_id: user.id, name: pack.name, description: pack.description, version: pack.version, digest, public: !!body['public'], created_at: now, updated_at: now }, manifest: pack }, 201);
});

packs.get('/packs/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const row = await c.env.DB.prepare('SELECT * FROM pigeon_packs WHERE id = ?').bind(c.req.param('id')).first<PackRow>();
  if (!row || (row.public !== 1 && row.owner_id !== user.id)) throw new ApiError(404, 'not_found', 'pack not found');
  return c.json({ pack: summary(row), manifest: JSON.parse(row.manifest) });
});

packs.delete('/packs/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const result = await c.env.DB.prepare('DELETE FROM pigeon_packs WHERE id = ? AND owner_id = ?')
    .bind(c.req.param('id'), user.id).run();
  if (result.meta.changes === 0) throw new ApiError(404, 'not_found', 'pack not found');
  return c.json({ ok: true });
});

packs.get('/spaces/:spaceId/pack', async (c) => {
  const user = c.get('user') as AuthedUser;
  const spaceId = c.req.param('spaceId');
  await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_NEST);
  const pack = await packFromSpace(c.env, spaceId);
  if (!pack) throw new ApiError(404, 'not_found', 'nest not found');
  const accent = c.req.query('accent')?.slice(0, 32);
  const uiSkin = c.req.query('ui_skin');
  if (accent || ['classic', 'nova', 'galaxy'].includes(uiSkin ?? '')) {
    pack.theme = {
      ...(accent ? { accent } : {}),
      ...(['classic', 'nova', 'galaxy'].includes(uiSkin ?? '') ? { ui_skin: uiSkin } : {}),
    };
  }
  return c.json({ pack, digest: await packDigest(pack) });
});

packs.post('/spaces/:spaceId/packs/install', async (c) => {
  const user = c.get('user') as AuthedUser;
  if (user.isBot) throw new ApiError(403, 'forbidden', 'bots cannot install packs');
  const spaceId = c.req.param('spaceId');
  await requirePermission(c.env, user.id, spaceId, Permission.MANAGE_NEST);
  const body = await readJsonBody(c);
  let packId = '';
  let manifest;
  if (body['pack_id']) {
    packId = String(body['pack_id']);
    const row = await c.env.DB.prepare('SELECT * FROM pigeon_packs WHERE id = ?').bind(packId).first<PackRow>();
    if (!row || (row.public !== 1 && row.owner_id !== user.id)) throw new ApiError(404, 'not_found', 'pack not found');
    manifest = parsePack(JSON.parse(row.manifest));
  } else {
    try {
      manifest = parsePack(body['pack']);
    } catch (error) {
      throw new ApiError(400, 'bad_pack', error instanceof Error ? error.message : 'invalid pack');
    }
  }
  const digest = await packDigest(manifest);
  const installId = packId || `inline:${digest.slice(0, 24)}`;
  const installed = await c.env.DB.prepare(
    'SELECT version, installed_at FROM installed_packs WHERE space_id = ? AND pack_id = ?',
  ).bind(spaceId, installId).first<{ version: string; installed_at: number }>();
  if (installed?.version === manifest.version) {
    return c.json({
      ok: true,
      duplicate: true,
      pack_id: installId,
      digest,
      installed_at: installed.installed_at,
      created: { categories: 0, channels: 0, roles: 0, bots: 0 },
      bot_credentials: [],
      theme: manifest.theme,
    });
  }
  const owned = await c.env.DB.prepare('SELECT COUNT(*) AS count FROM bots WHERE owner_id = ? AND deleted_at IS NULL')
    .bind(user.id).first<{ count: number }>();
  if (Number(owned?.count ?? 0) + manifest.bots.length > 25) {
    throw new ApiError(409, 'too_many_bots', 'pack would exceed the 25 bot account limit');
  }
  const result = await installPack(c.env, spaceId, user.id, manifest);
  await c.env.DB.prepare(
    `INSERT INTO installed_packs (space_id, pack_id, version, installed_by, installed_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(space_id, pack_id) DO UPDATE SET version = excluded.version,
       installed_by = excluded.installed_by, installed_at = excluded.installed_at`,
  ).bind(spaceId, installId, manifest.version, user.id, Date.now()).run();
  return c.json({
    ok: true,
    pack_id: installId,
    digest,
    created: {
      categories: result.categoryIds.size,
      channels: result.channelIds.size,
      roles: result.roleIds.size,
      bots: result.botCredentials.length,
    },
    bot_credentials: result.botCredentials,
    theme: result.theme,
  }, 201);
});

export default packs;
