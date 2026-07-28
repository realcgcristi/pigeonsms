import { useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import type { MessageDto, ScheduledMessageDto, SpaceMemberDto, SuperPinDto } from '@/api/dto'
import { Close, DeleteOutline, Forum, Image, PushPin, Schedule, Search } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { EmptyState, Tabs } from '@/components/ui/Layout'
import { relativeTime } from '@/lib/format'

type DetailsTab = 'about' | 'media' | 'pins' | 'scheduled'

export function ConversationDetails({
  channelId,
  title,
  avatarKey,
  spaceId,
  messages,
  pins,
  superPin,
  onClose,
  onSearch,
  onJump,
  onMedia,
  onUnpin,
  onRemoveSuperPin,
}: {
  channelId: string
  title: string
  avatarKey?: string | null
  spaceId?: string | null
  messages: MessageDto[]
  pins: MessageDto[]
  superPin: SuperPinDto | null
  onClose: () => void
  onSearch: () => void
  onJump: (messageId: string) => void
  onMedia: (message: MessageDto) => void
  onUnpin: (messageId: string) => void
  onRemoveSuperPin: () => void
}) {
  const [tab, setTab] = useState<DetailsTab>('about')
  const [members, setMembers] = useState<SpaceMemberDto[]>([])
  const [scheduled, setScheduled] = useState<ScheduledMessageDto[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    let alive = true
    setLoading(true)
    Promise.all([
      spaceId ? api.spaceMembers(spaceId).catch(() => []) : Promise.resolve([]),
      api.listScheduled().catch(() => []),
    ]).then(([nextMembers, nextScheduled]) => {
      if (!alive) return
      setMembers(nextMembers)
      setScheduled(nextScheduled.filter((item) => item.channel_id === channelId))
      setLoading(false)
    })
    return () => {
      alive = false
    }
  }, [channelId, spaceId])

  const media = useMemo(
    () =>
      messages
        .filter((message) => {
          const type = message.attachment?.type ?? ''
          return type.startsWith('image/') || type.startsWith('video/')
        })
        .reverse(),
    [messages],
  )

  const cancelScheduled = async (id: string) => {
    await api.cancelScheduled(id)
    setScheduled((items) => items.filter((item) => item.id !== id))
  }

  return (
    <div className="chat-details">
      <div className="chat-details__head">
        <strong>conversation details</strong>
        <button type="button" onClick={onClose} aria-label="close details">
          <Close size={20} />
        </button>
      </div>

      <div className="chat-details__hero">
        <Avatar name={title} avatarKey={avatarKey} size="lg" />
        <div>
          <strong>{title}</strong>
          <small>{spaceId ? `${members.length || '…'} nest members` : 'direct conversation'}</small>
        </div>
      </div>

      <Button variant="tonal" fullWidth leading={<Search size={18} />} onClick={onSearch}>
        search this conversation
      </Button>

      <Tabs
        tabs={[
          { key: 'about', label: 'about' },
          { key: 'media', label: `media ${media.length || ''}`.trim() },
          { key: 'pins', label: `pins ${pins.length || ''}`.trim() },
          { key: 'scheduled', label: `later ${scheduled.length || ''}`.trim() },
        ]}
        value={tab}
        onChange={setTab}
      />

      <div className="chat-details__body">
        {tab === 'about' ? (
          <>
            {superPin ? (
              <section className="chat-details__card chat-details__card--accent">
                <span className="chat-details__eyebrow"><PushPin size={15} /> super pin</span>
                <button type="button" onClick={() => onJump(superPin.message.id)}>
                  {superPin.message.content || 'pinned media'}
                </button>
                <Button variant="text" onClick={onRemoveSuperPin}>remove super pin</Button>
              </section>
            ) : null}
            {spaceId ? (
              <section className="chat-details__section">
                <h3>members</h3>
                {members.slice(0, 16).map((member) => (
                  <div className="chat-details__member" key={member.id}>
                    <Avatar
                      name={member.display_name || member.username}
                      avatarKey={member.avatar_square_key || member.avatar_key}
                      size="xs"
                      showPresence
                      online={member.active}
                    />
                    <span className="chat-details__member-name">{member.display_name || member.username}</span>
                    <small>{member.role}</small>
                  </div>
                ))}
              </section>
            ) : (
              <section className="chat-details__card">
                <span className="chat-details__eyebrow"><Forum size={15} /> quick actions</span>
                <p>Use a message&apos;s menu to reply, pin, create a thread, or make it the Super Pin.</p>
              </section>
            )}
          </>
        ) : null}

        {tab === 'media' ? (
          media.length ? (
            <div className="chat-details__media">
              {media.map((message) => (
                <button key={message.id} type="button" onClick={() => onMedia(message)}>
                  {message.attachment?.type?.startsWith('video/') ? (
                    <video src={api.mediaUrl(message.attachment.key)} preload="metadata" />
                  ) : (
                    <img src={api.mediaUrl(message.attachment?.key ?? '')} alt={message.attachment?.name ?? 'shared image'} />
                  )}
                </button>
              ))}
            </div>
          ) : (
            <EmptyState icon={<Image size={26} />} title="no shared media yet" subtitle="images and videos appear here" />
          )
        ) : null}

        {tab === 'pins' ? (
          pins.length ? (
            pins.map((message) => (
              <article className="chat-details__message" key={message.id}>
                <button type="button" onClick={() => onJump(message.id)}>
                  <strong>{message.author?.display_name || message.author?.username}</strong>
                  <span>{message.content || message.attachment?.name || 'attachment'}</span>
                  <small>{relativeTime(message.created_at)}</small>
                </button>
                <button type="button" aria-label="unpin" onClick={() => onUnpin(message.id)}>
                  <DeleteOutline size={18} />
                </button>
              </article>
            ))
          ) : (
            <EmptyState icon={<PushPin size={26} />} title="nothing pinned" subtitle="pin important messages from their menu" />
          )
        ) : null}

        {tab === 'scheduled' ? (
          scheduled.length ? (
            scheduled.map((item) => (
              <article className="chat-details__message" key={item.id}>
                <div>
                  <strong><Schedule size={15} /> sends later</strong>
                  <span>{item.content}</span>
                  <small>{new Date(item.send_at).toLocaleString()}</small>
                </div>
                <button type="button" aria-label="cancel scheduled message" onClick={() => void cancelScheduled(item.id)}>
                  <DeleteOutline size={18} />
                </button>
              </article>
            ))
          ) : (
            <EmptyState
              icon={<Schedule size={26} />}
              title={loading ? 'loading…' : 'nothing scheduled'}
              subtitle="scheduled messages for this conversation appear here"
            />
          )
        ) : null}
      </div>
    </div>
  )
}

export default ConversationDetails
