import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { PermissionName, SpaceRoleDto } from '@/api/dto'
import { PERMISSION_NAMES } from '@/api/dto'
import { Add, Delete, Shield } from '@/components/icons'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { Switch } from '@/components/ui/Switch'
import { TextField } from '@/components/ui/TextField'
import { ConfirmDialog, Sheet } from '@/components/ui/Overlay'
import { EmptyState, ListRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import './Spaces.css'

const COLORS = ['#FF9D76', '#FF8FB0', '#FFC46B', '#7FD8A4', '#76BEFF', '#B8A7F5']

function label(name: string): string {
  return name.toLowerCase().replace(/_/g, ' ')
}

export default function NestRolesScreen() {
  const { spaceId = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const [roles, setRoles] = useState<SpaceRoleDto[]>([])
  const [editing, setEditing] = useState<SpaceRoleDto | null>(null)
  const [creating, setCreating] = useState(false)
  const [deleting, setDeleting] = useState<SpaceRoleDto | null>(null)
  const [name, setName] = useState('')
  const [color, setColor] = useState<string>(COLORS[0] || '#FF9D76')
  const [selected, setSelected] = useState<Set<PermissionName>>(new Set())

  const load = useCallback(async () => {
    setRoles(await api.spaceRoles(spaceId))
  }, [spaceId])

  useEffect(() => {
    void load()
  }, [load])

  const openCreate = () => {
    setName('')
    setColor(COLORS[0] || '#FF9D76')
    setSelected(new Set())
    setEditing(null)
    setCreating(true)
  }

  const openEdit = (role: SpaceRoleDto) => {
    setName(role.name ?? '')
    setColor(role.color || COLORS[0] || '#FF9D76')
    setSelected(new Set((role.permission_names ?? []) as PermissionName[]))
    setEditing(role)
    setCreating(true)
  }

  const save = async () => {
    try {
      const permissions = Array.from(selected)
      if (editing) await api.updateRole(spaceId, editing.id, { name: name.trim(), permissions, color })
      else await api.createRole(spaceId, name.trim(), permissions, color)
      setCreating(false)
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not save role')
    }
  }

  return (
    <Screen>
      <TopBar
        title="roles"
        onBack={() => navigate(-1)}
        actions={
          <IconButton label="new role" tone="accent" filled onClick={openCreate}>
            <Add />
          </IconButton>
        }
      />
      <ScreenBody>
        {roles.length === 0 ? (
          <EmptyState
            icon={<Shield size={28} />}
            title="no roles yet"
            subtitle="roles hand out permissions to groups of members"
            action={
              <Button variant="tonal" onClick={openCreate}>
                create a role
              </Button>
            }
          />
        ) : (
          roles.map((role) => (
            <ListRow
              key={role.id}
              onClick={() => openEdit(role)}
              leading={
                <span className="nest__role-dot" style={{ background: role.color || 'var(--accent)' }} />
              }
              title={role.name}
              subtitle={`${role.permission_names?.length ?? 0} permissions`}
              trailing={
                <IconButton
                  label="delete role"
                  tone="danger"
                  onClick={(e) => {
                    e.stopPropagation()
                    setDeleting(role)
                  }}
                >
                  <Delete />
                </IconButton>
              }
            />
          ))
        )}
      </ScreenBody>

      <Sheet open={creating} title={editing ? 'edit role' : 'new role'} onClose={() => setCreating(false)}>
        <div className="nest__form">
          <TextField label="role name" value={name} onChange={setName} />
          <div className="nest__kinds">
            {COLORS.map((c) => (
              <button
                key={c}
                type="button"
                aria-label={c}
                onClick={() => setColor(c)}
                style={{
                  width: 32,
                  height: 32,
                  borderRadius: 999,
                  background: c,
                  border: color === c ? '2px solid var(--text-primary)' : '2px solid transparent',
                  cursor: 'pointer',
                }}
              />
            ))}
          </div>
        </div>
        {PERMISSION_NAMES.map((permission) => (
          <div className="nest__perm" key={permission}>
            <span>{label(permission)}</span>
            <Switch
              checked={selected.has(permission)}
              onChange={(checked) => {
                const next = new Set(selected)
                if (checked) next.add(permission)
                else next.delete(permission)
                setSelected(next)
              }}
            />
          </div>
        ))}
        <div className="nest__form">
          <Button size="cta" fullWidth onClick={() => void save()}>
            {editing ? 'save role' : 'create role'}
          </Button>
        </div>
      </Sheet>

      <ConfirmDialog
        open={!!deleting}
        title={`delete ${deleting?.name ?? 'role'}?`}
        confirmLabel="delete"
        danger
        onConfirm={async () => {
          if (!deleting) return
          await api.deleteRole(spaceId, deleting.id)
          await load()
        }}
        onClose={() => setDeleting(null)}
      />
    </Screen>
  )
}
