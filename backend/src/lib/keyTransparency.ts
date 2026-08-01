import { sha256Hex } from './crypto';
import { snowflake } from './ids';
import type { Env } from '../types';

export interface TransparencyEntry {
  id: string;
  user_id: string;
  device_id: string;
  action: 'register' | 'revoke';
  public_key: string | null;
  previous_hash: string | null;
  entry_hash: string;
  created_at: number;
}

export interface TransparencyCheckpoint {
  tree_size: number;
  root_hash: string;
  latest_hash: string | null;
  generated_at: number;
}

function canonical(entry: Omit<TransparencyEntry, 'entry_hash'>): string {
  return JSON.stringify([
    entry.id,
    entry.user_id,
    entry.device_id,
    entry.action,
    entry.public_key,
    entry.previous_hash,
    entry.created_at,
  ]);
}

export async function transparencyEntryHash(entry: Omit<TransparencyEntry, 'entry_hash'>): Promise<string> {
  return sha256Hex(`pigeon-key-v1:${canonical(entry)}`);
}

export async function verifyTransparencyChain(entries: TransparencyEntry[]): Promise<boolean> {
  let previous: string | null = null;
  for (const entry of entries) {
    if (entry.previous_hash !== previous) return false;
    const hash = await transparencyEntryHash({
      id: entry.id,
      user_id: entry.user_id,
      device_id: entry.device_id,
      action: entry.action,
      public_key: entry.public_key,
      previous_hash: entry.previous_hash,
      created_at: entry.created_at,
    });
    if (hash !== entry.entry_hash) return false;
    previous = hash;
  }
  return true;
}

async function merkleLevel(nodes: string[]): Promise<string[]> {
  const next: string[] = [];
  for (let index = 0; index < nodes.length; index += 2) {
    const left = nodes[index] ?? '';
    const right = nodes[index + 1] ?? left;
    next.push(await sha256Hex(`pigeon-node-v1:${left}:${right}`));
  }
  return next;
}

export async function transparencyRoot(hashes: string[]): Promise<string> {
  if (hashes.length === 0) return sha256Hex('pigeon-empty-v1');
  let level = hashes.slice();
  while (level.length > 1) level = await merkleLevel(level);
  return level[0] ?? sha256Hex('pigeon-empty-v1');
}

export async function transparencyProof(hashes: string[], leafIndex: number): Promise<string[]> {
  if (leafIndex < 0 || leafIndex >= hashes.length) return [];
  const proof: string[] = [];
  let index = leafIndex;
  let level = hashes.slice();
  while (level.length > 1) {
    const sibling = index % 2 === 0 ? level[index + 1] ?? level[index] : level[index - 1];
    if (sibling) proof.push(sibling);
    level = await merkleLevel(level);
    index = Math.floor(index / 2);
  }
  return proof;
}

function rowEntry(row: Record<string, unknown>): TransparencyEntry {
  return {
    id: String(row['id']),
    user_id: String(row['user_id']),
    device_id: String(row['device_id']),
    action: row['action'] === 'revoke' ? 'revoke' : 'register',
    public_key: row['public_key'] === null ? null : String(row['public_key']),
    previous_hash: row['previous_hash'] === null ? null : String(row['previous_hash']),
    entry_hash: String(row['entry_hash']),
    created_at: Number(row['created_at']),
  };
}

export async function listTransparencyEntries(env: Env, userId: string): Promise<TransparencyEntry[]> {
  const result = await env.DB.prepare(
    `SELECT id, user_id, device_id, action, public_key, previous_hash, entry_hash, created_at
     FROM key_transparency_entries WHERE user_id = ? ORDER BY sequence`,
  ).bind(userId).all<Record<string, unknown>>();
  return result.results.map(rowEntry);
}

export async function appendTransparencyEntry(
  env: Env,
  userId: string,
  deviceId: string,
  action: 'register' | 'revoke',
  publicKey: string | null,
  createdAt = Date.now(),
): Promise<TransparencyEntry> {
  const findExisting = () => env.DB.prepare(
    `SELECT id, user_id, device_id, action, public_key, previous_hash, entry_hash, created_at
     FROM key_transparency_entries WHERE user_id = ? AND device_id = ? AND action = ?`,
  ).bind(userId, deviceId, action).first<Record<string, unknown>>();
  const existing = await findExisting();
  if (existing) return rowEntry(existing);
  let failure: unknown;
  for (let attempt = 0; attempt < 4; attempt++) {
    const latest = await env.DB.prepare(
      `SELECT entry_hash, sequence FROM key_transparency_entries
       WHERE user_id = ? ORDER BY sequence DESC LIMIT 1`,
    ).bind(userId).first<{ entry_hash: string; sequence: number }>();
    const unsigned = {
      id: snowflake(),
      user_id: userId,
      device_id: deviceId,
      action,
      public_key: publicKey,
      previous_hash: latest?.entry_hash ?? null,
      created_at: createdAt,
    };
    const entry: TransparencyEntry = { ...unsigned, entry_hash: await transparencyEntryHash(unsigned) };
    try {
      await env.DB.prepare(
        `INSERT INTO key_transparency_entries
         (id, user_id, device_id, action, public_key, previous_hash, entry_hash, sequence, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        entry.id,
        entry.user_id,
        entry.device_id,
        entry.action,
        entry.public_key,
        entry.previous_hash,
        entry.entry_hash,
        Number(latest?.sequence ?? 0) + 1,
        entry.created_at,
      ).run();
      return entry;
    } catch (error) {
      failure = error;
      const duplicate = await findExisting().catch(() => null);
      if (duplicate) return rowEntry(duplicate);
    }
  }
  throw failure;
}

export async function ensureTransparencyEntries(env: Env, userId: string): Promise<TransparencyEntry[]> {
  const devices = await env.DB.prepare(
    `SELECT d.id, d.pub_key, d.created_at FROM user_devices d
     WHERE d.user_id = ? AND NOT EXISTS (
       SELECT 1 FROM key_transparency_entries e
       WHERE e.user_id = ? AND e.device_id = d.id AND e.action = 'register'
     ) ORDER BY d.created_at, d.id`,
  ).bind(userId, userId).all<{ id: string; pub_key: string; created_at: number }>();
  for (const device of devices.results) {
    await appendTransparencyEntry(env, userId, device.id, 'register', device.pub_key, device.created_at);
  }
  return listTransparencyEntries(env, userId);
}

export async function transparencyCheckpoint(entries: TransparencyEntry[]): Promise<TransparencyCheckpoint> {
  return {
    tree_size: entries.length,
    root_hash: await transparencyRoot(entries.map((entry) => entry.entry_hash)),
    latest_hash: entries.at(-1)?.entry_hash ?? null,
    generated_at: Date.now(),
  };
}
