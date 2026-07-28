import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Groups } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { EmptyState, ListRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { useSocial } from '@/store/social'
import './Settings.css'

export default function NestSettingsScreen() {
  const navigate = useNavigate()
  const spaces = useSocial((s) => s.spaces)
  const loadSpaces = useSocial((s) => s.loadSpaces)

  useEffect(() => {
    void loadSpaces(true)
  }, [loadSpaces])

  return (
    <Screen>
      <TopBar title="bird nests" subtitle="manage your nests and channels" onBack={() => navigate(-1)} />
      <ScreenBody>
        {spaces.length === 0 ? (
          <EmptyState icon={<Groups size={28} />} title="no nests yet" />
        ) : (
          spaces.map((space) => (
            <ListRow
              key={space.id}
              onClick={() => navigate(`/settings/nests/${space.id}`)}
              leading={
                <Avatar
                  name={space.name}
                  avatarKey={space.icon_square_key || space.icon_key}
                  className="spaces__icon"
                />
              }
              title={space.name}
              subtitle={`${(space.channels ?? []).length} channels · ${space.role}`}
            />
          ))
        )}
      </ScreenBody>
    </Screen>
  )
}
