import { Hono } from 'hono';
import { ApiError } from '../middleware/errors';
import { requireAuth } from '../middleware/auth';
import { snowflake } from '../lib/ids';
import { logSwallowed, readJsonBody } from '../lib/validate';
import type { AppEnv, AuthedUser, Env } from '../types';

/**
 * Resumable uploads (2.9.5), backed by R2 multipart.
 *
 * `POST /media/upload` streams a whole body into R2 in one request, so a dropped
 * connection at 95% means starting from zero — which is a large part of why the
 * cap sat at 50 MB. Here the client:
 *
 *   1. `POST /uploads` to open a session (server creates the R2 multipart upload),
 *   2. `PUT /uploads/:id/parts/:n` for each chunk, in any order, retrying
 *      individual chunks as needed,
 *   3. `POST /uploads/:id/complete` to finalise, or `DELETE` to abort.
 *
 * The session lives in D1, so progress survives an app restart: `GET /uploads/:id`
 * reports which parts already landed and the client resumes from there.
 *
 * Why parts are stored in D1 at all: completing an R2 multipart upload requires
 * handing back every part's number *and* etag together. R2 will not enumerate
 * them for us, so if we didn't persist each etag as it arrived, a client that
 * restarted mid-upload could never finish the transfer it had already paid for.
 */
const uploads = new Hono<AppEnv>();
uploads.use(requireAuth);

/** 500 MB — an order of magnitude past the single-shot path. */
const MAX_TOTAL = 500 * 1024 * 1024;
/** R2 requires every part except the last to be at least 5 MiB. */
const MIN_PART = 5 * 1024 * 1024;
const MAX_PART = 100 * 1024 * 1024;
const MAX_PARTS = 1000;
const MAX_OPEN_SESSIONS = 5;

interface SessionRow {
  id: string;
  owner_id: string;
  key: string;
  r2_upload_id: string;
  filename: string | null;
  content_type: string;
  total_size: number;
  part_size: number;
  created_at: number;
  completed_at: number | null;
  aborted_at: number | null;
}

/** Load a session and assert the caller owns it. */
async function loadSession(env: Env, userId: string, id: string): Promise<SessionRow> {
  const row = await env.DB.prepare('SELECT * FROM upload_sessions WHERE id = ?')
    .bind(id)
    .first<SessionRow>();
  if (!row) throw new ApiError(404, 'not_found', 'no such upload');
  // 404 rather than 403: someone else's upload id shouldn't be confirmable.
  if (row.owner_id !== userId) throw new ApiError(404, 'not_found', 'no such upload');
  return row;
}

