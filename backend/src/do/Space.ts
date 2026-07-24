import { assertChannelAccess } from '../lib/channels';
import { sha256Hex } from '../lib/crypto';
import { ApiError } from '../middleware/errors';
import type { Env } from '../types';

/**
 * One per space channel (community channel).
 *
 * 2.8.0 first cut: this owns *presence and typing* for a channel — the online
 * set (who currently holds a live socket) and transient "user is typing"
 * signals — and fans those ephemeral events out to connected sockets. It is a
 * hibernation-aware signaling room, modeled on CallRoom / DmChannel.
 *
 * IMPORTANT — message sequencing is deliberately NOT owned here yet.
 * TODO(2.9.0): move per-channel message `seq` allocation off the D1 single-row
 * `channels.last_seq` bump (`lib/channels.ts#bumpSeq`) into this DO's storage so
 * fanout ordering is owned by the same actor. For now D1 stays the single source
 * of truth for `seq`: forking the counter (DO storage vs. the D1 row that every
 * read path and `bumpSeq` still use) would corrupt ordering and unread counts.
 * This DO therefore must NOT allocate, persist, or mutate any message `seq` — it
 * only relays ephemeral presence/typing.
 */

const MAX_CLIENT_FRAME_BYTES = 8 * 1024;

/** Advisory lifetime of a typing signal before clients treat it as expired. */
const TYPING_TTL_MS = 8_000;

interface Member {
  userId: string;
  username: string;
}

type ClientFrame =
  | { t: 'typing.start' }
  | { t: 'typing.stop' }
  | { t: 'ping' };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function memberOf(ws: WebSocket): Member | null {
  try {
    const value: unknown = ws.deserializeAttachment();
    if (!isRecord(value) || typeof value.userId !== 'string' || typeof value.username !== 'string') {
      return null;
    }
    return { userId: value.userId, username: value.username };
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

function errorResponse(status: number, code: string, message: string): Response {
  return Response.json(
    { error: { code, message } },
    { status, headers: { 'cache-control': 'no-store' } },
  );
}

/** Parse `/spaces/:channelId/(ws|presence)`. The DO is addressed per channel (its
 *  name is the channel id), matching how message fanout is scoped per channel. */
function spacePath(pathname: string): { channelId: string; endpoint: 'ws' | 'presence' } | null {
  const parts = pathname.split('/').filter(Boolean);
  if (parts.length !== 3 || parts[0] !== 'spaces') return null;
  if (parts[2] !== 'ws' && parts[2] !== 'presence') return null;
  try {
    const channelId = decodeURIComponent(parts[1] ?? '');
    if (!channelId) return null;
    return { channelId, endpoint: parts[2] };
  } catch {
    return null;
  }
}

function parseClientFrame(raw: string): ClientFrame | null {
  if (new TextEncoder().encode(raw).byteLength > MAX_CLIENT_FRAME_BYTES) return null;
  let value: unknown;
  try {
    value = JSON.parse(raw) as unknown;
  } catch {
    return null;
  }
  if (!isRecord(value)) return null;
  if (value.t === 'typing.start' || value.t === 'typing.stop' || value.t === 'ping') {
    return { t: value.t };
  }
  return null;
}

export class Space {
  private readonly departed = new WeakSet<WebSocket>();

  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(req: Request): Promise<Response> {
    try {
      if (req.method !== 'GET') return errorResponse(405, 'method_not_allowed', 'GET required');

      const url = new URL(req.url);
      const path = spacePath(url.pathname);
      if (!path) return errorResponse(404, 'not_found', 'space endpoint not found');

      const identity = await this.resolveIdentity(req, url);
      if (!identity) return errorResponse(401, 'unauthorized', 'invalid or expired session');

      // Membership is re-checked inside the DO so it never trusts caller-supplied
      // identity or access claims.
      await assertChannelAccess(this.env, identity.userId, path.channelId);

      if (path.endpoint === 'presence') {
        return Response.json(
          { online: this.onlineSet(), typing: this.typingSet() },
          { headers: { 'cache-control': 'no-store' } },
        );
      }

      if (req.headers.get('upgrade')?.toLowerCase() !== 'websocket') {
        return errorResponse(426, 'upgrade_required', 'WebSocket upgrade required');
      }
      return this.accept(identity);
    } catch (err) {
      if (err instanceof ApiError) return errorResponse(err.status, err.code, err.message);
      console.error('Space fetch failed', err);
      return errorResponse(500, 'internal', 'something went wrong');
    }
  }

  webSocketMessage(ws: WebSocket, message: ArrayBuffer | string): void {
    const member = memberOf(ws);
    if (!member) {
      ws.close(1011, 'missing member identity');
      return;
    }
    if (typeof message !== 'string') {
      ws.close(1003, 'JSON text messages required');
      return;
    }
    const frame = parseClientFrame(message);
    if (!frame) {
      sendJson(ws, { t: 'error', d: { code: 'invalid_frame' } });
      return;
    }
    if (frame.t === 'ping') {
      sendJson(ws, { t: 'pong' });
      return;
    }
    // Ephemeral typing relay only — never persisted, never tied to message seq
    // (see class TODO: D1 remains the seq source of truth).
    this.broadcast(
      { t: frame.t === 'typing.start' ? 'typing.start' : 'typing.stop', d: { user_id: member.userId, username: member.username } },
      ws,
    );
  }

  webSocketClose(ws: WebSocket): void {
    this.handleDeparture(ws);
  }

  webSocketError(ws: WebSocket): void {
    this.handleDeparture(ws);
  }

  private async resolveIdentity(req: Request, url: URL): Promise<Member | null> {
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

  private accept(identity: Member): Response {
    const wasOnline = this.onlineSet().some((member) => member.userId === identity.userId);

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.serializeAttachment(identity);
    this.state.acceptWebSocket(server);

    sendJson(server, { t: 'ready', d: { online: this.onlineSet(), typing: this.typingSet() } });
    if (!wasOnline) {
      this.broadcast({ t: 'presence.online', d: { user_id: identity.userId, username: identity.username } }, server);
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  private onlineSet(): Member[] {
    const byUser = new Map<string, Member>();
    for (const ws of this.state.getWebSockets()) {
      const member = memberOf(ws);
      if (member && !byUser.has(member.userId)) byUser.set(member.userId, member);
    }
    return [...byUser.values()];
  }

  /** See DmChannel.typingSet: typing is transient, not durably retained across
   *  hibernation in this first cut. Clients derive live typing from relayed frames
   *  and expire it after TYPING_TTL_MS; the snapshot returns [] on a cold read. */
  private typingSet(): Member[] {
    void TYPING_TTL_MS;
    return [];
  }

  private broadcast(payload: unknown, except?: WebSocket): number {
    let delivered = 0;
    for (const candidate of this.state.getWebSockets()) {
      if (candidate === except) continue;
      if (sendJson(candidate, payload)) delivered += 1;
    }
    return delivered;
  }

  private handleDeparture(ws: WebSocket): void {
    if (this.departed.has(ws)) return;
    this.departed.add(ws);

    const member = memberOf(ws);
    if (!member) return;
    const stillPresent = this.state
      .getWebSockets()
      .some((candidate) => candidate !== ws && memberOf(candidate)?.userId === member.userId);
    if (!stillPresent) {
      this.broadcast({ t: 'presence.offline', d: { user_id: member.userId, username: member.username } }, ws);
    }
  }
}
