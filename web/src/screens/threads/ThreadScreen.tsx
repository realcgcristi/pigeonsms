import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, nonce } from '@/api/client'
import type { MessageDto, ThreadDto } from '@/api/dto'
import { Bookmark, Send } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { IconButton } from '@/components/ui/IconButton'
import { Screen, TopBar } from '@/components/ui/Layout'
import { relativeTime } from '@/lib/format'
import { renderMarkdown } from '@/lib/markdown'
import { useChat } from '@/store/chat'
import '@/components/chat/chat.css'

export default function ThreadScreen() {
  const { threadId = '' } = useParams()
  const navigate = useNavigate()
  const emoji = useChat((s) => s.emoji)
  const [thread, setThread] = useState<ThreadDto | null>(null)
  const [root, setRoot] = useState<MessageDto | null>(null)
  const [messages, setMessages] = useState<MessageDto[]>([])
  const [text, setText] = useState('')
  const [following, setFollowing] = useState(false)

  const load = useCallback(async () => {
    const info = await api.thread(threadId)
    setThread(info.thread)
    setRoot(info.root ?? null)
    const page = await api.threadMessages(threadId)
    setMessages(page.messages ?? [])
  }, [threadId])

  useEffect(() => {
    void load()
  }, [load])

  const send = async () => {
    if (!text.trim()) return
    const created = await api.sendThreadMessage(threadId, text.trim(), nonce())
    setMessages(messages.concat(created))
    setText('')
  }

  return (
    <Screen className="chat">
      <TopBar
        title={thread?.title || 'thread'}
        subtitle={`${thread?.reply_count ?? 0} replies`}
        onBack={() => navigate(-1)}
        actions={
          <IconButton
            label="follow"
            tone={following ? 'accent' : 'default'}
            onClick={async () => {
              await api.followThread(threadId, !following)
              setFollowing(!following)
            }}
          >
            <Bookmark />
          </IconButton>
        }
      />
      <div className="chat__list">
        {root ? (
          <div className="msg">
            <Avatar
              name={root.author?.display_name || root.author?.username || 'someone'}
              avatarKey={root.author?.avatar_key}
              size="sm"
            />
            <div className="msg__body">
              <div className="msg__author">{root.author?.display_name || root.author?.username}</div>
              <div className="msg__bubble">{renderMarkdown(root.content ?? '', { emoji })}</div>
            </div>
          </div>
        ) : null}
        {messages.map((message) => (
          <div className="msg" key={message.id}>
            <Avatar
              name={message.author?.display_name || message.author?.username || 'someone'}
              avatarKey={message.author?.avatar_key}
              size="sm"
            />
            <div className="msg__body">
              <div className="msg__author">
                {message.author?.display_name || message.author?.username} ·{' '}
                {relativeTime(message.created_at ?? 0)}
              </div>
              <div className="msg__bubble">{renderMarkdown(message.content ?? '', { emoji })}</div>
            </div>
          </div>
        ))}
      </div>
      <div className="composer">
        <textarea
          className="composer__input"
          rows={1}
          placeholder="reply in thread"
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              void send()
            }
          }}
        />
        <IconButton label="send" tone="accent" filled onClick={() => void send()}>
          <Send />
        </IconButton>
      </div>
    </Screen>
  )
}
