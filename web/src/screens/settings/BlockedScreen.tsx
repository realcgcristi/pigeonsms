import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { BlockedUserDto } from '@/api/dto'
import { Block } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { EmptyState, ListRow, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'

export default function BlockedScreen() {
  const navigate = useNavigate()
  const [blocks, setBlocks] = useState<BlockedUserDto[]>([])

  const load = useCallback(async () => {
    setBlocks(await api.blocks())
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  return (
    <Screen>
      <TopBar title="blocked users" onBack={() => navigate(-1)} />
      <ScreenBody>
        {blocks.length === 0 ? (
          <EmptyState icon={<Block size={28} />} title="no blocked users" />
        ) : (
          blocks.map((person) => (
            <ListRow
              key={person.id}
              leading={<Avatar name={person.display_name || person.username} avatarKey={person.avatar_key} />}
              title={person.display_name || person.username}
              subtitle={`@${person.username}`}
              trailing={
                <Button
                  variant="text"
                  onClick={async () => {
                    await api.unblock(person.id)
                    await load()
                  }}
                >
                  unblock
                </Button>
              }
            />
          ))
        )}
      </ScreenBody>
    </Screen>
  )
}
