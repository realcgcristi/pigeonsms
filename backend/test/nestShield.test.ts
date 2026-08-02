import { describe, expect, it } from 'vitest';
import { inspectShieldContent, type ShieldSettings } from '../src/lib/nestShield';

const settings: ShieldSettings = {
  enabled: 1,
  anti_raid: 1,
  raid_join_limit: 12,
  raid_window_seconds: 60,
  automod_enabled: 1,
  blocked_terms: '["stolen cards","phish me"]',
  block_external_invites: 1,
  block_spam: 1,
  mention_limit: 3,
  default_slowmode_seconds: 0,
  lockdown: 0,
};

describe('nest shield content inspection', () => {
  it('normalizes blocked terms before matching', () => {
    expect(inspectShieldContent('Selling   STOLEN cards here', settings)).toMatchObject({ kind: 'blocked_term' });
  });

  it('blocks external community invites', () => {
    expect(inspectShieldContent('join https://discord.gg/example', settings)).toMatchObject({ kind: 'external_invite' });
  });

  it('enforces the configured mention ceiling', () => {
    expect(inspectShieldContent('@one @two @three @four look', settings)).toMatchObject({ kind: 'mention_spam' });
  });

  it('detects long repeated-character runs without a backtracking regular expression', () => {
    expect(inspectShieldContent(`hello ${'x'.repeat(25)}`, settings)).toMatchObject({ kind: 'character_spam' });
    expect(inspectShieldContent(`hello ${'x'.repeat(24)}y`, settings)).toBeNull();
  });

  it('allows ordinary conversation', () => {
    expect(inspectShieldContent('good morning pigeons', settings)).toBeNull();
  });
});
