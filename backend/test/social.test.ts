import { describe, expect, it } from 'vitest';
import {
  normalizeProfileImageType,
  normalizeReactionEmoji,
  spaceCreationKey,
} from '../src/lib/social';

describe('reaction emoji normalization', () => {
  it('accepts encoded, joined, and variation-selector emoji', () => {
    expect(normalizeReactionEmoji('%F0%9F%98%80')).toBe('😀');
    expect(normalizeReactionEmoji('👨‍👩‍👧‍👦')).toBe('👨‍👩‍👧‍👦');
    expect(normalizeReactionEmoji('❤️')).toBe('❤️');
  });

  it('rejects malformed paths, text, whitespace, and oversized values', () => {
    expect(normalizeReactionEmoji('%')).toBeNull();
    expect(normalizeReactionEmoji('nice')).toBeNull();
    expect(normalizeReactionEmoji('😀 😀')).toBeNull();
    expect(normalizeReactionEmoji('😀'.repeat(17))).toBeNull();
  });
});

describe('space creation keys', () => {
  it('keeps explicit retries stable across request payload changes', () => {
    expect(spaceCreationKey('First name', ' request-123 ')).toBe('client:request-123');
    expect(spaceCreationKey('Renamed', 'request-123')).toBe('client:request-123');
  });

  it('deduplicates legacy clients by normalized active-space name', () => {
    expect(spaceCreationKey('Ｍy Space')).toBe('legacy:my space');
    expect(spaceCreationKey('MY SPACE')).toBe('legacy:my space');
  });

  it('rejects unusable explicit keys', () => {
    expect(spaceCreationKey('Space', '')).toBeNull();
    expect(spaceCreationKey('Space', 'two words')).toBeNull();
    expect(spaceCreationKey('Space', 'x'.repeat(129))).toBeNull();
  });
});

describe('profile image content types', () => {
  it('normalizes images and rejects inline SVG or non-images', () => {
    expect(normalizeProfileImageType('Image/PNG; charset=binary')).toBe('image/png');
    expect(normalizeProfileImageType('image/webp')).toBe('image/webp');
    expect(normalizeProfileImageType('image/svg+xml')).toBeNull();
    expect(normalizeProfileImageType('text/html')).toBeNull();
  });
});
