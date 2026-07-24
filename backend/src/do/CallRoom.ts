import { assertChannelAccess } from '../lib/channels';
import { sha256Hex } from '../lib/crypto';
import { ApiError } from '../middleware/errors';
import type { Env } from '../types';

export const MAX_SIGNAL_BYTES = 64 * 1024;

export type CallMode = 'voice' | 'video';
export type SignalType = 'offer' | 'answer' | 'ice' | 'mute' | 'camera';

export interface CallParticipant {
  userId: string;
  username: string;
  mode: CallMode;
}

export interface ClientSignal {
  type: SignalType;
  target?: string;
  data?: unknown;
}

export type SignalParseResult =
  | { ok: true; signal: ClientSignal }
  | { ok: false; code: 'invalid_signal' | 'signal_too_large' };

const SIGNAL_TYPES = new Set<SignalType>(['offer', 'answer', 'ice', 'mute', 'camera']);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Parse and bound the only client messages CallRoom will relay. */
export function parseClientSignal(raw: string): SignalParseResult {
  if (new TextEncoder().encode(raw).byteLength > MAX_SIGNAL_BYTES) {
    return { ok: false, code: 'signal_too_large' };
  }

  let value: unknown;
  try {
    value = JSON.parse(raw) as unknown;
  } catch {
    return { ok: false, code: 'invalid_signal' };
  }

  if (!isRecord(value) || typeof value.type !== 'string' || !SIGNAL_TYPES.has(value.type as SignalType)) {
    return { ok: false, code: 'invalid_signal' };
  }

  if (
    value.target !== undefined &&
    (typeof value.target !== 'string' || value.target.length === 0 || value.target.length > 128)
  ) {
    return { ok: false, code: 'invalid_signal' };
  }

  const signal: ClientSignal = { type: value.type as SignalType };
  if (typeof value.target === 'string') signal.target = value.target;
  if (Object.hasOwn(value, 'data')) signal.data = value.data;
  return { ok: true, signal };
}

function errorResponse(status: number, code: string, message: string): Response {
  return Response.json(
    { error: { code, message } },
    { status, headers: { 'cache-control': 'no-store' } },
  );
}

function callPath(pathname: string): { channelId: string; endpoint: 'ws' | 'participants' } | null {
  const parts = pathname.split('/').filter(Boolean);
  if (parts.length !== 3 || parts[0] !== 'calls') return null;
  if (parts[2] !== 'ws' && parts[2] !== 'participants') return null;

  try {
    const channelId = decodeURIComponent(parts[1] ?? '');
    if (!channelId) return null;
    return { channelId, endpoint: parts[2] };
  } catch {
    return null;
  }
}

function modeFrom(url: URL): CallMode | null {
  const mode = url.searchParams.get('mode');
  return mode === 'voice' || mode === 'video' ? mode : null;
}

function attachmentOf(ws: WebSocket): CallParticipant | null {
  try {
    const value: unknown = ws.deserializeAttachment();
    if (
      !isRecord(value) ||
      typeof value.userId !== 'string' ||
      typeof value.username !== 'string' ||
      (value.mode !== 'voice' && value.mode !== 'video')
    ) {
      return null;
    }
    return { userId: value.userId, username: value.username, mode: value.mode };
  } catch {
    return null;
  }
}

function sendJson(ws: WebSocket, payload: unknown): boolean {
  try {
    ws.send(JSON.stringify(payload));
    return true;
  } catch {
    return false;
  }
}

/**
 * One hibernation-aware signaling room per channel. It only relays bounded
 * WebRTC signaling/state JSON; audio and video never pass through the Worker.
 */
export class CallRoom {
  private readonly departed = new WeakSet<WebSocket>();

  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(req: Request): Promise<Response> {
    try {
      if (req.method !== 'GET') return errorResponse(405, 'method_not_allowed', 'GET required');

      const url = new URL(req.url);
      const path = callPath(url.pathname);
      if (!path) return errorResponse(404, 'not_found', 'call endpoint not found');

      const identity = await this.resolveIdentity(req, url);
      if (!identity) return errorResponse(401, 'unauthorized', 'invalid or expired session');

      // The Worker route checks this before forwarding too. Repeating it here
      // prevents the room from trusting identity or membership passed by a caller.
      await assertChannelAccess(this.env, identity.userId, path.channelId);

      if (path.endpoint === 'participants') {
        return Response.json(
          { participants: this.participants() },
          { headers: { 'cache-control': 'no-store' } },
        );
      }

      const mode = modeFrom(url);
      if (!mode) return errorResponse(400, 'invalid_mode', 'mode must be voice or video');
      if (req.headers.get('upgrade')?.toLowerCase() !== 'websocket') {
        return errorResponse(426, 'upgrade_required', 'WebSocket upgrade required');
      }

      return this.accept(identity, mode);
    } catch (err) {
      if (err instanceof ApiError) return errorResponse(err.status, err.code, err.message);
      console.error('CallRoom fetch failed', err);
      return errorResponse(500, 'internal', 'something went wrong');
    }
  }