/** POST /uploads { filename, content_type, total_size, part_size? } */
uploads.post('/', async (c) => {
  const user = c.get('user') as AuthedUser;
  const body = await readJsonBody(c);

  const totalSize = Number(body['total_size']);
  if (!Number.isInteger(totalSize) || totalSize <= 0 || totalSize > MAX_TOTAL) {
    throw new ApiError(413, 'too_big', `max ${Math.floor(MAX_TOTAL / 1024 / 1024)}mb`);
  }
  const partSize = Number.isInteger(body['part_size']) ? Number(body['part_size']) : MIN_PART;
  if (partSize < MIN_PART || partSize > MAX_PART) {
    throw new ApiError(400, 'bad_part_size', 'part_size must be between 5mb and 100mb');
  }
  if (Math.ceil(totalSize / partSize) > MAX_PARTS) {
    throw new ApiError(400, 'too_many_parts', 'use a larger part_size');
  }

  const contentType = String(body['content_type'] ?? 'application/octet-stream').slice(0, 128);
  const filename = body['filename'] === undefined ? null : String(body['filename']).slice(0, 200);

  // Bound how many half-finished uploads one account can hold open, since each
  // one is R2 storage nobody can see or delete yet.
  const open = await c.env.DB.prepare(
    'SELECT COUNT(*) AS n FROM upload_sessions WHERE owner_id = ? AND completed_at IS NULL AND aborted_at IS NULL',
  )
    .bind(user.id)
    .first<{ n: number }>();
  if (Number(open?.n ?? 0) >= MAX_OPEN_SESSIONS) {
    throw new ApiError(400, 'too_many', 'finish or cancel your other uploads first');
  }

  const id = snowflake();
  const key = `u/${user.id}/${id}`;
  const multipart = await c.env.MEDIA.createMultipartUpload(key, {
    httpMetadata: { contentType },
    customMetadata: { uploader: user.id },
  });

  const now = Date.now();
  await c.env.DB.prepare(
    `INSERT INTO upload_sessions (id, owner_id, key, r2_upload_id, filename, content_type, total_size, part_size, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(id, user.id, key, multipart.uploadId, filename, contentType, totalSize, partSize, now)
    .run();

  return c.json({
    upload: {
      id,
      key,
      part_size: partSize,
      total_size: totalSize,
      part_count: Math.ceil(totalSize / partSize),
    },
  }, 201);
});

/** GET /uploads/:id — progress, so a restarted client knows what to resend. */
uploads.get('/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const session = await loadSession(c.env, user.id, c.req.param('id') ?? '');
  const { results } = await c.env.DB.prepare(
    'SELECT part_number, size FROM upload_parts WHERE session_id = ? ORDER BY part_number',
  )
    .bind(session.id)
    .all<{ part_number: number; size: number }>();

  const received = results.reduce((sum, part) => sum + Number(part.size), 0);
  return c.json({
    upload: {
      id: session.id,
      key: session.key,
      part_size: session.part_size,
      total_size: session.total_size,
      part_count: Math.ceil(session.total_size / session.part_size),
      completed: session.completed_at !== null,
      aborted: session.aborted_at !== null,
      received_bytes: received,
      uploaded_parts: results.map((part) => Number(part.part_number)),
    },
  });
});

/**
 * PUT /uploads/:id/parts/:n — raw chunk body.
 *
 * Idempotent: re-uploading a part replaces its etag, so a client that isn't sure
 * whether a chunk landed can simply send it again.
 */
uploads.put('/:id/parts/:n', async (c) => {
  const user = c.get('user') as AuthedUser;
  const session = await loadSession(c.env, user.id, c.req.param('id') ?? '');
  if (session.completed_at !== null) throw new ApiError(400, 'completed', 'this upload is finished');
  if (session.aborted_at !== null) throw new ApiError(400, 'aborted', 'this upload was cancelled');

  const partNumber = Number(c.req.param('n'));
  const partCount = Math.ceil(session.total_size / session.part_size);
  if (!Number.isInteger(partNumber) || partNumber < 1 || partNumber > partCount) {
    throw new ApiError(400, 'bad_part', `part must be between 1 and ${partCount}`);
  }

  const bytes = await c.req.arrayBuffer();
  if (bytes.byteLength === 0) throw new ApiError(400, 'empty_part', 'part body is empty');
  // Every part but the last must be exactly part_size — R2 rejects undersized
  // middle parts at completion time, and failing here points at the real chunk
  // instead of failing the whole transfer minutes later.
  const isLast = partNumber === partCount;
  if (!isLast && bytes.byteLength !== session.part_size) {
    throw new ApiError(
      400,
      'bad_part_size',
      `part ${partNumber} must be exactly ${session.part_size} bytes`,
    );
  }
  if (bytes.byteLength > session.part_size) {
    throw new ApiError(400, 'bad_part_size', 'part is larger than the agreed part_size');
  }

  const multipart = c.env.MEDIA.resumeMultipartUpload(session.key, session.r2_upload_id);
  const uploaded = await multipart.uploadPart(partNumber, bytes);

  await c.env.DB.prepare(
    `INSERT INTO upload_parts (session_id, part_number, etag, size, uploaded_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT (session_id, part_number) DO UPDATE SET
       etag = excluded.etag, size = excluded.size, uploaded_at = excluded.uploaded_at`,
  )
    .bind(session.id, partNumber, uploaded.etag, bytes.byteLength, Date.now())
    .run();

  return c.json({ ok: true, part_number: partNumber, size: bytes.byteLength });
});

/**
 * POST /uploads/:id/complete — finalise and register the object.
 *
 * Returns the same attachment descriptor `/media/upload` does, so the caller can
 * hand it straight to the message send path with no branching.
 */
uploads.post('/:id/complete', async (c) => {
  const user = c.get('user') as AuthedUser;
  const session = await loadSession(c.env, user.id, c.req.param('id') ?? '');
  if (session.aborted_at !== null) throw new ApiError(400, 'aborted', 'this upload was cancelled');
  if (session.completed_at !== null) {
    return c.json({
      attachment: {
        key: session.key,
        name: session.filename,
        type: session.content_type,
        size: session.total_size,
      },
    });
  }

  const { results: parts } = await c.env.DB.prepare(
    'SELECT part_number, etag, size FROM upload_parts WHERE session_id = ? ORDER BY part_number',
  )
    .bind(session.id)
    .all<{ part_number: number; etag: string; size: number }>();

  const expected = Math.ceil(session.total_size / session.part_size);
  if (parts.length !== expected) {
    throw new ApiError(400, 'incomplete', `expected ${expected} parts, have ${parts.length}`);
  }
  // Guard against a gap: 1..n must all be present, or R2 fails opaquely.
  for (let i = 0; i < parts.length; i++) {
    if (Number(parts[i]?.part_number) !== i + 1) {
      throw new ApiError(400, 'incomplete', `missing part ${i + 1}`);
    }
  }

  const multipart = c.env.MEDIA.resumeMultipartUpload(session.key, session.r2_upload_id);
  const object = await multipart.complete(
    parts.map((part) => ({ partNumber: Number(part.part_number), etag: part.etag })),
  );

  const now = Date.now();
  // Trust R2's reported size over the client's declared total — the same reason
  // the single-shot path re-heads the object instead of believing content-length.
  const actualSize = object.size ?? parts.reduce((sum, p) => sum + Number(p.size), 0);
  await c.env.DB.batch([
    c.env.DB.prepare(
      `INSERT OR REPLACE INTO media_objects (key, owner_id, purpose, content_type, size, created_at)
       VALUES (?, ?, 'attachment', ?, ?, ?)`,
    ).bind(session.key, user.id, session.content_type, actualSize, now),
    c.env.DB.prepare('UPDATE upload_sessions SET completed_at = ? WHERE id = ?')
      .bind(now, session.id),
    c.env.DB.prepare('DELETE FROM upload_parts WHERE session_id = ?').bind(session.id),
  ]);

  return c.json({
    attachment: {
      key: session.key,
      name: session.filename,
      type: session.content_type,
      size: actualSize,
    },
  });
});

/** DELETE /uploads/:id — abort and release the R2 storage. */
uploads.delete('/:id', async (c) => {
  const user = c.get('user') as AuthedUser;
  const session = await loadSession(c.env, user.id, c.req.param('id') ?? '');
  if (session.completed_at !== null) {
    throw new ApiError(400, 'completed', 'this upload already finished');
  }

  const multipart = c.env.MEDIA.resumeMultipartUpload(session.key, session.r2_upload_id);
  // Best effort: if R2 has already dropped the multipart upload, the session
  // still needs to be marked aborted or it counts against the open-session cap
  // forever.
  await multipart.abort().catch((err) => logSwallowed('uploads.abort', err));

  await c.env.DB.batch([
    c.env.DB.prepare('UPDATE upload_sessions SET aborted_at = ? WHERE id = ?')
      .bind(Date.now(), session.id),
    c.env.DB.prepare('DELETE FROM upload_parts WHERE session_id = ?').bind(session.id),
  ]);
  return c.json({ ok: true });
});

export default uploads;
