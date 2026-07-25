import { ApiError } from '../middleware/errors';
import type { ChannelRow } from './channels';
import type { Env } from '../types';

/**
 * Worker-side client for the DO-owned message sequencer (see `do/seq.ts`).
 *
 * Every channel has exactly one sequencer DO, addressed by `idFromName(channelId)`:
 * space channels live in the `SPACE` namespace, DMs in `DM_CHANNEL`, matching the
 * DO that already owns presence/typing for that channel. One channel therefore
 * always resolves to one actor — which is what makes allocation serial.
 *
 * Deliberately no D1 fallback. Falling back to the old `bumpSeq` when the DO is
 * unreachable would fork the counter: D1's mirror only advances on a *successful
 * insert*, so it can legitimately sit below numbers the DO has already handed out
 * for in-flight sends, and a fallback allocation could duplicate one of them. The
 * UNIQUE index on `messages(channel_id, seq)` would turn that into a 500 anyway,
 * so we fail the send cleanly (503) instead of corrupting ordering.
 */

/** The DO namespace that owns sequencing for this channel. */
function namespaceFor(env: Env, channel: ChannelRow): DurableObjectNamespace {
  return channel.space_id ? env.SPACE : env.DM_CHANNEL;
}

/**
 * Claim [count] consecutive sequence numbers for [channel], returned lowest-first.
 *
 * Retries once: a DO can be mid-eviction or the request can lose a race with a
 * restart, both of which are transient and safe to repeat — allocation is not
 * idempotent, but a retried call simply claims a fresh (higher) range, and an
 * abandoned range is harmless now that `channels.last_seq` only tracks inserted
 * rows.
 */
export async function allocateSeqRange(
  env: Env,
  channel: ChannelRow,
  count = 1,
): Promise<number[]> {
  const namespace = namespaceFor(env, channel);
  const stub = namespace.get(namespace.idFromName(channel.id));
  const body = JSON.stringify({ count });

  let lastError: unknown = null;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = await stub.fetch(
        `https://channel/channels/${encodeURIComponent(channel.id)}/seq`,
        { method: 'POST', body },
      );
      if (!res.ok) {
        lastError = new Error(`sequencer responded ${res.status}`);
        continue;
      }
      const { start, end } = await res.json<{ start: number; end: number }>();
      if (!Number.isInteger(start) || !Number.isInteger(end) || end < start) {
        lastError = new Error(`sequencer returned a bad range ${start}..${end}`);
        continue;
      }
      const seqs: number[] = [];
      for (let seq = start; seq <= end; seq++) seqs.push(seq);
      return seqs;
    } catch (err) {
      lastError = err;
    }
  }

  console.error('sequencer unavailable', { channelId: channel.id, err: lastError });
  throw new ApiError(503, 'seq_unavailable', 'could not allocate a sequence number, try again');
}

/** Claim the next sequence number for [channel]. */
export async function allocateSeq(env: Env, channel: ChannelRow): Promise<number> {
  const [seq] = await allocateSeqRange(env, channel, 1);
  // allocateSeqRange either returns a non-empty range or throws; the `?? 0` only
  // satisfies the optional-index type.
  return seq ?? 0;
}

/**
 * Statement that advances the D1 mirror of a channel's sequence.
 *
 * Include this in the SAME batch as the message INSERT. The guard (`last_seq < ?`)
 * makes it a monotonic max rather than a read-modify-write, so out-of-order commits
 * can't move the mirror backwards and it never needs its own round-trip.
 */
export function mirrorSeqStatement(env: Env, channelId: string, seq: number): D1PreparedStatement {
  return env.DB.prepare('UPDATE channels SET last_seq = ? WHERE id = ? AND last_seq < ?')
    .bind(seq, channelId, seq);
}
