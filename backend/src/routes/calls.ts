import { Hono } from 'hono';
import { assertChannelAccess } from '../lib/channels';
import { requireAuth } from '../middleware/auth';
import { ApiError } from '../middleware/errors';
import type { AppEnv } from '../types';

const calls = new Hono<AppEnv>();
calls.use('*', requireAuth);

/** Channel IDs are room names, so voice channels keep one stable room. */
function room(c: { env: AppEnv['Bindings'] }, channelId: string) {
  return c.env.CALL_ROOM.get(c.env.CALL_ROOM.idFromName(channelId));
}

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
