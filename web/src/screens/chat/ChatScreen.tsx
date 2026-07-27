import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { ChannelCommandDto, InvitePreviewResponse, SpaceEmojiDto } from '@/api/dto'
import {
  AttachFile,
  Call,
  EmojiEmotions,
  Forum,
  Poll,
  PushPin,
  Search,
  Send,
} from '@/components/icons'
import { MessageRow } from '@/components/chat/MessageRow'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { TextField } from '@/components/ui/TextField'
import { Dialog, Sheet } from '@/components/ui/Overlay'
import { Screen, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { daySeparator, sameDay } from '@/lib/format'
import { emojiQueryAt } from '@/lib/markdown'
import type { ChatMessage } from '@/store/chat'
import { useChat } from '@/store/chat'
import { useSession } from '@/store/session'
import { useSocial } from '@/store/social'
import '@/components/chat/chat.css'

const UNICODE = ['😀', '😂', '🥲', '😍', '🤔', '👍', '🙏', '🔥', '🎉', '💜', '😭', '😎', '🤯', '🕊️', '✨', '💀']

type CommandOption = NonNullable<ChannelCommandDto['options']>[number]
type CommandValues = Record<string, string | number | boolean>
type CommandToken = { key: string | null; value: string }

const TOKEN = /([a-zA-Z0-9_-]+):(?:"([^"]*)"|'([^']*)'|(\S+))|"([^"]*)"|'([^']*)'|(\S+)/g

function commandTokens(input: string): CommandToken[] {
  const out: CommandToken[] = []
  TOKEN.lastIndex = 0
  let match = TOKEN.exec(input)
  while (match) {
    if (match[1] !== undefined) {
      out.push({ key: match[1], value: match[2] ?? match[3] ?? match[4] ?? '' })
    } else {
      out.push({ key: null, value: match[5] ?? match[6] ?? match[7] ?? '' })
    }
    match = TOKEN.exec(input)
  }
  return out
}

function coerce(option: CommandOption | undefined, value: string): string | number | boolean {
  const type = option?.type
  if (type === 'boolean') return value === 'true' || value === '1' || value === 'yes'
  if (type === 'integer' || type === 'number') {
    const parsed = Number(value)
    if (Number.isNaN(parsed)) return value
    return type === 'integer' ? Math.trunc(parsed) : parsed
  }
  return value
}

function orderedOptions(command: ChannelCommandDto): CommandOption[] {
  const options = command.options ?? []
  return options.filter((o) => o.required).concat(options.filter((o) => !o.required))
}

function buildInvocation(command: ChannelCommandDto, args: string) {
  const options = command.options ?? []
  const values: CommandValues = {}
  const bare: string[] = []
  for (const token of commandTokens(args)) {
    const option = token.key ? options.find((o) => o.name === token.key) : undefined
    if (option) values[option.name] = coerce(option, token.value)
    else bare.push(token.key ? `${token.key}:${token.value}` : token.value)
  }
  for (const option of orderedOptions(command)) {
    if (!bare.length) break
    if (option.name in values) continue
    values[option.name] = coerce(option, bare.shift() ?? '')
  }
  return { values, missing: options.find((o) => o.required && !(o.name in values)) }
}

function commandSignature(command: ChannelCommandDto): string {
  return orderedOptions(command)
    .map((option) => (option.required ? option.name : `${option.name}?`))
    .join(' ')
}

function optionHint(option: CommandOption): string {
  const suffix = option.required ? '' : ' (optional)'
  return option.description ? `${option.name}${suffix} — ${option.description}` : `${option.name}${suffix}`
}

type ComposerProps = {
  channelId: string
  emoji: SpaceEmojiDto[]
  editing: { id: string; content: string } | null
  isSpace: boolean
  onSubmit: (text: string) => void
  onAttach: (file: File) => void
  onSticker: (id: string) => void
  onPoll: () => void
}

