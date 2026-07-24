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

/** Hard cap on how many buffered events a single resume request may replay to a
 *  reconnecting socket, regardless of how many channels it asked about. Prevents
 *  a client with a very stale cursor set from being flooded (and from us walking
 *  the buffer once per channel). If we would exceed it, we stop replaying and
 *  tell the client to backfill from D1 instead (see `resume` frame below). */
const MAX_REPLAY_EVENTS = REPLAY_BUFFER_SIZE;

/** A per-channel resume cursor map the client may send on connect: the highest
 *  message `seq` it has durably applied for each channel. We replay buffered
 *  message-shaped events whose `d.channel_id` matches and `d.seq` is greater. */
type ResumeCursors = Record<string, number>;

/** Best-effort extraction of `{ channel_id, seq }` from a buffered event's `d`.
 *  Only message-shaped events (message.new/edit/delete, mention.new, …) carry
 *  a per-channel seq; everything else returns null and is ignored by per-channel
 *  resume (those still flow through the DO-local `?since=` buffer path). */
function channelSeqOf(payload: string): { channelId: string; seq: number } | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(payload) as unknown;
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) return null;
  const d = (parsed as { d?: unknown }).d;
  if (typeof d !== 'object' || d === null) return null;
  const channelId = (d as { channel_id?: unknown }).channel_id;
  const seq = (d as { seq?: unknown }).seq;
  if (typeof channelId !== 'string' || typeof seq !== 'number' || !Number.isFinite(seq)) {
    return null;
  }
  return { channelId, seq };
}

/** Parse the optional `?resume=` query value: a base64url- or plain-encoded JSON
 *  object of `{ [channelId]: lastSeq }`. Malformed input yields null (no resume),
 *  never an error — resume is strictly additive and must never break connect. */
function parseResumeCursors(raw: string | null): ResumeCursors | null {
  if (!raw) return null;
  let text = raw;
  // Accept a base64(url) blob too, since a cursor map can grow past what fits
  // comfortably in a bare query param; fall back to treating it as raw JSON.
  try {
    text = atob(raw.replace(/-/g, '+').replace(/_/g, '/'));
  } catch {
    text = raw;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text) as unknown;
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
  const cursors: ResumeCursors = {};
  for (const [channelId, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (typeof value === 'number' && Number.isFinite(value)) cursors[channelId] = value;
  }
  return Object.keys(cursors).length ? cursors : null;
}

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
      // Optional gap replay. Two complementary, additive mechanisms — a client
      // may use either, both, or neither (absent => identical to prior behavior):
      //   ?since=  — DO-local buffer cursor / unix-ms (2.7.0). Whole-buffer replay.
      //   ?resume= — per-channel { channelId: lastSeq } map (2.8.0). Replays only
      //              message-shaped events newer than each channel's cursor.
      // We run resume first (targeted, seq-aware) then since (catch-all); dedup is
      // the client's job via the per-event seq it already tracks.
      const replayedByResume = this.replayResume(pair[1], parseResumeCursors(url.searchParams.get('resume')));
      if (!replayedByResume) this.replaySince(pair[1], url.searchParams.get('since'));
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

  /**
   * Per-channel resume: replay buffered message-shaped events whose
   * `d.channel_id` is present in `cursors` and whose `d.seq` exceeds the client's
   * last-applied seq for that channel. Returns true iff resume was attempted
   * (i.e. the client sent a usable cursor map) so the caller can skip the
   * whole-buffer `?since=` path and avoid double-sending.
   *
   * Best-effort and capped: the buffer is empty after DO eviction (so this can
   * legitimately send nothing even when events were missed), and we never send
   * more than MAX_REPLAY_EVENTS. On either shortfall we emit a single
   * `gateway.resume` control frame telling the client the replay was incomplete
   * so it backfills the affected channels from D1 (the durable source of truth).
   */
  private replayResume(ws: WebSocket, cursors: ResumeCursors | null): boolean {
    if (!cursors) return false;

    // The buffer only holds the tail of history. If the client's cursor for a
    // channel is older than the oldest thing we still have, we cannot prove we
    // have every gap event — flag that channel as needing a D1 backfill.
    const gappy = new Set<string>();
    let sent = 0;
    let capped = false;

    for (const evt of this.recent) {
      if (sent >= MAX_REPLAY_EVENTS) {
        capped = true;
        break;
      }
      const info = channelSeqOf(evt.payload);
      if (!info) continue;
      const cursor = cursors[info.channelId];
      if (cursor === undefined) continue; // client did not ask about this channel
      if (info.seq <= cursor) continue; // already applied
      try {
        ws.send(evt.payload);
        sent += 1;
      } catch {
        // socket died mid-replay; stop and let the client reconnect+backfill.
        capped = true;
        break;
      }
    }

    // Any requested channel whose cursor sits below the buffer's retained floor
    // (or that got truncated by the cap) can have silently missed events between
    // its cursor and what we replayed. We cannot cheaply prove otherwise from an
    // in-memory ring, so conservatively tell the client to backfill every channel
    // it asked about when we hit the cap; when uncapped, only flag ones whose
    // cursor predates our oldest buffered event for that channel.
    if (capped) {
      for (const channelId of Object.keys(cursors)) gappy.add(channelId);
    } else {
      const oldestSeqByChannel = new Map<string, number>();
      for (const evt of this.recent) {
        const info = channelSeqOf(evt.payload);
        if (!info) continue;
        const prev = oldestSeqByChannel.get(info.channelId);
        if (prev === undefined || info.seq < prev) oldestSeqByChannel.set(info.channelId, info.seq);
      }
      for (const [channelId, cursor] of Object.entries(cursors)) {
        const oldest = oldestSeqByChannel.get(channelId);
        // If we have buffered events for this channel but the oldest one is more
        // than one past the client's cursor, there is a hole below our window.
        if (oldest !== undefined && oldest > cursor + 1) gappy.add(channelId);
        // If we hold NOTHING for this channel we cannot tell whether it was idle
        // or evicted; leave it un-flagged to avoid forcing needless backfills on
        // the common quiet-channel case — the client's own reconnect fetch covers it.
      }
    }

    if (gappy.size > 0) {
      try {
        ws.send(JSON.stringify({
          t: 'gateway.resume',
          d: { incomplete: true, backfill: [...gappy] },
        }));
      } catch {
        // socket already gone; the client will reconnect and try again.
      }
    }
    return true;
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
