import type { Env } from '../types';

/**
 * Explicit referential cleanup (BUGS_AND_ISSUES #53).
 *
 * ## The contract
 *
 * D1 does not enforce foreign keys, and every top-level entity here soft-deletes
 * (`users.deleted_at`, `spaces.deleted_at`, `channels.deleted_at`,
 * `messages.deleted_at`). Nothing dangles, but plenty *lingers*: an account that
 * deleted itself keeps its sessions, push tokens, memberships and reactions
 * forever, and a demolished nest keeps its member rows. Those rows are invisible
 * to every query and yet still receive fanout, still hold personal data, and
 * still grow without bound.
 *
 * So the rule this module implements:
 *
 * - **Content stays.** Messages an account wrote remain in other people's
 *   conversations — deleting them would silently gut threads that other users own.
 *   The author row is soft-deleted, so they render as a deleted user.
 * - **Everything else about the account goes**, immediately, on account deletion:
 *   credentials, devices, delivery targets, memberships, and per-user reactions to
 *   other people's content.
 * - **Container leftovers go on a delay** (see {@link sweepLingeringRows}), because
 *   a soft-deleted space/channel is only certainly dead once nobody has undeleted
 *   it — and unlike account deletion, nothing is waiting on it.
 */

/** How long a soft-deleted container keeps its dependent rows before the sweep
 *  collects them. Long enough to undo an accidental nest demolition by hand. */
const CONTAINER_GRACE_MS = 30 * 24 * 60 * 60 * 1000;

/** Cap per sweep tick so one cron invocation can't blow the D1 statement budget. */
const SWEEP_LIMIT = 500;

/** Statements that take the user id twice (both sides of a symmetric relation). */
const SYMMETRIC = new Set(['friends', 'blocks']);

/**
 * Hard-delete everything we hold *about* a user, keeping the content they wrote.
 *
 * Called from `DELETE /auth/me` after the password check, in the same request as
 * the soft delete of the `users` row. Ordering does not matter — these are all
 * leaf rows — so they go out as one batch.
 *
 * Note what is deliberately NOT here: `messages`, `message_revisions`, `pins`,
 * `audit_log`. The first two are other participants' conversation history, pins
 * belong to the channel rather than the pinner, and the audit trail must survive
 * the thing it audits.
 */
export async function purgeUserData(env: Env, userId: string): Promise<void> {
  const statements: { table: string; sql: string }[] = [
    // Credentials and delivery targets — nothing may authenticate or notify a
    // deleted account.
    { table: 'sessions', sql: 'DELETE FROM sessions WHERE user_id = ?' },
    { table: 'push_tokens', sql: 'DELETE FROM push_tokens WHERE user_id = ?' },
    { table: 'recovery_codes', sql: 'DELETE FROM recovery_codes WHERE user_id = ?' },
    { table: 'login_history', sql: 'DELETE FROM login_history WHERE user_id = ?' },

    // E2EE identity: without these, peers keep wrapping DM keys to devices that
    // will never come back. Envelopes first — they reference the device rows.
    {
      table: 'key_envelopes',
      sql: 'DELETE FROM key_envelopes WHERE to_device IN (SELECT id FROM user_devices WHERE user_id = ?)',
    },
    { table: 'user_devices', sql: 'DELETE FROM user_devices WHERE user_id = ?' },
    { table: 'key_backups', sql: 'DELETE FROM key_backups WHERE user_id = ?' },

    // Membership: stops fanout, unread accounting and member counts from
    // including someone who is gone.
    { table: 'space_members', sql: 'DELETE FROM space_members WHERE user_id = ?' },
    { table: 'channel_members', sql: 'DELETE FROM channel_members WHERE user_id = ?' },

    // Social graph and per-user signals on other people's content.
    { table: 'friends', sql: 'DELETE FROM friends WHERE requester = ? OR addressee = ?' },
    { table: 'blocks', sql: 'DELETE FROM blocks WHERE blocker = ? OR blocked = ?' },
    { table: 'reactions', sql: 'DELETE FROM reactions WHERE user_id = ?' },
    { table: 'poll_votes', sql: 'DELETE FROM poll_votes WHERE user_id = ?' },
    { table: 'forum_likes', sql: 'DELETE FROM forum_likes WHERE user_id = ?' },
    { table: 'message_mentions', sql: 'DELETE FROM message_mentions WHERE user_id = ?' },
    { table: 'super_pin_dismissals', sql: 'DELETE FROM super_pin_dismissals WHERE user_id = ?' },

    // Inbox + preferences + anything queued to send on their behalf.
    { table: 'notifications', sql: 'DELETE FROM notifications WHERE user_id = ?' },
    { table: 'notification_preferences', sql: 'DELETE FROM notification_preferences WHERE user_id = ?' },
    { table: 'scheduled_messages', sql: 'DELETE FROM scheduled_messages WHERE author_id = ?' },
  ];

  await env.DB.batch(
    statements.map(({ table, sql }) =>
      SYMMETRIC.has(table)
        ? env.DB.prepare(sql).bind(userId, userId)
        : env.DB.prepare(sql).bind(userId),
    ),
  );
}

