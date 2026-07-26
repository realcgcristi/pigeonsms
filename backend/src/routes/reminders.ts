import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { assertChannelAccess } from '../lib/channels';
import { snowflake } from '../lib/ids';
import { readJsonBody } from '../lib/validate';
import type { AppEnv, AuthedUser, Env, PushJob } from '../types';

/**
 * Reminders (2.9.5) — "remind me about this at 6pm".
 *
 * Scheduled *messages* already existed (2.8.0); this is the private counterpart.
 * The distinction that drives the whole design: a scheduled message is sent to a
 * conversation, a reminder is delivered only to you. So a reminder never becomes
 * a `messages` row — firing it writes a `notifications` row and a push, and
 * nobody else's channel is touched.
 *
 * Dispatch rides the existing 5-minute cron, which means the practical
 * granularity is ~5 minutes; the API accepts any timestamp and fires at the first
 * tick at or after it.
 */
const reminders = new Hono<AppEnv>();
reminders.use(requireAuth);

const MAX_TEXT = 500;
const MAX_PENDING = 100;
/** A reminder must be in the future, but allow a little slack for clock skew. */
const MIN_LEAD_MS = -60_000;
/** Two years out. Beyond this it's almost certainly a milliseconds/seconds mixup. */
const MAX_LEAD_MS = 2 * 365 * 24 * 60 * 60 * 1000;

interface ReminderRow {
  id: string;
  user_id: string;
  message_id: string | null;
  channel_id: string | null;
  text: string;
  remind_at: number;
  created_at: number;
  fired_at: number | null;
}

function serialize(row: ReminderRow) {
  return {
    id: row.id,
    message_id: row.message_id,
    channel_id: row.channel_id,
    text: row.text,
    remind_at: row.remind_at,
    created_at: row.created_at,
    fired_at: row.fired_at,
  };
}

/** GET /reminders?fired=1 — your pending (or already-fired) reminders. */
reminders.get('/', async (c) => {
  const user = c.get('user') as AuthedUser;
  const fired = c.req.query('fired') === '1';
  const { results } = await c.env.DB.prepare(
    `SELECT * FROM reminders
     WHERE user_id = ? AND fired_at IS ${fired ? 'NOT NULL' : 'NULL'}
     ORDER BY remind_at ASC LIMIT 200`,
  )
    .bind(user.id)
    .all<ReminderRow>();
  return c.json({ reminders: results.map(serialize) });
});

/**
 * POST /reminders { text, remind_at, message_id?, channel_id? }
 *
 * `message_id` makes it a "remind me about this message" — the notification then
 * deep-links back to it. Channel access is verified at creation so a reminder
 * can't be used to probe for channels you can't see.
 */
reminders.post('/', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await readJsonBody(c);

  const text = String(body['text'] ?? '').trim().slice(0, MAX_TEXT);
  if (!text) throw new ApiError(400, 'empty', 'what should I remind you about?');

  const remindAt = Number(body['remind_at']);
  if (!Number.isInteger(remindAt)) {
    throw new ApiError(400, 'bad_time', 'remind_at must be a unix-ms timestamp');
  }
  const lead = remindAt - Date.now();
  if (lead < MIN_LEAD_MS) throw new ApiError(400, 'bad_time', 'that time has already passed');
  if (lead > MAX_LEAD_MS) throw new ApiError(400, 'bad_time', 'that is too far in the future');

  let channelId: string | null = null;
  let messageId: string | null = null;
  if (body['channel_id']) {
    const channel = await assertChannelAccess(c.env, user.id, String(body['channel_id']));
    channelId = channel.id;
    if (body['message_id']) {
      const message = await c.env.DB.prepare(
        'SELECT id FROM messages WHERE id = ? AND channel_id = ?',
      )
        .bind(String(body['message_id']), channel.id)
        .first<{ id: string }>();
      if (!message) throw new ApiError(404, 'not_found', 'no such message in that channel');
      messageId = message.id;
    }
  }

  const pending = await c.env.DB.prepare(
    'SELECT COUNT(*) AS n FROM reminders WHERE user_id = ? AND fired_at IS NULL',
  )
    .bind(user.id)
    .first<{ n: number }>();
  if (Number(pending?.n ?? 0) >= MAX_PENDING) {
    throw new ApiError(400, 'too_many', `you can have ${MAX_PENDING} pending reminders`);
  }

  const row: ReminderRow = {
    id: snowflake(),
    user_id: user.id,
    message_id: messageId,
    channel_id: channelId,
    text,
    remind_at: remindAt,
    created_at: Date.now(),
    fired_at: null,
  };
  await c.env.DB.prepare(
    `INSERT INTO reminders (id, user_id, message_id, channel_id, text, remind_at, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(
      row.id, row.user_id, row.message_id, row.channel_id,
      row.text, row.remind_at, row.created_at,
    )
    .run();

  return c.json({ reminder: serialize(row) }, 201);
});

/** DELETE /reminders/:id — cancel. */
reminders.delete('/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const result = await c.env.DB.prepare('DELETE FROM reminders WHERE id = ? AND user_id = ?')
    .bind(c.req.param('id'), user.id)
    .run();
  if (result.meta.changes === 0) throw new ApiError(404, 'not_found', 'no such reminder');
  return c.json({ ok: true });
});

/**
 * Fire every due reminder. Called from the cron handler.
 *
 * Each row is claimed with a guarded UPDATE (`fired_at IS NULL`) before anything
 * is delivered, so two overlapping cron ticks can never double-notify: only the
 * tick whose UPDATE reports a change proceeds. Delivery failures are logged and
 * the row stays claimed rather than retried forever — a reminder that arrives
 * twice is worse than one that arrives late.
 */
export async function dispatchDueReminders(env: Env): Promise<void> {
  const now = Date.now();
  const { results: due } = await env.DB.prepare(
    'SELECT * FROM reminders WHERE fired_at IS NULL AND remind_at <= ? ORDER BY remind_at ASC LIMIT 100',
  )
    .bind(now)
    .all<ReminderRow>();

  for (const row of due) {
    const claimed = await env.DB.prepare(
      'UPDATE reminders SET fired_at = ? WHERE id = ? AND fired_at IS NULL',
    )
      .bind(now, row.id)
      .run();
    if (claimed.meta.changes === 0) continue;

    try {
      await env.DB.prepare(
        `INSERT INTO notifications (id, user_id, kind, message_id, channel_id, actor_id, title, body, data, created_at)
         VALUES (?, ?, 'reminder', ?, ?, NULL, ?, ?, ?, ?)`,
      )
        .bind(
          snowflake(),
          row.user_id,
          row.message_id,
          row.channel_id,
          'Reminder',
          row.text,
          JSON.stringify({
            type: 'reminder',
            reminder_id: row.id,
            channel_id: row.channel_id,
            message_id: row.message_id,
          }),
          now,
        )
        .run();

      const job: PushJob = {
        user_id: row.user_id,
        title: 'Reminder',
        body: row.text,
        data: {
          type: 'reminder',
          reminder_id: row.id,
          ...(row.channel_id ? { channel_id: row.channel_id } : {}),
          ...(row.message_id ? { message_id: row.message_id } : {}),
        },
      };
      await env.PUSH_QUEUE.send(job);
    } catch (err) {
      console.error('cron: reminder delivery failed', { id: row.id, err });
    }
  }
}

export default reminders;
