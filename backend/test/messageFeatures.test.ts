import { describe, expect, it } from 'vitest';
import { isDefinitivelyInvalidFcmResponse } from '../src/lib/fcm';
import {
  normalizeMessageKind,
  normalizeMessageMetadata,
  normalizePoll,
  parseMentionTokens,
  parseStoredMetadata,
} from '../src/lib/messageFeatures';

describe('mention parsing', () => {
  it('deduplicates usernames and recognizes everyone case-insensitively', () => {
    expect(parseMentionTokens('hey @Alice and @alice, ping @EVERYONE')).toEqual({
      everyone: true,
      usernames: ['alice'],
    });
  });

  it('does not parse fragments inside usernames', () => {
    expect(parseMentionTokens('mail alice@example.com and x@bob')).toEqual({
      everyone: false,
      usernames: [],
    });
  });
});

describe('message feature validation', () => {
  it('normalizes a single-choice anonymous poll', () => {
    expect(normalizePoll({ question: 'Pick one', options: [' A ', 'B'], anonymous: true })).toEqual({
      question: 'Pick one',
      options: ['A', 'B'],
      anonymous: true,
      multipleChoice: false,
    });
  });

  it('rejects duplicate and out-of-range poll options', () => {
    expect(() => normalizePoll({ question: 'x', options: ['A', 'a'] })).toThrow(/unique/);
    expect(() => normalizePoll({ question: 'x', options: ['A'] })).toThrow(/2 to 10/);
    expect(() => normalizePoll({ question: 'x', options: ['A', 'B'], multiple_choice: true })).toThrow(/one choice/);
  });

  it('requires coherent event times and spaces', () => {
    expect(() => normalizeMessageMetadata('event', { title: 'Launch', starts_at: 10 }, false)).toThrow(/spaces/);
    expect(() => normalizeMessageMetadata('event', { title: 'Launch', starts_at: 10, ends_at: 9 }, true)).toThrow(/after/);
    expect(normalizeMessageMetadata('event', { title: 'Launch', starts_at: 10, ends_at: 11 }, true)).toMatchObject({
      title: 'Launch', starts_at: 10, ends_at: 11,
    });
  });

  it('keeps forum-only kinds off the generic message endpoint', () => {
    expect(normalizeMessageKind('sticker')).toBe('sticker');
    expect(() => normalizeMessageKind('forum_post')).toThrow(/forum endpoints/);
    expect(normalizeMessageKind('forum_post', true)).toBe('forum_post');
  });

  it('parses stored metadata defensively', () => {
    expect(parseStoredMetadata('{"x":1}')).toEqual({ x: 1 });
    expect(parseStoredMetadata('[]')).toBeNull();
    expect(parseStoredMetadata('{oops')).toBeNull();
  });
});

describe('FCM token pruning classification', () => {
  it('only classifies definitive registration failures as invalid', () => {
    expect(isDefinitivelyInvalidFcmResponse(404, '{"status":"UNREGISTERED"}')).toBe(true);
    expect(isDefinitivelyInvalidFcmResponse(410, '')).toBe(true);
    expect(isDefinitivelyInvalidFcmResponse(429, 'quota exhausted')).toBe(false);
    expect(isDefinitivelyInvalidFcmResponse(500, 'backend unavailable')).toBe(false);
    expect(isDefinitivelyInvalidFcmResponse(401, 'bad service account')).toBe(false);
  });
});
