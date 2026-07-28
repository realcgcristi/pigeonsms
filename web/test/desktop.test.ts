import { describe, expect, it } from 'vitest';
import { desktopRouteFromUrl } from '../src/desktop/runtime';

describe('desktop deep links', () => {
  it('maps host-based links to app routes', () => {
    expect(desktopRouteFromUrl('pigeonsms://chat/123?space=true')).toBe('/chat/123?space=true');
  });

  it('maps open links to app routes', () => {
    expect(desktopRouteFromUrl('pigeonsms://open/nest/456/members')).toBe('/nest/456/members');
  });

  it('supports the app home', () => {
    expect(desktopRouteFromUrl('pigeonsms://open')).toBe('/');
  });

  it('rejects unknown routes and foreign schemes', () => {
    expect(desktopRouteFromUrl('pigeonsms://admin/users')).toBeNull();
    expect(desktopRouteFromUrl('https://pigeonsms.aldi.best/chat/123')).toBeNull();
  });
});