  webSocketMessage(ws: WebSocket, message: ArrayBuffer | string): void {
    const participant = attachmentOf(ws);
    if (!participant) {
      ws.close(1011, 'missing participant identity');
      return;
    }

    if (typeof message !== 'string') {
      ws.close(1003, 'JSON text messages required');
      return;
    }

    const parsed = parseClientSignal(message);
    if (!parsed.ok) {
      if (parsed.code === 'signal_too_large') {
        ws.close(1009, 'signal too large');
      } else {
        sendJson(ws, { type: 'error', code: parsed.code });
      }
      return;
    }

    const relay: Record<string, unknown> = {
      type: parsed.signal.type,
      from: participant.userId,
      mode: participant.mode,
    };
    if (parsed.signal.target) relay.target = parsed.signal.target;
    if (Object.hasOwn(parsed.signal, 'data')) relay.data = parsed.signal.data;

    const delivered = this.broadcast(relay, ws, parsed.signal.target);
    if (parsed.signal.target && delivered === 0) {
      sendJson(ws, { type: 'error', code: 'target_not_found', target: parsed.signal.target });
    }
  }

  webSocketClose(ws: WebSocket): void {
    this.handleDeparture(ws);
  }

  webSocketError(ws: WebSocket): void {
    this.handleDeparture(ws);
  }

  private async resolveIdentity(
    req: Request,
    url: URL,
  ): Promise<{ userId: string; username: string } | null> {
    const header = req.headers.get('authorization') ?? '';
    const token = header.startsWith('Bearer ') ? header.slice(7) : (url.searchParams.get('token') ?? '');
    if (!token) return null;

    const row = await this.env.DB.prepare(
      `SELECT u.id, u.username
       FROM sessions s JOIN users u ON u.id = s.user_id
       WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at >= ?
         AND u.deleted_at IS NULL`,
    )
      .bind(await sha256Hex(token), Date.now())
      .first<{ id: string; username: string }>();
    return row ? { userId: row.id, username: row.username } : null;
  }

  private accept(identity: { userId: string; username: string }, mode: CallMode): Response {
    const participant: CallParticipant = { ...identity, mode };
    const existingSockets = this.state.getWebSockets();
    const alreadyPresent = existingSockets.some(
      (candidate) => attachmentOf(candidate)?.userId === participant.userId,
    );

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.serializeAttachment(participant);
    this.state.acceptWebSocket(server);

    sendJson(server, {
      type: 'ready',
      participant,
      participants: this.participants(),
    });
    if (!alreadyPresent) this.broadcast({ type: 'join', participant }, server);

    return new Response(null, { status: 101, webSocket: client });
  }

  private participants(sockets: WebSocket[] = this.state.getWebSockets()): CallParticipant[] {
    const byUser = new Map<string, CallParticipant>();
    for (const ws of sockets) {
      const participant = attachmentOf(ws);
      if (participant && !byUser.has(participant.userId)) {
        byUser.set(participant.userId, participant);
      }
    }
    return [...byUser.values()];
  }

  private broadcast(payload: unknown, except?: WebSocket, target?: string): number {
    let delivered = 0;
    for (const candidate of this.state.getWebSockets()) {
      if (candidate === except) continue;
      const participant = attachmentOf(candidate);
      if (!participant || (target && participant.userId !== target)) continue;
      if (sendJson(candidate, payload)) delivered += 1;
    }
    return delivered;
  }

  private handleDeparture(ws: WebSocket): void {
    if (this.departed.has(ws)) return;
    this.departed.add(ws);

    const participant = attachmentOf(ws);
    if (!participant) return;
    const stillPresent = this.state
      .getWebSockets()
      .some((candidate) => candidate !== ws && attachmentOf(candidate)?.userId === participant.userId);
    if (!stillPresent) this.broadcast({ type: 'leave', participant }, ws);
  }
}
