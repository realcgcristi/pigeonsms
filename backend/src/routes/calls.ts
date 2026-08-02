import { Hono } from 'hono';
import { assertChannelAccess } from '../lib/channels';
import { requireAuth } from '../middleware/auth';
import { ApiError } from '../middleware/errors';
import { enforceRateLimit } from '../middleware/ratelimit';
import type { AppEnv } from '../types';

const calls = new Hono<AppEnv>();
calls.use('*', requireAuth);

/** Channel IDs are room names, so voice channels keep one stable room. */
function room(c: { env: AppEnv['Bindings'] }, channelId: string) {
  return c.env.CALL_ROOM.get(c.env.CALL_ROOM.idFromName(channelId));
}

export interface IceServerConfig {
  urls: string[];
  username?: string;
  credential?: string;
}

const FALLBACK_ICE_SERVERS: IceServerConfig[] = [
  { urls: ['stun:stun.cloudflare.com:3478', 'stun:stun.l.google.com:19302'] },
];

function validIceUrl(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0 || value.length > 2048) return false;
  if (!/^(?:stun|stuns|turn|turns):/i.test(value)) return false;
  return !/^(?:stun|stuns|turn|turns):[^?]+:53(?:\?|$)/i.test(value);
}

export function sanitizeIceServers(value: unknown): IceServerConfig[] {
  if (!Array.isArray(value)) return [];
  const result: IceServerConfig[] = [];
  for (const item of value.slice(0, 12)) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
    const record = item as Record<string, unknown>;
    const rawUrls = Array.isArray(record.urls) ? record.urls : [record.urls];
    const urls = rawUrls.filter(validIceUrl).slice(0, 12);
    if (urls.length === 0) continue;
    const hasTurn = urls.some((url) => /^turns?:/i.test(url));
    const username = typeof record.username === 'string' ? record.username.slice(0, 512) : undefined;
    const credential = typeof record.credential === 'string' ? record.credential.slice(0, 512) : undefined;
    if (hasTurn && (!username || !credential)) continue;
    result.push({ urls, ...(username ? { username } : {}), ...(credential ? { credential } : {}) });
  }
  return result;
}

function credentialTtl(value: string | undefined): number {
  const parsed = Number.parseInt(value ?? '', 10);
  if (!Number.isFinite(parsed)) return 43_200;
  return Math.min(172_800, Math.max(3_600, parsed));
}

async function callConfiguration(env: AppEnv['Bindings']) {
  const ttl = credentialTtl(env.TURN_CREDENTIAL_TTL_SECONDS);
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) {
    return { ice_servers: FALLBACK_ICE_SERVERS, turn: false, expires_at: null, source: 'stun' };
  }
  try {
    const response = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(env.TURN_KEY_ID)}/credentials/generate-ice-servers`,
      {
        method: 'POST',
        headers: {
          authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`,
          'content-type': 'application/json',
        },
        body: JSON.stringify({ ttl }),
      },
    );
    if (!response.ok) throw new Error(`turn credential request failed with ${response.status}`);
    const body = await response.json() as { iceServers?: unknown };
    const iceServers = sanitizeIceServers(body.iceServers);
    if (!iceServers.some((server) => server.urls.some((url) => /^turns?:/i.test(url)))) {
      throw new Error('turn credential response contained no relay server');
    }
    return {
      ice_servers: iceServers,
      turn: true,
      expires_at: Date.now() + ttl * 1000,
      source: 'cloudflare',
    };
  } catch (error) {
    console.error('TURN credential generation failed', error);
    return { ice_servers: FALLBACK_ICE_SERVERS, turn: false, expires_at: null, source: 'stun' };
  }
}

calls.get('/:channelId/config', async (c) => {
  const channelId = c.req.param('channelId');
  const userId = c.get('user')!.id;
  await enforceRateLimit(c.env.RL_GENERAL, `turn:${userId}`);
  await assertChannelAccess(c.env, userId, channelId);
  c.header('cache-control', 'no-store');
  return c.json(await callConfiguration(c.env));
});

calls.get('/:channelId/ws', async (c) => {
  const mode = c.req.query('mode');
  if (mode !== 'voice' && mode !== 'video') {
    throw new ApiError(400, 'invalid_mode', 'mode must be voice or video');
  }
  if (c.req.header('upgrade')?.toLowerCase() !== 'websocket') {
    throw new ApiError(426, 'upgrade_required', 'WebSocket upgrade required');
  }

  const channelId = c.req.param('channelId');
  await assertChannelAccess(c.env, c.get('user')!.id, channelId);

  // Forward the original request: rebuilding it drops forbidden Upgrade and
  // Connection headers. Authorization/query token remains for the room's own check.
  return room(c, channelId).fetch(c.req.raw);
});

calls.get('/:channelId/participants', async (c) => {
  const channelId = c.req.param('channelId');
  await assertChannelAccess(c.env, c.get('user')!.id, channelId);
  return room(c, channelId).fetch(c.req.raw);
});

export default calls;
