import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { AuditEntryDto, ChannelDto, SpaceBanDto, SpaceDto, SpaceMemberDto } from '@/api/dto'
import {
  Campaign,
  DeleteOutline,
  Edit,
  ExitToApp,
  Forum,
  Groups,
  History,
  Image,
  Settings,
  Tag,
} from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { ConfirmDialog, Sheet } from '@/components/ui/Overlay'
import { TextField } from '@/components/ui/TextField'
import { EmptyState, ListRow, Screen, ScreenBody, SettingsGroup, SettingsRow, Tabs, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { relativeTime } from '@/lib/format'
import { useSession } from '@/store/session'
import { useSocial } from '@/store/social'
import './Settings.css'

type Tab = 'overview' | 'channels' | 'moderation' | 'audit'

export default function NestManageScreen() {
  const { spaceId = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const me = useSession((state) => state.user)
  const refreshSpaces = useSocial((state) => state.loadSpaces)
  const iconRef = useRef<HTMLInputElement>(null)
  const [tab, setTab] = useState<Tab>('overview')
  const [space, setSpace] = useState<SpaceDto | null>(null)
  const [members, setMembers] = useState<SpaceMemberDto[]>([])
  const [bans, setBans] = useState<SpaceBanDto[]>([])
  const [audit, setAudit] = useState<AuditEntryDto[]>([])
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [renameTarget, setRenameTarget] = useState<ChannelDto | null>(null)
  const [channelName, setChannelName] = useState('')
  const [deleteChannel, setDeleteChannel] = useState<ChannelDto | null>(null)
  const [deleteNest, setDeleteNest] = useState(false)
  const [leaveNest, setLeaveNest] = useState(false)
  const [transferTo, setTransferTo] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    const nextSpace = await api.space(spaceId)
    setSpace(nextSpace)
    setName(nextSpace.name)
    setDescription(nextSpace.description ?? '')
    const [nextMembers, nextBans, nextAudit] = await Promise.all([
      api.spaceMembers(spaceId).catch(() => []),
      api.spaceBans(spaceId).catch(() => []),
      api.spaceAudit(spaceId).catch(() => []),
    ])
    setMembers(nextMembers)
    setBans(nextBans)
    setAudit(nextAudit)
  }, [spaceId])

  useEffect(() => {
    void load().catch((err) => toast.error(err instanceof Error ? err.message : 'could not load nest'))
  }, [load, toast])

  const owner = space?.owner_id === me?.id || space?.role === 'owner'
  const canManage = owner || space?.role === 'admin'
  const channels = useMemo(() => space?.channels ?? [], [space])

  const saveNest = async () => {
    setBusy(true)
    try {
      const updated = await api.updateSpace(spaceId, { name: name.trim(), description: description.trim() || null })
      setSpace((current) => ({ ...current, ...updated }))
      await refreshSpaces(true)
      toast.show('nest updated')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not update nest')
    } finally {
      setBusy(false)
    }
  }

  const uploadIcon = async (file: File) => {
    setBusy(true)
    try {
      await api.uploadSpaceIcon(spaceId, file, file.type || 'image/png', 'original')
      await api.uploadSpaceIcon(spaceId, file, file.type || 'image/png', 'square')
      await load()
      await refreshSpaces(true)
      toast.show('nest icon updated')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not upload icon')
    } finally {
      setBusy(false)
    }
  }

  const rename = async () => {
    if (!renameTarget || !channelName.trim()) return
    try {
      await api.renameChannel(spaceId, renameTarget.id, channelName.trim())
      setRenameTarget(null)
      await load()
      await refreshSpaces(true)
      toast.show('channel renamed')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not rename channel')
    }
  }

  return (
    <Screen className="settings-screen">
      <TopBar title={space?.name ?? 'manage nest'} subtitle="complete nest administration" onBack={() => navigate(-1)} />
      <Tabs
        tabs={[
          { key: 'overview', label: 'overview' },
          { key: 'channels', label: `channels ${channels.length}` },
          { key: 'moderation', label: 'moderation' },
          { key: 'audit', label: 'audit log' },
        ]}
        value={tab}
        onChange={setTab}
      />
      <ScreenBody>
        {tab === 'overview' ? (
          <>
            <div className="nest-manage__hero">
              <Avatar
                name={space?.name ?? 'nest'}
                avatarKey={space?.icon_square_key || space?.icon_key}
                size="hero"
                className="spaces__icon"
              />
              <div>
                <strong>{space?.name}</strong>
                <small>{members.length} members · {channels.length} channels · {space?.role}</small>
              </div>
              {canManage ? (
                <Button variant="tonal" leading={<Image size={18} />} loading={busy} onClick={() => iconRef.current?.click()}>
                  change icon
                </Button>
              ) : null}
            </div>

            {canManage ? (
              <div className="settings__form">
                <TextField label="nest name" value={name} onChange={setName} />
                <TextField label="description" value={description} onChange={setDescription} multiline />
                <Button loading={busy} disabled={!name.trim()} onClick={() => void saveNest()}>save nest</Button>
              </div>
            ) : null}

            <SettingsGroup label="people and permissions">
              <SettingsRow icon={<Groups size={18} />} title="members" value={`${members.length} people`} onClick={() => navigate(`/nest/${spaceId}/members`)} />
              <SettingsRow icon={<Settings size={18} />} title="roles" value="permissions and role assignments" onClick={() => navigate(`/nest/${spaceId}/roles`)} />
              <SettingsRow icon={<Tag size={18} />} title="emoji and stickers" onClick={() => navigate(`/nest/${spaceId}/emoji`)} />
            </SettingsGroup>

            {owner ? (
              <SettingsGroup label="ownership">
                <label className="settings__scope-row">
                  <span>
                    <strong>transfer ownership</strong>
                    <small>you become an admin after transferring</small>
                  </span>
                  <select value={transferTo} onChange={(event) => setTransferTo(event.target.value)}>
                    <option value="">choose member</option>
                    {members.filter((member) => member.id !== me?.id).map((member) => (
                      <option key={member.id} value={member.id}>{member.display_name || member.username}</option>
                    ))}
                  </select>
                </label>
                <SettingsRow
                  icon={<Groups size={18} />}
                  title="confirm ownership transfer"
                  value="this immediately changes the nest owner"
                  danger
                  onClick={async () => {
                    if (!transferTo) {
                      toast.error('choose a member first')
                      return
                    }
                    try {
                      await api.transferSpace(spaceId, transferTo)
                      await load()
                      toast.show('ownership transferred')
                    } catch (err) {
                      toast.error(err instanceof Error ? err.message : 'could not transfer ownership')
                    }
                  }}
                />
                <SettingsRow icon={<DeleteOutline size={18} />} title="demolish nest" value="deletes every channel and message" danger onClick={() => setDeleteNest(true)} />
              </SettingsGroup>
            ) : (
              <SettingsGroup>
                <SettingsRow icon={<ExitToApp size={18} />} title="leave nest" danger onClick={() => setLeaveNest(true)} />
              </SettingsGroup>
            )}
          </>
        ) : null}

        {tab === 'channels' ? (
          channels.length ? (
            channels.map((channel) => (
              <ListRow
                key={channel.id}
                title={`#${channel.name || 'channel'}`}
                subtitle={channel.topic || channel.kind}
                leading={
                  <span className="nest__channel-icon">
                    {channel.kind === 'forum' ? <Forum size={19} /> : channel.kind === 'voice' ? <Campaign size={19} /> : <Tag size={19} />}
                  </span>
                }
                trailing={
                  canManage ? (
                    <span className="nest__member-actions">
                      <IconButton
                        label="channel permissions"
                        onClick={(event) => {
                          event.stopPropagation()
                          navigate(`/nest/${spaceId}/channel/${channel.id}/permissions`)
                        }}
                      >
                        <Settings />
                      </IconButton>
                      <IconButton
                        label="rename channel"
                        onClick={(event) => {
                          event.stopPropagation()
                          setChannelName(channel.name || '')
                          setRenameTarget(channel)
                        }}
                      >
                        <Edit />
                      </IconButton>
                      <IconButton
                        label="delete channel"
                        tone="danger"
                        onClick={(event) => {
                          event.stopPropagation()
                          setDeleteChannel(channel)
                        }}
                      >
                        <DeleteOutline />
                      </IconButton>
                    </span>
                  ) : null
                }
                onClick={() => navigate(channel.kind === 'forum' ? `/forum/${channel.id}?name=${encodeURIComponent(channel.name || '')}&spaceId=${spaceId}` : channel.kind === 'voice' ? `/call/${channel.id}?name=${encodeURIComponent(channel.name || '')}&spaceId=${spaceId}` : `/chat/${channel.id}?space=true&name=${encodeURIComponent(channel.name || '')}`)}
              />
            ))
          ) : (
            <EmptyState icon={<Tag size={28} />} title="no channels" />
          )
        ) : null}

        {tab === 'moderation' ? (
          <>
            <SettingsGroup label="member tools">
              <SettingsRow icon={<Groups size={18} />} title="manage members" value="assign roles, kick or ban" onClick={() => navigate(`/nest/${spaceId}/members`)} />
            </SettingsGroup>
            <div className="ui-group__label">banned users</div>
            {bans.length ? (
              bans.map((ban) => (
                <ListRow
                  key={ban.user_id}
                  leading={<Avatar name={ban.display_name || ban.username || 'user'} avatarKey={ban.avatar_key} size="sm" />}
                  title={ban.display_name || ban.username || ban.user_id}
                  subtitle={ban.reason || 'no reason provided'}
                  trailing={
                    <Button
                      variant="text"
                      onClick={async () => {
                        try {
                          await api.unbanMember(spaceId, ban.user_id)
                          setBans((items) => items.filter((item) => item.user_id !== ban.user_id))
                          toast.show('ban lifted')
                        } catch (err) {
                          toast.error(err instanceof Error ? err.message : 'could not lift ban')
                        }
                      }}
                    >
                      unban
                    </Button>
                  }
                />
              ))
            ) : (
              <EmptyState title="nobody is banned" subtitle="banned members appear here" />
            )}
          </>
        ) : null}

        {tab === 'audit' ? (
          audit.length ? (
            audit.map((entry, index) => (
              <ListRow
                key={`${entry.created_at}-${index}`}
                leading={<History size={20} />}
                title={entry.action.replaceAll('.', ' ')}
                subtitle={entry.target || 'nest'}
                trailing={<small>{relativeTime(entry.created_at)}</small>}
              />
            ))
          ) : (
            <EmptyState icon={<History size={28} />} title={canManage ? 'no audit entries yet' : 'audit log unavailable'} />
          )
        ) : null}
      </ScreenBody>

      <input
        ref={iconRef}
        hidden
        type="file"
        accept="image/*"
        onChange={(event) => {
          const file = event.target.files?.[0]
          if (file) void uploadIcon(file)
          event.target.value = ''
        }}
      />

      <Sheet open={!!renameTarget} title="rename channel" onClose={() => setRenameTarget(null)}>
        <div className="nest__form">
          <TextField label="channel name" value={channelName} onChange={setChannelName} />
          <Button size="cta" fullWidth onClick={() => void rename()}>rename channel</Button>
        </div>
      </Sheet>

      <ConfirmDialog
        open={!!deleteChannel}
        title={`delete #${deleteChannel?.name || 'channel'}?`}
        body="This permanently removes the channel and all of its messages."
        confirmLabel="delete channel"
        danger
        onConfirm={async () => {
          if (!deleteChannel) return
          try {
            await api.deleteChannel(spaceId, deleteChannel.id)
            setDeleteChannel(null)
            await load()
            await refreshSpaces(true)
            toast.show('channel deleted')
          } catch (err) {
            toast.error(err instanceof Error ? err.message : 'could not delete channel')
          }
        }}
        onClose={() => setDeleteChannel(null)}
      />
      <ConfirmDialog
        open={deleteNest}
        title={`demolish ${space?.name || 'this nest'}?`}
        body="Every channel and message will be permanently removed. This cannot be undone."
        confirmLabel="demolish nest"
        danger
        onConfirm={async () => {
          try {
            await api.deleteSpace(spaceId)
            await refreshSpaces(true)
            navigate('/spaces')
          } catch (err) {
            toast.error(err instanceof Error ? err.message : 'could not delete nest')
          }
        }}
        onClose={() => setDeleteNest(false)}
      />
      <ConfirmDialog
        open={leaveNest}
        title={`leave ${space?.name || 'this nest'}?`}
        body="You will need another invite to come back."
        confirmLabel="leave"
        danger
        onConfirm={async () => {
          try {
            await api.leaveSpace(spaceId)
            await refreshSpaces(true)
            navigate('/spaces')
          } catch (err) {
            toast.error(err instanceof Error ? err.message : 'could not leave nest')
          }
        }}
        onClose={() => setLeaveNest(false)}
      />
    </Screen>
  )
}
