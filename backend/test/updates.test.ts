import { describe, expect, it } from 'vitest';
import { desktopUpdateFor } from '../src/routes/updates';

describe('desktop updater manifest', () => {
  it('selects a signed release for the requested target', () => {
    expect(desktopUpdateFor({
      version: '3.0.0-rc.2',
      notes: 'release candidate',
      platforms: {
        'windows-x86_64': {
          signature: 'signed-payload-that-is-long-enough-to-validate',
          url: 'https://github.com/realcgcristi/pigeonsms/releases/download/v3-rc2/PigeonSMS.exe',
        },
      },
    }, 'windows', 'x86_64')).toMatchObject({
      version: '3.0.0-rc.2',
      notes: 'release candidate',
    });
  });

  it('rejects unsigned and foreign release URLs', () => {
    expect(desktopUpdateFor({
      version: '3.0.0',
      platforms: {
        'windows-x86_64': {
          signature: 'signed-payload-that-is-long-enough-to-validate',
          url: 'https://example.com/PigeonSMS.exe',
        },
      },
    }, 'windows', 'x86_64')).toBeNull();
  });
});
