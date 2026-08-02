import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import type {
  MemberTimeoutDto,
  ModerationReportDto,
  NestShieldResponse,
  ShieldActionDto,
  SpaceMemberDto,
} from '@/api/dto'
import { History, Report, Shield, Timer } from '@/components/icons'
import { Button } from '@/components/ui/Button'
import { Switch } from '@/components/ui/Switch'
import { TextField } from '@/components/ui/TextField'
import { EmptyState, ListRow, SettingsGroup, SettingsRow } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { relativeTime } from '@/lib/format'

const fallback: NestShieldResponse = {
  settings: {
    enabled: false,
    anti_raid: true,
    raid_join_limit: 12,
    raid_window_seconds: 60,
    automod_enabled: true,
    blocked_terms: [],
    block_external_invites: true,
    block_spam: true,
    mention_limit: 8,
    default_slowmode_seconds: 0,
    lockdown: false,
  },
  channels: [],
}

function reportContent(report: ModerationReportDto): string {
  const message = report.evidence?.['message']
  if (!message || typeof message !== 'object' || Array.isArray(message)) return 'message evidence captured'
  const content = message['content']
  return typeof content === 'string' && content.trim() ? content : 'attachment or empty message'
}

export function NestShieldPanel({ spaceId, members }: { spaceId: string; members: SpaceMemberDto[] }) {
  const toast = useToast()
  const [shield, setShield] = useState<NestShieldResponse>(fallback)
  const [reports, setReports] = useState<ModerationReportDto[]>([])
  const [timeouts, setTimeouts] = useState<MemberTimeoutDto[]>([])
  const [actions, setActions] = useState<ShieldActionDto[]>([])
  const [terms, setTerms] = useState('')
  const [target, setTarget] = useState('')
  const [duration, setDuration] = useState('600')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    const [nextShield, nextReports, nextTimeouts, nextActions] = await Promise.all([
      api.nestShield(spaceId),
      api.moderationReports(spaceId).catch(() => []),
      api.memberTimeouts(spaceId).catch(() => []),
      api.shieldActions(spaceId).catch(() => []),
    ])
    setShield(nextShield)
    setTerms(nextShield.settings.blocked_terms.join('\n'))
    setReports(nextReports)
    setTimeouts(nextTimeouts)
    setActions(nextActions)
    setLoading(false)
  }, [spaceId])

  useEffect(() => {
    void load().catch((error) => {
      setLoading(false)
      toast.error(error instanceof Error ? error.message : 'could not load Nest Shield')
    })
  }, [load, toast])

  const availableMembers = useMemo(
    () => members.filter((member) => member.role !== 'owner' && !timeouts.some((item) => item.user_id === member.id)),
    [members, timeouts],
  )

  const update = <K extends keyof NestShieldResponse['settings']>(key: K, value: NestShieldResponse['settings'][K]) => {
    setShield((current) => ({ ...current, settings: { ...current.settings, [key]: value } }))
  }

  const save = async () => {
    setBusy(true)
    try {
      const blockedTerms = terms.split(/[\n,]/).map((item) => item.trim()).filter(Boolean)
      const settings = await api.updateNestShield(spaceId, { ...shield.settings, blocked_terms: blockedTerms })
      setShield((current) => ({ ...current, settings }))
      setTerms(settings.blocked_terms.join('\n'))
      toast.show('Nest Shield updated')
      await load()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'could not save Nest Shield')
    } finally {
      setBusy(false)
    }
  }

  const applyTimeout = async () => {
    if (!target) return
    setBusy(true)
    try {
      await api.timeoutMember(spaceId, target, Number(duration), reason)
      setTarget('')
      setReason('')
      await load()
      toast.show('member timed out')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'could not time out member')
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <div className="shield__loading">loading Nest Shield…</div>

  return (
    <div className="shield">
      <div className={`shield__hero ${shield.settings.lockdown ? 'is-lockdown' : ''}`}>
        <Shield size={28} />
        <div>
          <strong>{shield.settings.lockdown ? 'emergency lockdown active' : shield.settings.enabled ? 'Nest Shield is protecting this nest' : 'Nest Shield is off'}</strong>
          <span>raid protection, automod, reports, timeouts and per-channel slow mode</span>
        </div>
      </div>

      <SettingsGroup label="protection">
        <SettingsRow
          icon={<Shield size={18} />}
          title="Nest Shield"
          value="enforce moderation rules on messages and joins"
          trailing={<Switch checked={shield.settings.enabled} onChange={(value) => update('enabled', value)} label="Nest Shield" />}
        />
        <SettingsRow
          icon={<Report size={18} />}
          title="emergency lockdown"
          value="only moderators can send and new joins are paused"
          danger={shield.settings.lockdown}
          trailing={<Switch checked={shield.settings.lockdown} onChange={(value) => update('lockdown', value)} label="emergency lockdown" />}
        />
        <SettingsRow
          title="anti-raid"
          value="lock automatically when too many accounts join"
          trailing={<Switch checked={shield.settings.anti_raid} onChange={(value) => update('anti_raid', value)} label="anti-raid" />}
        />
        <SettingsRow
          title="automod"
          value="scan plaintext nest messages before delivery"
          trailing={<Switch checked={shield.settings.automod_enabled} onChange={(value) => update('automod_enabled', value)} label="automod" />}
        />
        <SettingsRow
          title="block spam"
          value="stop message floods, duplicate messages and character spam"
          trailing={<Switch checked={shield.settings.block_spam} onChange={(value) => update('block_spam', value)} label="block spam" />}
        />
        <SettingsRow
          title="block external invites"
          value="stop Discord, Slack, Matrix and Telegram invite links"
          trailing={<Switch checked={shield.settings.block_external_invites} onChange={(value) => update('block_external_invites', value)} label="external invites" />}
        />
      </SettingsGroup>

      <div className="shield__grid">
        <label className="shield__field">
          <span>joins before lockdown</span>
          <input type="number" min="3" max="500" value={shield.settings.raid_join_limit} onChange={(event) => update('raid_join_limit', Number(event.target.value))} />
        </label>
        <label className="shield__field">
          <span>raid window · seconds</span>
          <input type="number" min="10" max="3600" value={shield.settings.raid_window_seconds} onChange={(event) => update('raid_window_seconds', Number(event.target.value))} />
        </label>
        <label className="shield__field">
          <span>mention limit</span>
          <input type="number" min="1" max="100" value={shield.settings.mention_limit} onChange={(event) => update('mention_limit', Number(event.target.value))} />
        </label>
        <label className="shield__field">
          <span>default slow mode · seconds</span>
          <input type="number" min="0" max="21600" value={shield.settings.default_slowmode_seconds} onChange={(event) => update('default_slowmode_seconds', Number(event.target.value))} />
        </label>
      </div>

      <TextField
        label="blocked terms"
        value={terms}
        onChange={setTerms}
        multiline
        rows={4}
        helper="one term per line; matching is normalized and case-insensitive"
      />

      {shield.channels.length ? (
        <SettingsGroup label="channel slow mode">
          {shield.channels.map((channel) => (
            <label className="shield__channel" key={channel.channel_id}>
              <span>#{channel.name || 'channel'}</span>
              <select
                value={channel.slowmode_seconds}
                onChange={async (event) => {
                  const seconds = Number(event.target.value)
                  setShield((current) => ({
                    ...current,
                    channels: current.channels.map((item) => item.channel_id === channel.channel_id ? { ...item, slowmode_seconds: seconds } : item),
                  }))
                  try {
                    await api.updateChannelShield(spaceId, channel.channel_id, seconds)
                  } catch (error) {
                    toast.error(error instanceof Error ? error.message : 'could not update slow mode')
                    await load()
                  }
                }}
              >
                <option value="0">off</option>
                <option value="5">5 seconds</option>
                <option value="10">10 seconds</option>
                <option value="30">30 seconds</option>
                <option value="60">1 minute</option>
                <option value="300">5 minutes</option>
              </select>
            </label>
          ))}
        </SettingsGroup>
      ) : null}

      <Button loading={busy} fullWidth onClick={() => void save()}>save protection rules</Button>

      <SettingsGroup label="time out a member">
        <div className="shield__timeout-form">
          <select value={target} onChange={(event) => setTarget(event.target.value)}>
            <option value="">choose member</option>
            {availableMembers.map((member) => (
              <option key={member.id} value={member.id}>{member.display_name || member.username}</option>
            ))}
          </select>
          <select value={duration} onChange={(event) => setDuration(event.target.value)}>
            <option value="600">10 minutes</option>
            <option value="3600">1 hour</option>
            <option value="86400">1 day</option>
            <option value="604800">1 week</option>
          </select>
          <TextField label="reason" value={reason} onChange={setReason} />
          <Button disabled={!target} loading={busy} onClick={() => void applyTimeout()}>time out</Button>
        </div>
        {timeouts.map((timeout) => (
          <ListRow
            key={timeout.user_id}
            leading={<Timer size={19} />}
            title={timeout.display_name || timeout.username}
            subtitle={`${timeout.reason || 'no reason'} · ends ${relativeTime(timeout.until_at)}`}
            trailing={<Button variant="text" onClick={async () => {
              await api.clearMemberTimeout(spaceId, timeout.user_id)
              setTimeouts((items) => items.filter((item) => item.user_id !== timeout.user_id))
            }}>clear</Button>}
          />
        ))}
      </SettingsGroup>

      <SettingsGroup label={`open reports · ${reports.length}`}>
        {reports.length ? reports.map((report) => (
          <div className="shield__report" key={report.id}>
            <div className="shield__report-head">
              <Report size={19} />
              <strong>{report.category} · {report.reported_username || report.reported_user_id}</strong>
              <small>{relativeTime(report.created_at)}</small>
            </div>
            <p>{reportContent(report)}</p>
            {report.reason ? <span>{report.reason}</span> : null}
            <code title={report.evidence_hash}>{report.evidence_hash.slice(0, 20)}…</code>
            <div className="shield__report-actions">
              <Button variant="tonal" onClick={async () => {
                await api.resolveModerationReport(spaceId, report.id, 'resolved', 'reviewed by moderator')
                setReports((items) => items.filter((item) => item.id !== report.id))
              }}>resolve</Button>
              <Button variant="text" onClick={async () => {
                await api.resolveModerationReport(spaceId, report.id, 'dismissed')
                setReports((items) => items.filter((item) => item.id !== report.id))
              }}>dismiss</Button>
            </div>
          </div>
        )) : <EmptyState title="no open reports" subtitle="new reports preserve a hashed evidence snapshot" />}
      </SettingsGroup>

      <SettingsGroup label="recent Shield activity">
        {actions.length ? actions.slice(0, 20).map((action) => (
          <ListRow
            key={action.id}
            leading={<History size={18} />}
            title={action.kind.replaceAll('_', ' ')}
            subtitle={action.detail || action.display_name || action.username || 'Nest Shield'}
            trailing={<small>{relativeTime(action.created_at)}</small>}
          />
        )) : <EmptyState title="no Shield activity yet" />}
      </SettingsGroup>
    </div>
  )
}
