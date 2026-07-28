import { describe, expect, it } from 'vitest';
import { sessionTokenFromCookie } from '../src/middleware/auth';

describe('session cookie parsing', () => {
  it('reads and decodes the session token among other cookies', () => {
    expect(sessionTokenFromCookie('theme=dark; pigeon_session=abc%2F123%3D; flag=1')).toBe('abc/123=');
  });

  it('preserves equals signs in token values', () => {
    expect(sessionTokenFromCookie('pigeon_session=part=two=three')).toBe('part=two=three');
  });

  it('rejects missing and malformed session cookies safely', () => {
    expect(sessionTokenFromCookie(null)).toBe('');
    expect(sessionTokenFromCookie('theme=dark')).toBe('');
    expect(sessionTokenFromCookie('pigeon_session=%E0%A4%A')).toBe('');
  });
});
