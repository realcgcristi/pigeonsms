import { ApiError } from '../middleware/errors';
import type { Env } from '../types';

export interface AttachmentInput {
  key: string;
  name: string | null;
  type: string;
  size: number;
}

interface MediaRow {
  key: string;
  owner_id: string;
  content_type: string;
  size: number;
}

export async function assertOwnedAttachment(
  env: Env,
  userId: string,
  raw: { key?: string; name?: string; type?: string; size?: number },
): Promise<AttachmentInput> {
  const key = String(raw.key ?? '').trim();
  if (!key) throw new ApiError(400, 'bad_attachment', 'attachment key is required');

  let media = await env.DB.prepare(
    'SELECT key, owner_id, content_type, size FROM media_objects WHERE key = ?',
  )
    .bind(key)
    .first<MediaRow>();

  if (!media) {
    const object = await env.MEDIA.head(key);
    if (!object) throw new ApiError(400, 'bad_attachment', 'attachment does not exist');
    const owner = object.customMetadata?.['uploader'];
    if (!owner || owner !== userId) throw new ApiError(403, 'forbidden_attachment', 'not your upload');
    const contentType = object.httpMetadata?.contentType ?? String(raw.type ?? 'application/octet-stream');
    await env.DB.prepare(
      `INSERT OR IGNORE INTO media_objects
       (key, owner_id, purpose, content_type, size, created_at) VALUES (?, ?, 'attachment', ?, ?, ?)`,
    )
      .bind(key, userId, contentType, object.size, Date.now())
      .run();
    media = { key, owner_id: owner, content_type: contentType, size: object.size };
  }

  if (media.owner_id !== userId) throw new ApiError(403, 'forbidden_attachment', 'not your upload');
  return {
    key,
    name: raw.name === undefined ? null : String(raw.name).slice(0, 128),
    type: media.content_type.slice(0, 128),
    size: Number(media.size),
  };
}