const Composer = memo(function Composer({
  channelId,
  emoji,
  editing,
  isSpace,
  onSubmit,
  onAttach,
  onSticker,
  onPoll,
}: ComposerProps) {
  const [text, setText] = useState('')
  const [picker, setPicker] = useState(false)
  const [commands, setCommands] = useState<Record<string, ChannelCommandDto[]>>({})
  const [active, setActive] = useState(0)
  const [dismissed, setDismissed] = useState(false)
  const [sending, setSending] = useState(false)
  const typingAt = useRef(0)
  const requested = useRef<Record<string, boolean>>({})
  const fileRef = useRef<HTMLInputElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const toast = useToast()

  useEffect(() => {
    if (editing) {
      setText(editing.content)
      inputRef.current?.focus()
    }
  }, [editing])

  const slash = !editing && text.startsWith('/')

  useEffect(() => {
    if (!slash || requested.current[channelId]) return
    requested.current[channelId] = true
    api
      .channelCommands(channelId)
      .then((list) => setCommands((prev) => ({ ...prev, [channelId]: list })))
      .catch(() => setCommands((prev) => ({ ...prev, [channelId]: [] })))
  }, [channelId, slash])

  const available = commands[channelId] ?? []
  const raw = slash ? text.slice(1) : ''
  const head = raw.split(' ')[0] ?? ''
  const args = raw.slice(head.length).replace(/^ /, '')
  const inArgs = slash && raw.includes(' ')
  const picked = available.find((c) => c.name === head.toLowerCase())

  const matches = useMemo(() => {
    if (!slash || inArgs) return []
    const needle = head.toLowerCase()
    return available.filter((c) => c.name.startsWith(needle)).slice(0, 8)
  }, [available, head, inArgs, slash])

  const paletteOpen = !dismissed && matches.length > 0
  const index = Math.min(active, matches.length - 1)

  const hint = useMemo(() => {
    if (!inArgs || !picked) return null
    const partial = args.length > 0 && !args.endsWith(' ')
    const filled = buildInvocation(picked, partial ? args.replace(/\S+$/, '') : args).values
    const next = orderedOptions(picked).find((o) => !(o.name in filled))
    return next ? optionHint(next) : `/${picked.name} — ${picked.description}`
  }, [args, inArgs, picked])

  const complete = (command: ChannelCommandDto) => {
    setText(`/${command.name} `)
    setActive(0)
    setDismissed(false)
    inputRef.current?.focus()
  }

  const runCommand = async (body: string) => {
    const name = (body.slice(1).split(' ')[0] ?? '').toLowerCase()
    const command = available.find((c) => c.name === name)
    if (!command) {
      toast.error(`unknown command /${name}`)
      return
    }
    const invocation = buildInvocation(command, body.slice(1).slice(name.length).trim())
    if (invocation.missing) {
      toast.error(`${invocation.missing.name} is required`)
      return
    }
    setSending(true)
    try {
      await api.sendInteraction(channelId, {
        command: command.name,
        bot_id: command.bot.id,
        options: invocation.values,
        nonce: crypto.randomUUID(),
      })
      setText('')
      setDismissed(false)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'command failed')
    } finally {
      setSending(false)
    }
  }

  const query = emojiQueryAt(text, text.length)
  const suggestions = useMemo(
    () => (query && !slash ? emoji.filter((e) => e.name?.startsWith(query)).slice(0, 12) : []),
    [emoji, query, slash],
  )

  const groups = useMemo(
    () =>
      Object.entries(
        emoji.reduce<Record<string, SpaceEmojiDto[]>>((acc, item) => {
          const key = item.space_name || 'nest'
          acc[key] = (acc[key] ?? []).concat(item)
          return acc
        }, {}),
      ),
    [emoji],
  )

  const submit = () => {
    const body = text.trim()
    if (!body || sending) return
    if (!editing && body.startsWith('/')) {
      void runCommand(body)
      return
    }
    setText('')
    onSubmit(body)
  }

  return (
    <>
      {paletteOpen ? (
        <div className="cmd-palette" role="listbox" aria-label="commands">
          {matches.map((command, i) => (
            <button
              key={command.id}
              type="button"
              role="option"
              aria-selected={i === index}
              className={i === index ? 'cmd-palette__row cmd-palette__row--active' : 'cmd-palette__row'}
              onMouseEnter={() => setActive(i)}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => complete(command)}
            >
              <Avatar
                name={command.bot.display_name || command.bot.username}
                avatarKey={command.bot.avatar_key}
                size={32}
              />
              <span className="cmd-palette__text">
                <span className="cmd-palette__title">
                  <span className="cmd-palette__name">/{command.name}</span>
                  <span className="cmd-palette__args">{commandSignature(command)}</span>
                </span>
                <span className="cmd-palette__desc">{command.description}</span>
              </span>
            </button>
          ))}
        </div>
      ) : null}

      {hint ? <div className="cmd-hint">{hint}</div> : null}

      {suggestions.length > 0 ? (
        <div className="autocomplete">
          {suggestions.map((item) => (
            <button
              key={item.id}
              type="button"
              className="autocomplete__item"
              onClick={() => setText(text.replace(/:([a-z0-9_]{1,32})$/i, `:${item.name}: `))}
            >
              <img src={api.mediaUrl(item.media_key ?? '')} alt={item.name} />
              :{item.name}:
            </button>
          ))}
        </div>
      ) : null}

      <div className="composer">
        <IconButton label="attach" onClick={() => fileRef.current?.click()}>
          <AttachFile />
        </IconButton>
        <IconButton label="emoji" onClick={() => setPicker(true)}>
          <EmojiEmotions />
        </IconButton>
        {isSpace ? (
          <IconButton label="poll" onClick={onPoll}>
            <Poll />
          </IconButton>
        ) : null}
        <textarea
          ref={inputRef}
          className="composer__input"
          placeholder={editing ? 'edit message' : 'message'}
          value={text}
          rows={1}
          onChange={(e) => {
            setText(e.target.value)
            setDismissed(false)
            setActive(0)
            if (e.target.value.startsWith('/')) return
            const now = Date.now()
            if (now - typingAt.current > 3000) {
              typingAt.current = now
              void api.typing(channelId)
            }
          }}
          onKeyDown={(e) => {
            if (paletteOpen && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
              e.preventDefault()
              const step = e.key === 'ArrowDown' ? 1 : matches.length - 1
              setActive((current) => (Math.min(current, matches.length - 1) + step) % matches.length)
              return
            }
            if (paletteOpen && e.key === 'Escape') {
              e.preventDefault()
              setDismissed(true)
              return
            }
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              if (paletteOpen) {
                const command = matches[index]
                if (command) complete(command)
                return
              }
              submit()
            }
          }}
        />
        <IconButton label="send" tone="accent" filled disabled={sending} onClick={submit}>
          <Send />
        </IconButton>
      </div>

      <input
        ref={fileRef}
        type="file"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) onAttach(file)
          e.target.value = ''
        }}
      />

      <Sheet open={picker} title="emoji" onClose={() => setPicker(false)}>
        <div className="emoji-picker">
          <div className="emoji-picker__group">everyday</div>
          <div className="emoji-picker__row">
            {UNICODE.map((value) => (
              <button
                key={value}
                type="button"
                className="emoji-picker__item emoji-picker__unicode"
                onClick={() => {
                  setText(`${text}${value}`)
                  setPicker(false)
                }}
              >
                {value}
              </button>
            ))}
          </div>
          {groups.map(([group, items]) => (
            <div key={group}>
              <div className="emoji-picker__group">{group}</div>
              <div className="emoji-picker__row">
                {items.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    className="emoji-picker__item"
                    onClick={() => {
                      if (item.kind === 'sticker') onSticker(item.id)
                      else setText(`${text}:${item.name}: `)
                      setPicker(false)
                    }}
                  >
                    <img src={api.mediaUrl(item.media_key ?? '')} alt={item.name} loading="lazy" />
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      </Sheet>
    </>
  )
})

const MessageList = memo(function MessageList({
  messages,
  meId,
  emoji,
  onReply,
  onEdit,
  onDelete,
  onRetry,
  onReact,
  onOpenProfile,
  onInvite,
  onVote,
}: {
  messages: ChatMessage[]
  meId: string | undefined
  emoji: SpaceEmojiDto[]
  onReply: (id: string) => void
  onEdit: (id: string, content: string) => void
  onDelete: (id: string) => void
  onRetry: (id: string) => void
  onReact: (id: string, emoji: string, active: boolean) => void
  onOpenProfile: (id: string) => void
  onInvite: (code: string) => void
  onVote: (id: string, optionId: string) => void
}) {
  return (
    <>
      {messages.map((message, index) => {
        const previous = messages[index - 1]
        const showDay = !previous || !sameDay(previous.created_at ?? 0, message.created_at ?? 0)
        const showAuthor = !previous || previous.author?.id !== message.author?.id || showDay
        return (
          <div key={message.id}>
            {showDay ? <div className="chat__day">{daySeparator(message.created_at ?? 0)}</div> : null}
            <MessageRow
              message={message}
              mine={message.author?.id === meId}
              showAuthor={showAuthor}
              emoji={emoji}
              replyTo={messages.find((m) => m.id === message.reply_to)}
              onReply={() => onReply(message.id)}
              onEdit={() => onEdit(message.id, message.content ?? '')}
              onDelete={() => onDelete(message.id)}
              onRetry={() => onRetry(message.id)}
              onReact={(value, active) => onReact(message.id, value, active)}
              onOpenProfile={onOpenProfile}
              onInvite={onInvite}
              onVote={(optionId) => onVote(message.id, optionId)}
            />
          </div>
        )
      })}
    </>
  )
})

export default function ChatScreen() {
  const { channelId = '' } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const toast = useToast()
  const me = useSession((s) => s.user)
  const dms = useSocial((s) => s.dms)
  const clearUnread = useSocial((s) => s.clearUnread)

  const messages = useChat((s) => s.channels[channelId]?.messages)
  const typing = useChat((s) => s.channels[channelId]?.typing)
  const load = useChat((s) => s.load)
  const loadMore = useChat((s) => s.loadMore)
  const send = useChat((s) => s.send)
  const sendSticker = useChat((s) => s.sendSticker)
  const edit = useChat((s) => s.edit)
  const remove = useChat((s) => s.remove)
  const react = useChat((s) => s.react)
  const retry = useChat((s) => s.retry)
  const markRead = useChat((s) => s.markRead)
  const emoji = useChat((s) => s.emoji)
  const loadEmoji = useChat((s) => s.loadEmoji)
  const subscribe = useChat((s) => s.subscribe)

  const listRef = useRef<HTMLDivElement>(null)
  const [replyTo, setReplyTo] = useState<string | null>(null)
  const [editing, setEditing] = useState<{ id: string; content: string } | null>(null)
  const [pollOpen, setPollOpen] = useState(false)
  const [question, setQuestion] = useState('')
  const [options, setOptions] = useState('')
  const [invite, setInvite] = useState<InvitePreviewResponse | null>(null)
  const [inviteCode, setInviteCode] = useState('')

  const isSpace = params.get('space') === 'true'
  const dm = dms.find((d) => d.channel_id === channelId)
  const title = params.get('name') ?? dm?.peer.display_name ?? dm?.peer.username ?? 'chat'
  const list = messages ?? []

  useEffect(() => {
    void load(channelId, true)
    void loadEmoji()
    return subscribe()
  }, [channelId, load, loadEmoji, subscribe])

  useEffect(() => {
    if (!list.length) return
    markRead(channelId)
    clearUnread(channelId)
    const node = listRef.current
    if (node) node.scrollTop = node.scrollHeight
  }, [list.length, channelId, markRead, clearUnread])

  const submit = useCallback(
    (body: string) => {
      if (editing) {
        void edit(channelId, editing.id, body)
        setEditing(null)
        return
      }
      void send(channelId, body, { replyTo })
      setReplyTo(null)
    },
    [channelId, edit, editing, replyTo, send],
  )

  const attach = useCallback(
    async (file: File) => {
      try {
        const attachment = await api.uploadResumable(file)
        await send(channelId, '', { attachment })
      } catch (err) {
        toast.error(err instanceof Error ? err.message : 'upload failed')
      }
    },
    [channelId, send, toast],
  )

  const openInvite = useCallback(
    async (code: string) => {
      setInviteCode(code)
      try {
        setInvite(await api.invitePreview(code))
      } catch {
        toast.error('invite is not valid')
      }
    },
    [toast],
  )

  const onEdit = useCallback((id: string, content: string) => setEditing({ id, content }), [])
  const onDelete = useCallback((id: string) => void remove(channelId, id), [channelId, remove])
  const onRetry = useCallback((id: string) => void retry(channelId, id), [channelId, retry])
  const onReact = useCallback(
    (id: string, value: string, active: boolean) => void react(channelId, id, value, active),
    [channelId, react],
  )
  const onOpenProfile = useCallback((id: string) => navigate(`/profile/${id}`), [navigate])
  const onVote = useCallback(
    (id: string, optionId: string) => {
      void api.votePoll(id, optionId).then(() => load(channelId, true))
    },
    [channelId, load],
  )

  const createPoll = async () => {
    const opts = options
      .split('\n')
      .map((o) => o.trim())
      .filter(Boolean)
    if (!question.trim() || opts.length < 2) {
      toast.error('need a question and two options')
      return
    }
    try {
      await api.sendPoll(channelId, question.trim(), opts, false, crypto.randomUUID())
      setPollOpen(false)
      setQuestion('')
      setOptions('')
      await load(channelId, true)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not make poll')
    }
  }

  const typingNames = Object.keys(typing ?? {}).filter(
    (name) => Date.now() - (typing?.[name] ?? 0) < 6000 && name !== me?.username,
  )

  return (
    <Screen className="chat">
      <TopBar
        title={title}
        subtitle={typingNames.length ? `${typingNames.join(', ')} typing…` : undefined}
        onBack={() => navigate(-1)}
        leading={<Avatar name={title} avatarKey={dm?.peer.avatar_key} size="sm" />}
        actions={
          <>
            {isSpace ? (
              <IconButton label="threads" onClick={() => navigate(`/threads/${channelId}`)}>
                <Forum />
              </IconButton>
            ) : (
              <IconButton label="call" onClick={() => navigate(`/call/${channelId}`)}>
                <Call />
              </IconButton>
            )}
            <IconButton label="search" onClick={() => navigate(`/search?channel=${channelId}`)}>
              <Search />
            </IconButton>
            <IconButton label="pins" onClick={() => void api.pins(channelId)}>
              <PushPin />
            </IconButton>
          </>
        }
      />

      <div
        className="chat__list"
        ref={listRef}
        onScroll={(e) => {
          if (e.currentTarget.scrollTop < 60) void loadMore(channelId)
        }}
      >
        <MessageList
          messages={list}
          meId={me?.id}
          emoji={emoji}
          onReply={setReplyTo}
          onEdit={onEdit}
          onDelete={onDelete}
          onRetry={onRetry}
          onReact={onReact}
          onOpenProfile={onOpenProfile}
          onInvite={(code) => void openInvite(code)}
          onVote={onVote}
        />
      </div>

      {replyTo ? (
        <div className="composer__reply">
          <span>replying to {list.find((m) => m.id === replyTo)?.content?.slice(0, 48)}</span>
          <Button variant="text" onClick={() => setReplyTo(null)}>
            cancel
          </Button>
        </div>
      ) : null}

      <Composer
        channelId={channelId}
        emoji={emoji}
        editing={editing}
        isSpace={isSpace}
        onSubmit={submit}
        onAttach={(file) => void attach(file)}
        onSticker={(id) => void sendSticker(channelId, id)}
        onPoll={() => setPollOpen(true)}
      />

      <Sheet open={pollOpen} title="new poll" onClose={() => setPollOpen(false)}>
        <div className="nest__form">
          <TextField label="question" value={question} onChange={setQuestion} />
          <TextField label="options (one per line)" value={options} onChange={setOptions} multiline />
          <Button size="cta" fullWidth onClick={() => void createPoll()}>
            post poll
          </Button>
        </div>
      </Sheet>

      <Dialog
        open={!!invite}
        title="nest invite"
        onClose={() => setInvite(null)}
        actions={
          <>
            <Button variant="text" onClick={() => setInvite(null)}>
              not now
            </Button>
            <Button
              onClick={async () => {
                try {
                  const spaceId = await api.joinSpace(inviteCode)
                  setInvite(null)
                  navigate(`/nest/${spaceId}`)
                } catch {
                  toast.error('could not join')
                }
              }}
            >
              join
            </Button>
          </>
        }
      >
        <div className="msg__invite">
          <Avatar name={invite?.space?.name ?? 'nest'} avatarKey={invite?.space?.icon_key} size="lg" />
          <div>
            <strong>{invite?.space?.name}</strong>
            <div>{invite?.space?.member_count ?? 0} members</div>
          </div>
        </div>
      </Dialog>
    </Screen>
  )
}
