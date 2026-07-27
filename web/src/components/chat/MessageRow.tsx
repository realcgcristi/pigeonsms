import { memo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type { MessageDto, SpaceEmojiDto } from '@/api/dto'
import { Description, DoneAll, ErrorOutline, Schedule } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { ContextMenu } from '@/components/ui/Overlay'
import type { MenuItem } from '@/components/ui/Overlay'
import { bytes, timeOfDay } from '@/lib/format'
import { isEmojiOnly, renderMarkdown } from '@/lib/markdown'
import type { ChatMessage } from '@/store/chat'
import './chat.css'

const QUICK = ['❤️', '😂', '👍', '🔥', '😮', '😢']

function MessageRowBase({
  message,
  mine,
  showAuthor,
  emoji,
  replyTo,
  onReply,
  onEdit,
  onDelete,
  onReact,
  onRetry,
  onOpenProfile,
  onInvite,
  onVote,
}: {
  message: ChatMessage
  mine: boolean
  showAuthor: boolean
  emoji: SpaceEmojiDto[]
  replyTo?: MessageDto
  onReply: () => void
  onEdit: () => void
  onDelete: () => void
  onReact: (emoji: string, active: boolean) => void
  onRetry: () => void
  onOpenProfile: (id: string) => void
  onInvite: (code: string) => void
  onVote: (optionId: string) => void
}) {
  const navigate = useNavigate()
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null)

  const author = message.author
  const name = author?.display_name || author?.username || 'someone'
  const allEmoji = emoji.concat(message.custom_emoji ?? [])
  const sticker = (message.metadata as { sticker?: { media_key?: string } } | null)?.sticker
  const big = isEmojiOnly(message.content ?? '')

  const items: MenuItem[] = [
    { key: 'reply', label: 'reply', onSelect: onReply },
    {
      key: 'copy',
      label: 'copy text',
      onSelect: () => void navigator.clipboard.writeText(message.content ?? ''),
    },
  ]
  if (mine && message.state !== 'failed') items.push({ key: 'edit', label: 'edit', onSelect: onEdit })
  if (message.state === 'failed') items.push({ key: 'retry', label: 'try again', onSelect: onRetry })
  if (mine) items.push({ key: 'delete', label: 'delete', danger: true, onSelect: onDelete })

  if (message.kind === 'command') {
    return (
      <div className="msg msg--command">
        <div className="msg__command">
          <span className="msg__command-caller">{name}</span>
          <span className="msg__command-text">{message.content}</span>
        </div>
      </div>
    )
  }

  if (message.deleted) {
    return (
      <div className={mine ? 'msg msg--mine' : 'msg'}>
        <div className="msg__body">
          <div className="msg__bubble msg__bubble--plain">
            <em style={{ color: 'var(--text-tertiary)' }}>message deleted</em>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div
      className={mine ? 'msg msg--mine' : 'msg'}
      onContextMenu={(e) => {
        e.preventDefault()
        setMenu({ x: e.clientX, y: e.clientY })
      }}
    >
      {!mine ? (
        <Avatar
          name={name}
          avatarKey={author?.avatar_key}
          size={showAuthor ? 'sm' : 24}
          style={showAuthor ? undefined : { visibility: 'hidden' }}
          onClick={() => author && onOpenProfile(author.id)}
        />
      ) : null}
      <div className="msg__body">
        {!mine && showAuthor ? (
          <div className="msg__author">
            {name}
            {author?.is_bot ? <span className="msg__bot">BOT</span> : null}
          </div>
        ) : null}

        {replyTo ? (
          <div className="msg__reply">
            {replyTo.author?.display_name || replyTo.author?.username}: {replyTo.content?.slice(0, 80)}
          </div>
        ) : null}

        {sticker?.media_key ? (
          <img className="msg__sticker" src={api.mediaUrl(sticker.media_key)} alt="sticker" />
        ) : null}

        {message.attachment ? (
          (message.attachment.type ?? '').startsWith('image/') ? (
            <img
              className="msg__attachment"
              src={api.mediaUrl(message.attachment.key)}
              alt={message.attachment.name ?? 'image'}
              loading="lazy"
            />
          ) : (message.attachment.type ?? '').startsWith('video/') ? (
            <video className="msg__attachment" src={api.mediaUrl(message.attachment.key)} controls />
          ) : (message.attachment.type ?? '').startsWith('audio/') ? (
            <audio src={api.mediaUrl(message.attachment.key)} controls />
          ) : (
            <a
              className="msg__file"
              href={api.mediaUrl(message.attachment.key)}
              target="_blank"
              rel="noreferrer noopener"
            >
              <Description size={20} />
              <span>
                {message.attachment.name ?? 'file'}
                <br />
                <small>{bytes(message.attachment.size)}</small>
              </span>
            </a>
          )
        ) : null}

        {message.poll ? (
          <div className={message.state === 'failed' ? 'msg__bubble msg__bubble--failed' : 'msg__bubble'}>
            <div className="msg__poll">
              <strong>{message.poll.question}</strong>
              {(message.poll.options ?? []).map((option) => {
                const total = message.poll?.total_votes || 0
                const pct = total > 0 ? Math.round(((option.votes ?? 0) / total) * 100) : 0
                return (
                  <button
                    key={option.id}
                    type="button"
                    className="msg__poll-option"
                    onClick={() => onVote(option.id)}
                  >
                    <span className="msg__poll-fill" style={{ width: `${pct}%` }} />
                    <span className="msg__poll-text">
                      <span>
                        {option.me ? '● ' : ''}
                        {option.text}
                      </span>
                      <span>{pct}%</span>
                    </span>
                  </button>
                )
              })}
              <small>{message.poll.total_votes ?? 0} votes</small>
            </div>
          </div>
        ) : null}

        {message.content && !sticker ? (
          <div
            className={[
              'msg__bubble',
              big ? 'msg__bubble--plain' : '',
              message.state === 'failed' ? 'msg__bubble--failed' : '',
            ]
              .filter(Boolean)
              .join(' ')}
          >
            {renderMarkdown(message.content, {
              emoji: allEmoji,
              big,
              onInvite,
              onMention: (username) => navigate(`/search?q=${encodeURIComponent(username)}`),
            })}
          </div>
        ) : null}

        {(message.reactions ?? []).length > 0 ? (
          <div className="msg__reactions">
            {(message.reactions ?? []).map((reaction) => {
              const custom = allEmoji.find((e) => e.name === reaction.emoji.replace(/:/g, ''))
              return (
                <button
                  key={reaction.emoji}
                  type="button"
                  className={reaction.me ? 'msg__reaction msg__reaction--me' : 'msg__reaction'}
                  onClick={() => onReact(reaction.emoji, !!reaction.me)}
                >
                  {custom ? (
                    <img
                      src={api.mediaUrl(custom.media_key ?? '')}
                      alt={reaction.emoji}
                      style={{ width: 16, height: 16 }}
                    />
                  ) : (
                    reaction.emoji
                  )}
                  {reaction.count}
                </button>
              )
            })}
          </div>
        ) : null}

        <div className="msg__meta">
          {message.state === 'pending' ? <Schedule size={12} /> : null}
          {message.state === 'failed' ? <ErrorOutline size={12} /> : null}
          {message.state === 'sent' && mine ? <DoneAll size={12} /> : null}
          {timeOfDay(message.created_at ?? 0)}
          {message.edited_at ? ' · edited' : ''}
        </div>
      </div>

      <ContextMenu
        open={!!menu}
        x={menu?.x ?? 0}
        y={menu?.y ?? 0}
        items={items.concat(
          QUICK.map((e) => ({
            key: `react-${e}`,
            label: `react ${e}`,
            onSelect: () => onReact(e, false),
          })),
        )}
        onClose={() => setMenu(null)}
      />
    </div>
  )
}

export const MessageRow = memo(MessageRowBase)

export default MessageRow
