import { describe, expect, it } from 'vitest';
import { enforceNestShield, inspectShieldContent, type ShieldSettings } from '../src/lib/nestShield';
import type { ChannelRow } from '../src/lib/channels';
import type { Env } from '../src/types';

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

const channel: ChannelRow = {
  id: 'channel-1',
  space_id: 'space-1',
  name: 'general',
  topic: null,
  kind: 'text',
  last_seq: 0,
};

function shieldEnv(role: string, overrides: Partial<ShieldSettings>, timeout = false): Env {
  const value = { ...settings, ...overrides };
  const database = {
    prepare(sql: string) {
      const statement = {
        bind() {
          return statement;
        },
        async first() {
          if (sql.includes('SELECT role FROM space_members')) return { role };
          if (sql.includes('FROM space_shield_settings')) return value;
          if (sql.includes('FROM space_member_timeouts')) {
            return timeout ? { until_at: Date.now() + 60_000, reason: 'cool down' } : null;
          }
          return null;
        },
        async all() {
          return { results: [], success: true, meta: {} };
        },
      };
      return statement;
    },
  };
  return { DB: database as unknown as D1Database } as Env;
}

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

  it('enforces emergency lockdown even when general Shield rules are disabled', async () => {
    await expect(enforceNestShield(
      shieldEnv('member', { enabled: 0, lockdown: 1 }),
      channel,
      'member-1',
      'hello',
      { encrypted: false, hasAttachment: false },
    )).rejects.toMatchObject({ code: 'nest_lockdown' });

    await expect(enforceNestShield(
      shieldEnv('owner', { enabled: 0, lockdown: 1 }),
      channel,
      'owner-1',
      'hello',
      { encrypted: false, hasAttachment: false },
    )).resolves.toBeUndefined();
  });

  it('keeps active member timeouts enforced when automod is off', async () => {
    await expect(enforceNestShield(
      shieldEnv('member', { enabled: 0, lockdown: 0 }, true),
      channel,
      'member-1',
      'hello',
      { encrypted: false, hasAttachment: false },
    )).rejects.toMatchObject({ code: 'member_timed_out' });
  });
});