/**
 * Periodic sweep for rows whose container was soft-deleted long enough ago that
 * it is certainly not coming back, plus true orphans.
 *
 * Runs from the existing cron trigger. Every statement is bounded by
 * {@link SWEEP_LIMIT}; anything left over is collected on the next tick, so a
 * large backlog drains gradually instead of timing out the invocation.
 */
export async function sweepLingeringRows(env: Env): Promise<void> {
  const cutoff = Date.now() - CONTAINER_GRACE_MS;

  await env.DB.batch([
    // Memberships of long-demolished nests / channels.
    env.DB.prepare(
      `DELETE FROM space_members WHERE rowid IN (
         SELECT sm.rowid FROM space_members sm JOIN spaces s ON s.id = sm.space_id
         WHERE s.deleted_at IS NOT NULL AND s.deleted_at < ? LIMIT ?)`,
    ).bind(cutoff, SWEEP_LIMIT),
    env.DB.prepare(
      `DELETE FROM channel_members WHERE rowid IN (
         SELECT cm.rowid FROM channel_members cm JOIN channels ch ON ch.id = cm.channel_id
         WHERE ch.deleted_at IS NOT NULL AND ch.deleted_at < ? LIMIT ?)`,
    ).bind(cutoff, SWEEP_LIMIT),
    // Forum tags belonging to a long-dead channel.
    env.DB.prepare(
      `DELETE FROM forum_tags WHERE id IN (
         SELECT ft.id FROM forum_tags ft JOIN channels ch ON ch.id = ft.channel_id
         WHERE ch.deleted_at IS NOT NULL AND ch.deleted_at < ? LIMIT ?)`,
    ).bind(cutoff, SWEEP_LIMIT),
    // Envelopes for device identities that no longer exist (revoked device, or a
    // user purged by the path above) can never be unwrapped by anyone.
    env.DB.prepare(
      `DELETE FROM key_envelopes WHERE id IN (
         SELECT ke.id FROM key_envelopes ke
         LEFT JOIN user_devices d ON d.id = ke.to_device
         WHERE d.id IS NULL LIMIT ?)`,
    ).bind(SWEEP_LIMIT),
    env.DB.prepare(
      `UPDATE threads SET archived_at = COALESCE(archived_at, expires_at)
       WHERE id IN (SELECT id FROM threads WHERE kind = 'branch' AND expires_at IS NOT NULL
       AND expires_at <= ? AND archived_at IS NULL LIMIT ?)`,
    ).bind(Date.now(), SWEEP_LIMIT),
    env.DB.prepare(
      'DELETE FROM bridge_dedup WHERE rowid IN (SELECT rowid FROM bridge_dedup WHERE created_at < ? LIMIT ?)',
    ).bind(cutoff, SWEEP_LIMIT),
  ]);
}
