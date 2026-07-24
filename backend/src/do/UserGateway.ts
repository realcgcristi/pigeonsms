import type { Env } from '../types';

/** One buffered event. `seq` is a DO-local monotonic counter (NOT the per-channel
 *  message seq); `ts` is the server time we delivered it. Both are meaningful only
 *  within a single DO lifetime — see `recent` below. */
interface BufferedEvent {
  seq: number;
  ts: number;
  payload: string;
}

/** How many recent events we keep for gap-replay. Best-effort only. */
const REPLAY_BUFFER_SIZE = 100;

/** A `?since=` value at or above this is treated as a unix-ms timestamp rather
 *  than a DO-local buffer seq (buffer seqs start at 1 and rarely reach this). */
const SINCE_TIMESTAMP_THRESHOLD = 1_000_000_000_000; // 2001-09-09 in ms

/**
 * One per user; owns every device WebSocket (Hibernation API, so idle
 * connections cost nothing). Other handlers POST /notify to push events;
 * the response tells them whether anything was connected (0 → FCM fallback).
 */
export class UserGateway {
  /**
   * In-memory ring buffer of recently delivered `/notify` payloads, for
   * best-effort gap replay when a socket reconnects with `?since=`. This lives
   * only on the DO instance: it is EMPTY after a cold start / hibernation
   * eviction, so replay can silently return nothing. It is intentionally not
   * persisted — durable history already lives in D1 (messages/notifications),
   * and clients should backfill from there for anything they truly need.
   */
  private recent: BufferedEvent[] = [];
  private recentSeq = 0;

  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);

    if (url.pathname === '/bind' && req.method === 'POST') {
      await this.state.storage.put('uid', await req.text());
      // A socket may have connected on a cold DO before /bind landed (the two
      // are separate stub.fetch calls); its touchPresence no-oped for lack of a
      // uid. Now that we know who this is, record presence so that first
      // connect is not lost until the client's next ping.
      await this.touchPresence();
      return new Response(null, { status: 204 });
    }

    if (req.headers.get('upgrade')?.toLowerCase() === 'websocket') {
      const pair = new WebSocketPair();
      this.state.acceptWebSocket(pair[1]);
      // touchPresence is a no-op if /bind has not landed yet on a cold DO; the
      // /bind handler re-touches once the uid is known, so presence is not lost.
      await this.touchPresence();
      // Optional gap replay: if the client passed ?since=, resend buffered events
      // newer than that. Absent ?since => no replay, identical to prior behavior.
      this.replaySince(pair[1], url.searchParams.get('since'));
      return new Response(null, { status: 101, webSocket: pair[0] });
    }

    if (url.pathname === '/notify' && req.method === 'POST') {
      const payload = await req.text();
      this.buffer(payload);
      const sockets = this.state.getWebSockets();
      let delivered = 0;
      for (const ws of sockets) {
        try {
          ws.send(payload);
          delivered += 1;
        } catch {
          // socket died mid-send; hibernation API reaps it
        }
      }
      return Response.json({ delivered });
    }

    return new Response('not found', { status: 404 });
  }

  async webSocketMessage(ws: WebSocket, message: ArrayBuffer | string): Promise<void> {
    if (message === 'ping') {
      ws.send('pong');
      await this.touchPresence();
      return;
    }
    // No other client->server message types are defined today. Rather than
    // silently swallow them, ignore explicitly here so future protocol additions
    // are an obvious edit point (and unexpected traffic is at least accounted for).
    console.warn('UserGateway: ignoring unknown client message', typeof message === 'string' ? message.slice(0, 64) : '<binary>');
  }

  /** Append a delivered payload to the in-memory ring buffer (best-effort). */
  private buffer(payload: string): void {
    this.recentSeq += 1;
    this.recent.push({ seq: this.recentSeq, ts: Date.now(), payload });
    if (this.recent.length > REPLAY_BUFFER_SIZE) {
      this.recent.splice(0, this.recent.length - REPLAY_BUFFER_SIZE);
    }
  }

  /**
   * Replay buffered events newer than `since` to a single just-connected socket.
   * `since` may be a DO-local buffer seq or a unix-ms timestamp (disambiguated by
   * magnitude). Best-effort only: the buffer is empty after DO eviction, so this
   * can legitimately send nothing even when the client missed events.
   */
  private replaySince(ws: WebSocket, since: string | null): void {
    if (since === null) return; // no ?since => behave exactly as before
    const value = Number(since);
    if (!Number.isFinite(value)) return;
    const byTimestamp = value >= SINCE_TIMESTAMP_THRESHOLD;
    for (const evt of this.recent) {
      const cursor = byTimestamp ? evt.ts : evt.seq;
      if (cursor > value) {
        try {
          ws.send(evt.payload);
        } catch {
          // socket died mid-replay; nothing to reap yet, just stop.
          break;
        }
      }
    }
  }

  async webSocketClose(): Promise<void> {
    await this.touchPresence();
  }

  private async touchPresence(): Promise<void> {
    const uid = await this.state.storage.get<string>('uid');
    if (!uid) return;
    await this.env.DB.prepare('UPDATE users SET last_online = ? WHERE id = ?')
      .bind(Date.now(), uid)
      .run();
  }
}
