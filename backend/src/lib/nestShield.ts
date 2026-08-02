import { snowflake } from './ids'
import { has, Permission, resolvePermissions } from './permissions'
import { ApiError } from '../middleware/errors'
import type { ChannelRow } from './channels'
import type { Env } from '../types'

export interface ShieldSettings {
  enabled: number
  anti_raid: number
  raid_join_limit: number
  raid_window_seconds: number
  automod_enabled: number
  blocked_terms: string
  block_external_invites: number
  block_spam: number
  mention_limit: number
  default_slowmode_seconds: number
  lockdown: number
}

export type ShieldViolation = { kind: string; detail: string }

function hasRepeatedRun(value: string, limit: number): boolean {
  let previous = ''
  let count = 0
  for (const character of value) {
    if (character === previous) count += 1
    else {
      previous = character
      count = 1
    }
    if (count >= limit) return true
  }
  return false
}

export function inspectShieldContent(content: string, settings: ShieldSettings): ShieldViolation | null {
  const normalized = content.normalize('NFKC').toLocaleLowerCase().replace(/\s+/g, ' ').trim()
  if (!normalized) return null
  let blocked: string[] = []
  try {
    const parsed = JSON.parse(settings.blocked_terms) as unknown
    if (Array.isArray(parsed)) blocked = parsed.filter((term): term is string => typeof term === 'string')
  } catch {
    blocked = []
  }
  const term = blocked.find((item) => item && normalized.includes(item.normalize('NFKC').toLocaleLowerCase()))
  if (term) return { kind: 'blocked_term', detail: term }
  if (settings.block_external_invites === 1 && /(?:discord(?:app)?\.com\/invite|discord\.gg|join\.slack\.com|matrix\.to\/#|t\.me\/joinchat)/i.test(content)) {
    return { kind: 'external_invite', detail: 'external invite link' }
  }
  const mentions = content.match(/(^|\s)@[\p{L}\p{N}_-]+/gu)?.length ?? 0
  if (mentions > settings.mention_limit) return { kind: 'mention_spam', detail: `${mentions} mentions` }
  if (hasRepeatedRun(normalized, 25)) return { kind: 'character_spam', detail: 'repeated characters' }
  return null
}

async function settingsFor(env: Env, spaceId: string): Promise<ShieldSettings | null> {
  return env.DB.prepare(
    `SELECT enabled, anti_raid, raid_join_limit, raid_window_seconds, automod_enabled,
            blocked_terms, block_external_invites, block_spam, mention_limit,
            default_slowmode_seconds, lockdown
     FROM space_shield_settings WHERE space_id = ?`,
  ).bind(spaceId).first<ShieldSettings>()
}

async function block(env: Env, channel: ChannelRow, userId: string, violation: ShieldViolation): Promise<never> {
  await env.DB.prepare(
    `INSERT INTO shield_actions (id, space_id, channel_id, user_id, kind, detail, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  ).bind(snowflake(), channel.space_id, channel.id, userId, violation.kind, violation.detail, Date.now()).run()
  throw new ApiError(403, `automod_${violation.kind}`, `Nest Shield blocked this message: ${violation.detail}`)
}

export async function enforceNestShield(
  env: Env,
  channel: ChannelRow,
  userId: string,
  content: string,
  options: { encrypted: boolean; hasAttachment: boolean },
): Promise<void> {
  if (!channel.space_id) return
  const member = await resolvePermissions(env, userId, channel.space_id, channel.id)
  if (!has(member.permissions, Permission.SEND_MESSAGES)) throw new ApiError(403, 'missing_permission', 'you cannot send in this channel')
  if (options.hasAttachment && !has(member.permissions, Permission.ATTACH_FILES)) {
    throw new ApiError(403, 'missing_permission', 'you cannot attach files in this channel')
  }
  const settings = await settingsFor(env, channel.space_id)
  if (!settings) return
  const moderator = member.isOwner || has(member.permissions, Permission.MANAGE_MESSAGES)
  const timeout = await env.DB.prepare(
    'SELECT until_at, reason FROM space_member_timeouts WHERE space_id = ? AND user_id = ? AND until_at > ?',
  ).bind(channel.space_id, userId, Date.now()).first<{ until_at: number; reason: string | null }>()
  if (timeout && !member.isOwner) {
    throw new ApiError(403, 'member_timed_out', `you are timed out until ${new Date(timeout.until_at).toISOString()}`)
  }
  if (settings.lockdown === 1 && !moderator) throw new ApiError(403, 'nest_lockdown', 'this nest is in emergency lockdown')
  if (settings.enabled !== 1) return
  if (!moderator) {
    const channelSlow = await env.DB.prepare(
      'SELECT slowmode_seconds FROM channel_shield_settings WHERE channel_id = ?',
    ).bind(channel.id).first<{ slowmode_seconds: number }>()
    const slowSeconds = Number(channelSlow?.slowmode_seconds ?? settings.default_slowmode_seconds)
    if (slowSeconds > 0) {
      const recent = await env.DB.prepare(
        'SELECT created_at FROM messages WHERE channel_id = ? AND author_id = ? AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 1',
      ).bind(channel.id, userId).first<{ created_at: number }>()
      const remaining = recent ? recent.created_at + slowSeconds * 1000 - Date.now() : 0
      if (remaining > 0) throw new ApiError(429, 'slow_mode', `wait ${Math.ceil(remaining / 1000)} seconds before sending again`)
    }
    if (settings.automod_enabled === 1 && !options.encrypted) {
      const violation = inspectShieldContent(content, settings)
      if (violation) await block(env, channel, userId, violation)
      if (settings.block_spam === 1) {
        const recent = await env.DB.prepare(
          `SELECT content, created_at FROM messages
           WHERE channel_id = ? AND author_id = ? AND deleted_at IS NULL AND created_at > ?
           ORDER BY created_at DESC LIMIT 8`,
        ).bind(channel.id, userId, Date.now() - 15_000).all<{ content: string; created_at: number }>()
        const normalized = content.normalize('NFKC').toLocaleLowerCase().replace(/\s+/g, ' ').trim()
        if (recent.results.length >= 6 || recent.results.filter((row) => row.content.normalize('NFKC').toLocaleLowerCase().replace(/\s+/g, ' ').trim() === normalized).length >= 2) {
          await block(env, channel, userId, { kind: 'message_spam', detail: 'messages are being sent too quickly' })
        }
      }
    }
  }
}

export async function antiRaidCheck(env: Env, spaceId: string): Promise<{ lockdown: boolean; settings: ShieldSettings | null }> {
  const settings = await settingsFor(env, spaceId)
  if (!settings) return { lockdown: false, settings }
  if (settings.lockdown === 1) return { lockdown: true, settings }
  if (settings.enabled !== 1) return { lockdown: false, settings }
  if (settings.anti_raid !== 1) return { lockdown: false, settings }
  const since = Date.now() - settings.raid_window_seconds * 1000
  const count = await env.DB.prepare(
    'SELECT COUNT(*) AS count FROM space_join_events WHERE space_id = ? AND created_at >= ?',
  ).bind(spaceId, since).first<{ count: number }>()
  return { lockdown: Number(count?.count ?? 0) >= settings.raid_join_limit, settings }
}
