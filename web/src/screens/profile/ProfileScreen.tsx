import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { MutualSpaceDto, ProfileDto } from '@/api/dto'
import { Block, ChatBubbleOutline, Edit, PersonAdd, Verified } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { TextField } from '@/components/ui/TextField'
import { Sheet } from '@/components/ui/Overlay'
import { ListRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { fullDate, lastSeen } from '@/lib/format'
import { useSession } from '@/store/session'
import { usePrefs } from '@/store/prefs'
import './Profile.css'

export default function ProfileScreen() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const me = useSession((s) => s.user)
  const nicknames = usePrefs((s) => s.nicknames)
  const setNickname = usePrefs((s) => s.setNickname)
  const [profile, setProfile] = useState<ProfileDto | null>(null)
  const [mutual, setMutual] = useState<MutualSpaceDto[]>([])
  const [editingNick, setEditingNick] = useState(false)
  const [nick, setNick] = useState('')

  useEffect(() => {
    void api
      .profile(id)
      .then((res) => {
        setProfile(res.profile)
        setMutual(res.mutual_spaces ?? [])
      })
      .catch(() => undefined)
  }, [id])

  const mine = me?.id === id
  const name = nicknames[id] || profile?.display_name || profile?.username || 'someone'

  return (
    <Screen>
      <TopBar title="profile" onBack={() => navigate(-1)} />
      <ScreenBody>
        <div
          className="profile__banner"
          style={
            profile?.banner_key
              ? { backgroundImage: `url(${api.mediaUrl(profile.banner_key)})` }
              : { background: profile?.banner_color || 'var(--surface-high)' }
          }
        />
        <div className="profile__head">
          <Avatar name={name} avatarKey={profile?.avatar_key} size="hero" className="profile__avatar" />
          <div className="profile__name">
            {name}
            {(profile?.badges ?? []).includes('verified') ? <Verified size={20} /> : null}
          </div>
          <div className="profile__handle">@{profile?.username}</div>
          {profile?.pronouns ? <div className="profile__pronouns">{profile.pronouns}</div> : null}
          {profile?.status_text ? <div className="profile__status">{profile.status_text}</div> : null}
        </div>

        {(profile?.badges ?? []).length > 0 ? (
          <div className="profile__badges">
            {(profile?.badges ?? []).map((badge) => (
              <span className="profile__badge" key={badge}>
                {badge}
              </span>
            ))}
          </div>
        ) : null}

        <div className="profile__actions">
          {mine ? (
            <Button variant="tonal" leading={<Edit size={18} />} onClick={() => navigate('/settings/editprofile')}>
              edit profile
            </Button>
          ) : (
            <>
              <Button
                leading={<ChatBubbleOutline size={18} />}
                onClick={async () => {
                  const channelId = await api.openDm(id)
                  navigate(`/chat/${channelId}`)
                }}
              >
                message
              </Button>
              <Button
                variant="tonal"
                leading={<PersonAdd size={18} />}
                onClick={async () => {
                  try {
                    await api.addFriend(profile?.username ?? '')
                    toast.show('request sent')
                  } catch (err) {
                    toast.error(err instanceof Error ? err.message : 'could not add')
                  }
                }}
              >
                add friend
              </Button>
              <Button
                variant="text"
                leading={<Block size={18} />}
                onClick={async () => {
                  await api.block(id)
                  toast.show('blocked')
                }}
              >
                block
              </Button>
            </>
          )}
        </div>

        {profile?.about ? <div className="profile__about">{profile.about}</div> : null}

        <div className="profile__meta">
          <div>{lastSeen(profile?.last_online)}</div>
          <div>joined {profile ? fullDate(profile.created_at ?? 0) : ''}</div>
        </div>

        {!mine ? (
          <ListRow
            title="nickname"
            subtitle={nicknames[id] ? `only you see “${nicknames[id]}”` : 'set a private nickname'}
            onClick={() => {
              setNick(nicknames[id] ?? '')
              setEditingNick(true)
            }}
          />
        ) : null}

        {mutual.length > 0 ? (
          <>
            <div className="nest__section">mutual nests</div>
            {mutual.map((space) => (
              <ListRow
                key={space.id}
                onClick={() => navigate(`/nest/${space.id}`)}
                leading={
                  <Avatar
                    name={space.name}
                    avatarKey={space.icon_square_key || space.icon_key}
                    className="spaces__icon"
                  />
                }
                title={space.name}
                subtitle={`${space.member_count} members`}
              />
            ))}
          </>
        ) : null}
      </ScreenBody>

      <Sheet open={editingNick} title="nickname" onClose={() => setEditingNick(false)}>
        <div className="nest__form">
          <TextField label="nickname" value={nick} onChange={setNick} />
          <Button
            size="cta"
            fullWidth
            onClick={() => {
              setNickname(id, nick.trim())
              setEditingNick(false)
            }}
          >
            save
          </Button>
        </div>
      </Sheet>
    </Screen>
  )
}
