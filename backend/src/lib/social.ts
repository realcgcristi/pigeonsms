const EMOJI_LIKE = /\p{Extended_Pictographic}|\p{Regional_Indicator}|[0-9#*]\uFE0F?\u20E3/u;
const ASCII_CONTROL_OR_SPACE = /[\u0000-\u0020\u007f-\u009f]/u;

/** Decode and bound a Unicode reaction without breaking ZWJ emoji sequences. */
export function normalizeReactionEmoji(raw: string): string | null {
  let value: string;
  try {
    value = decodeURIComponent(raw).trim();
  } catch {
    return null;
  }

  if (
    !value ||
    [...value].length > 16 ||
    new TextEncoder().encode(value).length > 64 ||
    ASCII_CONTROL_OR_SPACE.test(value) ||
    !EMOJI_LIKE.test(value)
  ) {
    return null;
  }
  return value;
}

/**
 * Explicit keys make retries safe. Legacy clients get one active space per
 * normalized name, preventing rapid repeated taps from creating duplicates.
 */
export function spaceCreationKey(name: string, supplied?: unknown): string | null {
  if (supplied === undefined || supplied === null) {
    return `legacy:${name.normalize('NFKC').toLowerCase()}`;
  }

  const value = String(supplied).trim();
  if (!value || value.length > 128 || ASCII_CONTROL_OR_SPACE.test(value)) return null;
  return `client:${value}`;
}

/** Keep profile media raster/bitmap-like; SVG is unsafe to serve inline. */
export function normalizeProfileImageType(raw: string): string | null {
  const value = raw.split(';', 1)[0]?.trim().toLowerCase() ?? '';
  if (!/^image\/[a-z0-9.+-]+$/.test(value) || value === 'image/svg+xml') return null;
  return value;
}
