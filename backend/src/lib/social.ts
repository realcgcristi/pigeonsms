const EMOJI_LIKE = /\p{Extended_Pictographic}|\p{Regional_Indicator}|[0-9#*]\uFE0F?\u20E3/u;
const ASCII_CONTROL_OR_SPACE = /[\u0000-\u0020\u007f-\u009f]/u;

/**
 * A custom-emoji reaction, stored as `custom:<space_emojis.id>` (2.9.5).
 *
 * The id rather than the `:shortcode:` on purpose: renaming an emoji must not
 * orphan every reaction that used it, and two nests can legitimately use the
 * same shortcode for different images.
 */
const CUSTOM_EMOJI_RE = /^custom:\d{1,25}$/;

/** Decode and bound a reaction: a Unicode emoji, or a `custom:<id>` reference. */
export function normalizeReactionEmoji(raw: string): string | null {
  let value: string;
  try {
    value = decodeURIComponent(raw).trim();
  } catch {
    return null;
  }

  // Custom emoji bypass the pictographic test — the payload is an id, not a
  // glyph. Whether the id actually resolves is checked at the call site, which
  // knows which nest the message lives in.
  if (CUSTOM_EMOJI_RE.test(value)) return value;

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
