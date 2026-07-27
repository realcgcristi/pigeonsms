import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, nonce } from '@/api/client'
import { Add, Groups, Link as LinkIcon } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { TextField } from '@/components/ui/TextField'
import { Sheet } from '@/components/ui/Overlay'
import { EmptyState, ListRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { useSocial } from '@/store/social'
import './Spaces.css'

export default function SpacesScreen() {
  const navigate = useNavigate()
  const toast = useToast()
  const spaces = useSocial((s) => s.spaces)
  const loadSpaces = useSocial((s) => s.loadSpaces)
  const [creating, setCreating] = useState(false)
  const [joining, setJoining] = useState(false)
  const [name, setName] = useState('')
  const [code, setCode] = useState('')

  useEffect(() => {
    void loadSpaces(true)
  }, [loadSpaces])

  const create = async () => {
    try {
      const space = await api.createSpace(name.trim(), nonce())
      setName('')
      setCreating(false)
      await loadSpaces(true)
      navigate(`/nest/${space.id}`)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not create nest')
    }
  }

  const join = async () => {
    try {
      const spaceId = await api.joinSpace(code.trim())
      setCode('')
      setJoining(false)
      await loadSpaces(true)
      navigate(`/nest/${spaceId}`)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'invite not valid')
    }
  }

  return (
    <Screen>
      <TopBar
        title="nests"
        actions={
          <>
            <IconButton label="join with invite" onClick={() => setJoining(true)}>
              <LinkIcon />
            </IconButton>
            <IconButton label="new nest" tone="accent" onClick={() => setCreating(true)}>
              <Add />
            </IconButton>
          </>
        }
      />
      <ScreenBody tabbed>
        {spaces.length === 0 ? (
          <EmptyState
            icon={<Groups size={30} />}
            title="no nests yet"
            subtitle="build one or join with an invite code"
            action={
              <Button variant="tonal" onClick={() => setCreating(true)}>
                build a nest
              </Button>
            }
          />
        ) : (
          spaces.map((space) => {
            const unread = (space.channels ?? []).reduce((sum, c) => sum + (c.unread ?? 0), 0)
            return (
              <ListRow
                key={space.id}
                onClick={() => navigate(`/nest/${space.id}`)}
                leading={
                  <Avatar
                    name={space.name}
                    avatarKey={space.icon_square_key || space.icon_key}
                    size="lg"
                    className="spaces__icon"
                  />
                }
                title={space.name}
                subtitle={`${space.member_count} ${space.member_count === 1 ? 'member' : 'members'} · ${
                  (space.channels ?? []).length
                } channels`}
                trailing={unread > 0 ? <span className="spaces__badge">{unread}</span> : null}
              />
            )
          })
        )}
      </ScreenBody>

      <Sheet open={creating} title="build a nest" onClose={() => setCreating(false)}>
        <div className="spaces__form">
          <TextField label="nest name" value={name} onChange={setName} />
          <Button size="cta" fullWidth onClick={() => void create()}>
            create
          </Button>
        </div>
      </Sheet>

      <Sheet open={joining} title="join a nest" onClose={() => setJoining(false)}>
        <div className="spaces__form">
          <TextField label="invite code" value={code} onChange={setCode} placeholder="SPC-..." />
          <Button size="cta" fullWidth onClick={() => void join()}>
            join
          </Button>
        </div>
      </Sheet>
    </Screen>
  )
}
