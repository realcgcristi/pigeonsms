import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { PermissionsResponse, SpaceMemberDto, SpaceRoleDto } from '@/api/dto'
import { Add, Block, Check, PersonRemove } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { SearchField } from '@/components/ui/SearchField'
import { ConfirmDialog, Sheet } from '@/components/ui/Overlay'
import { EmptyState, ListRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { isOnline } from '@/lib/format'
import { useSession } from '@/store/session'
import './Spaces.css'

export default function NestMembersScreen() {
  const { spaceId = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const me = useSession((s) => s.user)
  const [members, setMembers] = useState<SpaceMemberDto[]>([])
  const [roles, setRoles] = useState<SpaceRoleDto[]>([])
  const [perms, setPerms] = useState<PermissionsResponse | null>(null)
  const [query, setQuery] = useState('')
  const [target, setTarget] = useState<SpaceMemberDto | null>(null)
  const [draftRoles, setDraftRoles] = useState<Set<string>>(new Set())
  const [kicking, setKicking] = useState<SpaceMemberDto | null>(null)
  const [banning, setBanning] = useState<SpaceMemberDto | null>(null)

  const load = useCallback(async () => {
    const [m, r] = await Promise.all([api.spaceMembers(spaceId), api.spaceRoles(spaceId).catch(() => [])])
    setMembers(m)
    setRoles(r)
  }, [spaceId])

  useEffect(() => {
    void load()
    void api.spacePermissions(spaceId).then(setPerms).catch(() => undefined)
  }, [load, spaceId])

  const canModerate = perms?.is_owner || perms?.permission_names?.includes('KICK_MEMBERS')
  const canManageRoles = perms?.is_owner || perms?.permission_names?.includes('MANAGE_ROLES')

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return members
    return members.filter((m) => (m.display_name || m.username).toLowerCase().includes(q))
  }, [members, query])

  const openRoles = (member: SpaceMemberDto) => {
    setDraftRoles(new Set(member.role_ids ?? []))
    setTarget(member)
  }

  const saveRoles = async () => {
    if (!target) return
    try {
      await api.setMemberRoles(spaceId, target.id, Array.from(draftRoles))
      toast.show('roles updated')
      setTarget(null)
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not save roles')
    }
  }

  return (
    <Screen>
      <TopBar title="members" subtitle={`${members.length}`} onBack={() => navigate(-1)} />
      <div className="friends__search">
        <SearchField value={query} onChange={setQuery} placeholder="search members" />
      </div>
      <ScreenBody>
        {visible.length === 0 ? (
          <EmptyState title="nobody here" />
        ) : (
          visible.map((member) => {
            const name = member.display_name || member.username
            const roleNames = (member.role_ids ?? [])
              .map((id: string) => roles.find((r) => r.id === id)?.name)
              .filter(Boolean)
              .join(', ')
            return (
              <ListRow
                key={member.id}
                onClick={() => navigate(`/profile/${member.id}`)}
                leading={
                  <Avatar
                    name={name}
                    avatarKey={member.avatar_key}
                    showPresence
                    online={isOnline(member.last_online)}
                  />
                }
                title={name}
                subtitle={roleNames || member.role}
                trailing={
                  <span className="nest__member-actions">
                    {canManageRoles ? (
                      <IconButton
                        label="assign roles"
                        tone="accent"
                        onClick={(e) => {
                          e.stopPropagation()
                          openRoles(member)
                        }}
                      >
                        <Add />
                      </IconButton>
                    ) : null}
                    {canModerate && member.id !== me?.id && member.role !== 'owner' ? (
                      <>
                        <IconButton
                          label="kick"
                          onClick={(e) => {
                            e.stopPropagation()
                            setKicking(member)
                          }}
                        >
                          <PersonRemove />
                        </IconButton>
                        <IconButton
                          label="ban"
                          tone="danger"
                          onClick={(e) => {
                            e.stopPropagation()
                            setBanning(member)
                          }}
                        >
                          <Block />
                        </IconButton>
                      </>
                    ) : null}
                  </span>
                }
              />
            )
          })
        )}
      </ScreenBody>

      <Sheet
        open={!!target}
        title={target ? `roles for ${target.display_name || target.username}` : ''}
        onClose={() => setTarget(null)}
      >
        {roles.length === 0 ? (
          <EmptyState title="no roles yet" subtitle="create one in the roles screen" />
        ) : (
          roles.map((role) => {
            const on = draftRoles.has(role.id)
            return (
              <ListRow
                key={role.id}
                onClick={() => {
                  const next = new Set(draftRoles)
                  if (on) next.delete(role.id)
                  else next.add(role.id)
                  setDraftRoles(next)
                }}
                leading={
                  <span className="nest__role-dot" style={{ background: role.color || 'var(--accent)' }} />
                }
                title={role.name}
                subtitle={`${role.permission_names?.length ?? 0} permissions`}
                trailing={on ? <Check /> : null}
              />
            )
          })
        )}
        <div className="nest__form">
          <Button size="cta" fullWidth onClick={() => void saveRoles()}>
            save roles
          </Button>
        </div>
      </Sheet>

      <ConfirmDialog
        open={!!kicking}
        title={`kick ${kicking ? kicking.display_name || kicking.username : ''}?`}
        body="they can rejoin with a new invite."
        confirmLabel="kick"
        danger
        onConfirm={async () => {
          if (!kicking) return
          await api.kickMember(spaceId, kicking.id)
          await load()
        }}
        onClose={() => setKicking(null)}
      />

      <ConfirmDialog
        open={!!banning}
        title={`ban ${banning ? banning.display_name || banning.username : ''}?`}
        body="they will not be able to join again."
        confirmLabel="ban"
        danger
        onConfirm={async () => {
          if (!banning) return
          await api.banMember(spaceId, banning.id)
          await load()
        }}
        onClose={() => setBanning(null)}
      />
    </Screen>
  )
}
