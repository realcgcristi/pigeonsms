import type { Env } from '../types';

/**
 * Per-channel message sequence allocation, owned by the channel's Durable Object.
 *
 * ## Why this moved off D1
 *
 * Until 2.9.0 every send allocated its `seq` with a single-row read-modify-write
 * against D1 (`UPDATE channels SET last_seq = last_seq + 1 ... RETURNING`). That
 * made one row the serialization point for every message in a channel, which is
 * exactly the contention hotspot the DO architecture exists to remove. It also
 * had to allocate in a *separate statement* from the insert, so a failed insert
 * burned the number and left a permanent gap — patched over by a `releaseSeq`
 * compensation that could itself lose a race.
 *
 * The DO is the natural owner: it is already the single-threaded actor for the
 * channel, so allocation is serialized for free, needs no distributed lock, and
 * never contends with unrelated D1 writes.
 *
 * ## D1 stays a mirror, not the allocator
 *
 * `channels.last_seq` is still written — but as `MAX(last_seq, inserted_seq)` in
 * the same batch as the message INSERT, so it now tracks the *highest actually
 * inserted* seq rather than the highest ever handed out. Read paths that depend
 * on it (`has_more_after`, unread counts, the DM last-message join) get more
 * accurate answers than before, and a burned allocation no longer corrupts them.
 * That is also what makes this migration self-healing: if a DO's storage is ever
 * reset, {@link ChannelSequencer.seed} rebuilds the counter from the D1 high-water
 * mark, so numbering continues instead of restarting.
 *
 * ## Ordering guarantee
 *
 * Allocation happens inside `blockConcurrencyWhile`, so the read-modify-write of
 * the stored counter cannot interleave with another in-flight request to the same
 * DO. Sequence numbers are strictly increasing per channel and never reused.
 */

const SEQ_KEY = 'channel:seq';
const CHANNEL_KEY = 'channel:id';

/** Upper bound on a single allocation request — a bulk import shouldn't be able
 *  to claim an unbounded range in one call. */
const MAX_BATCH = 100;

export interface SeqAllocation {
  /** First sequence number in the allocated range (inclusive). */
  start: number;
  /** Last sequence number in the allocated range (inclusive). */
  end: number;
}

/** `/channels/:id/seq` — the only internal endpoint the sequencer exposes. */
export function seqPath(pathname: string): string | null {
  const parts = pathname.split('/').filter(Boolean);
  if (parts.length !== 3 || parts[0] !== 'channels' || parts[2] !== 'seq') return null;
  try {
    const channelId = decodeURIComponent(parts[1] ?? '');
    return channelId || null;
  } catch {
    return null;
  }
}

export class ChannelSequencer {
  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  /**
   * Claim the next [count] sequence numbers for [channelId].
   *
   * Serialized with `blockConcurrencyWhile`: without it, two concurrent requests
   * to this DO could both read the counter before either wrote it back, and hand
   * out the same seq twice (which the UNIQUE index on `messages(channel_id, seq)`
   * would then reject as a 500 on an otherwise valid send).
   */
  async allocate(channelId: string, count = 1): Promise<SeqAllocation> {
    const n = Math.min(Math.max(Math.trunc(count) || 1, 1), MAX_BATCH);
    return this.state.blockConcurrencyWhile(async () => {
      await this.assertChannel(channelId);
      let current = await this.state.storage.get<number>(SEQ_KEY);
      if (current === undefined) current = await this.seed(channelId);
      const end = current + n;
      await this.state.storage.put(SEQ_KEY, end);
      return { start: current + 1, end };
    });
  }

  /** Handle `POST /channels/:id/seq` from the Worker. Bodyless = allocate one. */
  async handle(req: Request, channelId: string): Promise<Response> {
    if (req.method !== 'POST') {
      return Response.json(
        { error: { code: 'method_not_allowed', message: 'POST required' } },
        { status: 405, headers: { 'cache-control': 'no-store' } },
      );
    }
    let count = 1;
    const raw = await req.text().catch(() => '');
    if (raw.trim()) {
      try {
        const parsed = JSON.parse(raw) as { count?: unknown };
        if (typeof parsed.count === 'number') count = parsed.count;
      } catch {
        // A malformed internal body means a bug on our side, not a client's —
        // allocating one is the safe interpretation and the caller still gets a
        // usable seq rather than a failed send.
      }
    }
    try {
      const allocation = await this.allocate(channelId, count);
      return Response.json(allocation, { headers: { 'cache-control': 'no-store' } });
    } catch (err) {
      console.error('seq allocation failed', { channelId, err });
      return Response.json(
        { error: { code: 'seq_unavailable', message: 'could not allocate a sequence number' } },
        { status: 503, headers: { 'cache-control': 'no-store' } },
      );
    }
  }

  /**
   * Pin this DO to one channel id. The Worker addresses the DO with
   * `idFromName(channelId)`, so a mismatch means a routing bug upstream — and a
   * silent one would seed the counter from the wrong channel's history and
   * scramble ordering for both. Fail loudly instead.
   */
  private async assertChannel(channelId: string): Promise<void> {
    const known = await this.state.storage.get<string>(CHANNEL_KEY);
    if (known === undefined) {
      await this.state.storage.put(CHANNEL_KEY, channelId);
      return;
    }
    if (known !== channelId) {
      throw new Error(`sequencer bound to channel ${known}, got ${channelId}`);
    }
  }

  /**
   * Cold start: adopt the highest sequence number D1 has ever seen for this
   * channel. `MAX(messages.seq)` is the truth for what exists; `channels.last_seq`
   * can be higher if a pre-2.9.0 allocation was burned by a failed insert. Taking
   * the max of both means we never re-issue a number that was already handed out.
   */
  private async seed(channelId: string): Promise<number> {
    const row = await this.env.DB.prepare(
      `SELECT COALESCE((SELECT MAX(seq) FROM messages WHERE channel_id = ?), 0) AS max_seq,
              COALESCE((SELECT last_seq FROM channels WHERE id = ?), 0) AS last_seq`,
    )
      .bind(channelId, channelId)
      .first<{ max_seq: number; last_seq: number }>();
    return Math.max(Number(row?.max_seq ?? 0), Number(row?.last_seq ?? 0));
  }
}
