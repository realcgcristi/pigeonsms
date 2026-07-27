import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { ChannelDto, PermissionsResponse, SpaceDto } from '@/api/dto'
import { Add, Campaign, EmojiEmotions, ExitToApp, Forum, Groups, PersonAdd, Settings, Tag } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { TextField } from '@/components/ui/TextField'
import { ConfirmDialog, Sheet } from '@/components/ui/Overlay'
import { Badge, Chip, ChipRow, EmptyState, ListRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { useSocial } from '@/store/social'
import './Spaces.css'

const KINDS = [
  { key: 'text', label: 'text' },
  { key: 'voice', label: 'voice' },
  { key: 'forum', label: 'forum' },
]

export default function NestChannelsScreen() {
  const { spaceId = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const spaces = useSocial((s) => s.spaces)
  const loadSpaces = useSocial((s) => s.loadSpaces)
  const [space, setSpace] = useState<SpaceDto | null>(null)
  const [perms, setPerms] = useState<PermissionsResponse | null>(null)
  const [creating, setCreating] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const [name, setName] = useState('')
  const [kind, setKind] = useState('text')

  useEffect(() => {
    const cached = spaces.find((s) => s.id === spaceId)
    if (cached) setSpace(cached)
    void api.space(spaceId).then(setSpace).catch(() => undefined)
    void api.spacePermissions(spaceId).then(setPerms).catch(() => undefined)
  }, [spaceId, spaces])

  const channels = useMemo(() => space?.channels ?? [], [space])
  const canManage = perms?.is_owner || perms?.permission_names?.includes('MANAGE_CHANNELS')

  const open = (channel: ChannelDto) => {
    const title = encodeURIComponent(channel.name ?? 'channel')
    if (channel.kind === 'forum') navigate(`/forum/${channel.id}?name=${title}`)
    else if (channel.kind === 'voice') navigate(`/call/${channel.id}?name=${title}`)
    else navigate(`/chat/${channel.id}?space=true&name=${title}`)
  }

  const create = async () => {
    try {
      await api.createChannel(spaceId, name.trim(), kind)
      setName('')
      setCreating(false)
      const fresh = await api.space(spaceId)
      setSpace(fresh)
      void loadSpaces(true)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not create channel')
    }
  }

  const invite = async () => {
    try {
      const res = await api.spaceInvite(spaceId)
      await navigator.clipboard.writeText(res.code)
      toast.show(`invite copied: ${res.code}`)
    } catch {
      toast.error('could not make an invite')
    }
  }

  const leave = async () => {
    await api.leaveSpace(spaceId)
    await loadSpaces(true)
    navigate('/spaces')
  }

  return (
    <Screen>
      <TopBar
        title={space?.name ?? 'nest'}
        onBack={() => navigate('/spaces')}
        actions={
          <>
            <IconButton label="members" onClick={() => navigate(`/nest/${spaceId}/members`)}>
              <Groups />
            </IconButton>
            <IconButton label="nest settings" onClick={() => navigate('/settings/nests')}>
              <Settings />
            </IconButton>
          </>
        }
      />
      <ScreenBody>
        <div className="nest__hero">
          <Avatar
            name={space?.name ?? 'nest'}
            avatarKey={space?.icon_square_key || space?.icon_key}
            size="hero"
            className="spaces__icon"
          />
          <div className="nest__hero-text">
            <div className="nest__hero-name">{space?.name}</div>
            <div className="nest__hero-meta">
              {space?.member_count ?? 0} members · {channels.length} channels
            </div>
          </div>
        </div>

        <div className="nest__actions">
          <Button variant="tonal" leading={<PersonAdd size={18} />} onClick={() => void invite()}>
            invite
          </Button>
          <Button
            variant="tonal"
            leading={<EmojiEmotions size={18} />}
            onClick={() => navigate(`/nest/${spaceId}/emoji`)}
          >
            emoji
          </Button>
          <Button
            variant="tonal"
            leading={<Groups size={18} />}
            onClick={() => navigate(`/nest/${spaceId}/roles`)}
          >
            roles
          </Button>
          <Button variant="text" leading={<ExitToApp size={18} />} onClick={() => setLeaving(true)}>
            leave
          </Button>
        </div>

        <div className="nest__section">
          <span>channels</span>
          {canManage ? (
            <IconButton label="new channel" tone="accent" onClick={() => setCreating(true)}>
              <Add />
            </IconButton>
          ) : null}
        </div>

        {channels.length === 0 ? (
          <EmptyState icon={<Tag size={28} />} title="no channels" subtitle="make the first one" />
        ) : (
          channels.map((channel) => (
            <ListRow
              key={channel.id}
              onClick={() => open(channel)}
              leading={
                <span className="nest__channel-icon">
                  {channel.kind === 'forum' ? (
                    <Forum size={20} />
                  ) : channel.kind === 'voice' ? (
                    <Campaign size={20} />
                  ) : (
                    <Tag size={20} />
                  )}
                </span>
              }
              title={channel.name ?? 'channel'}
              subtitle={channel.topic ?? undefined}
              trailing={<Badge count={channel.unread ?? 0} />}
            />
          ))
        )}
      </ScreenBody>

      <Sheet open={creating} title="new channel" onClose={() => setCreating(false)}>
        <div className="nest__form">
          <TextField label="channel name" value={name} onChange={setName} />
          <ChipRow>
            {KINDS.map((k) => (
              <Chip key={k.key} label={k.label} active={kind === k.key} onClick={() => setKind(k.key)} />
            ))}
          </ChipRow>
          <Button size="cta" fullWidth onClick={() => void create()}>
            create channel
          </Button>
        </div>
      </Sheet>

      <ConfirmDialog
        open={leaving}
        title="leave this nest?"
        body="you will need a new invite to come back."
        confirmLabel="leave"
        danger
        onConfirm={() => void leave()}
        onClose={() => setLeaving(false)}
      />
    </Screen>
  )
}
