import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import {
  PERMISSION_NAMES,
  type ChannelOverrideDto,
  type PermissionName,
  type SpaceMemberDto,
  type SpaceRoleDto,
} from '@/api/dto'
import { Check, Close, DeleteOutline, Shield } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { EmptyState, ListRow, Screen, ScreenBody, Tabs, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import './Spaces.css'

type TargetKind = 'role' | 'member'
type PermissionState = 'inherit' | 'allow' | 'deny'

export default function ChannelPermissionsScreen() {
  const { spaceId = '', channelId = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const [roles, setRoles] = useState<SpaceRoleDto[]>([])
  const [members, setMembers] = useState<SpaceMemberDto[]>([])
  const [overrides, setOverrides] = useState<ChannelOverrideDto[]>([])
  const [targetKind, setTargetKind] = useState<TargetKind>('role')
  const [targetId, setTargetId] = useState('')
  const [states, setStates] = useState<Record<PermissionName, PermissionState>>(
    () => Object.fromEntries(PERMISSION_NAMES.map((name) => [name, 'inherit'])) as Record<PermissionName, PermissionState>,
  )
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    const [nextRoles, nextMembers, nextOverrides] = await Promise.all([
      api.spaceRoles(spaceId),
      api.spaceMembers(spaceId),
      api.channelOverrides(spaceId, channelId),
    ])
    setRoles(nextRoles)
    setMembers(nextMembers)
    setOverrides(nextOverrides)
  }, [channelId, spaceId])

  useEffect(() => {
    void load().catch((err) => toast.error(err instanceof Error ? err.message : 'could not load overrides'))
  }, [load, toast])

  const targets = targetKind === 'role' ? roles : members
  const selected = useMemo(
    () =>
      overrides.find((override) =>
        targetKind === 'role' ? override.role_id === targetId : override.user_id === targetId,
      ),
    [overrides, targetId, targetKind],
  )

  useEffect(() => {
    const allow = new Set(selected?.allow_names ?? [])
    const deny = new Set(selected?.deny_names ?? [])
    setStates(
      Object.fromEntries(
        PERMISSION_NAMES.map((name) => [name, allow.has(name) ? 'allow' : deny.has(name) ? 'deny' : 'inherit']),
      ) as Record<PermissionName, PermissionState>,
    )
  }, [selected])

  const save = async () => {
    if (!targetId) {
      toast.error(`choose a ${targetKind}`)
      return
    }
    setBusy(true)
    try {
      const allow = PERMISSION_NAMES.filter((name) => states[name] === 'allow')
      const deny = PERMISSION_NAMES.filter((name) => states[name] === 'deny')
      await api.setChannelOverride(spaceId, channelId, {
        ...(targetKind === 'role' ? { role_id: targetId } : { user_id: targetId }),
        allow,
        deny,
      })
      await load()
      toast.show('channel permissions saved')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not save override')
    } finally {
      setBusy(false)
    }
  }

  const targetName = (override: ChannelOverrideDto) => {
    if (override.role_id) return roles.find((role) => role.id === override.role_id)?.name || 'role'
    const member = members.find((item) => item.id === override.user_id)
    return member?.display_name || member?.username || 'member'
  }

  return (
    <Screen>
      <TopBar title="channel permissions" subtitle="allow, deny, or inherit each capability" onBack={() => navigate(-1)} />
      <Tabs
        tabs={[
          { key: 'role', label: 'role override' },
          { key: 'member', label: 'member override' },
        ]}
        value={targetKind}
        onChange={(kind) => {
          setTargetKind(kind)
          setTargetId('')
        }}
      />
      <ScreenBody>
        <div className="nest__form">
          <label className="permissions__target">
            <span>{targetKind}</span>
            <select value={targetId} onChange={(event) => setTargetId(event.target.value)}>
              <option value="">choose {targetKind}</option>
              {targets.map((target) => (
                <option key={target.id} value={target.id}>
                  {'username' in target ? target.display_name || target.username : target.name || 'role'}
                </option>
              ))}
            </select>
          </label>
        </div>

        <div className="ui-group__label">permissions</div>
        <div className="permissions__grid">
          {PERMISSION_NAMES.map((permission) => (
            <div className="permissions__row" key={permission}>
              <span>{permission.toLowerCase().replaceAll('_', ' ')}</span>
              <div>
                {(['inherit', 'allow', 'deny'] as const).map((value) => (
                  <button
                    key={value}
                    type="button"
                    className={states[permission] === value ? `permissions__choice permissions__choice--${value} permissions__choice--on` : `permissions__choice permissions__choice--${value}`}
                    aria-label={`${value} ${permission.toLowerCase().replaceAll('_', ' ')}`}
                    onClick={() => setStates((current) => ({ ...current, [permission]: value }))}
                  >
                    {value === 'allow' ? <Check size={17} /> : value === 'deny' ? <Close size={17} /> : '—'}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
        <div className="nest__form">
          <Button size="cta" fullWidth loading={busy} onClick={() => void save()}>save override</Button>
        </div>

        <div className="ui-group__label">active overrides</div>
        {overrides.length ? (
          overrides.map((override) => {
            const member = override.user_id ? members.find((item) => item.id === override.user_id) : null
            return (
              <ListRow
                key={override.id}
                leading={
                  member ? (
                    <Avatar name={member.display_name || member.username} avatarKey={member.avatar_key} size="sm" />
                  ) : (
                    <Shield size={21} />
                  )
                }
                title={targetName(override)}
                subtitle={`${override.allow_names?.length ?? 0} allowed · ${override.deny_names?.length ?? 0} denied`}
                trailing={
                  <button
                    type="button"
                    className="permissions__delete"
                    aria-label="delete override"
                    onClick={async (event) => {
                      event.stopPropagation()
                      await api.deleteChannelOverride(spaceId, channelId, override.id)
                      setOverrides((items) => items.filter((item) => item.id !== override.id))
                    }}
                  >
                    <DeleteOutline size={19} />
                  </button>
                }
                onClick={() => {
                  setTargetKind(override.role_id ? 'role' : 'member')
                  setTargetId(override.role_id || override.user_id || '')
                  window.scrollTo({ top: 0, behavior: 'smooth' })
                }}
              />
            )
          })
        ) : (
          <EmptyState icon={<Shield size={28} />} title="no overrides" subtitle="this channel inherits nest permissions" />
        )}
      </ScreenBody>
    </Screen>
  )
}
