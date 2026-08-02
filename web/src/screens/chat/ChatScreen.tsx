import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { ChannelCommandDto, InvitePreviewResponse, MessageDto, SpaceEmojiDto } from '@/api/dto'
import {
  AttachFile,
  Campaign,
  Close,
  Call,
  EmojiEmotions,
  Forum,
  Mic,
  MoreVert,
  Poll,
  PushPin,
  Search,
  Send,
  Tag,
  Timer,
} from '@/components/icons'
import { ConversationDetails } from '@/components/chat/ConversationDetails'
import { ImageEditor } from '@/components/chat/ImageEditor'
import { MessageRow } from '@/components/chat/MessageRow'
import { useAttachmentUrl } from '@/components/chat/useAttachmentUrl'
import { NestIcon } from '@/components/Logo'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { TextField } from '@/components/ui/TextField'
import { Dialog, Sheet } from '@/components/ui/Overlay'
import { Screen, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { daySeparator, relativeTime, sameDay } from '@/lib/format'
import { emojiQueryAt } from '@/lib/markdown'
import { protectAttachment } from '@/lib/e2ee/manager'
import type { ChatMessage } from '@/store/chat'
import { useChat } from '@/store/chat'
import { usePrefs } from '@/store/prefs'
import { useSession } from '@/store/session'
import { useSocial } from '@/store/social'
import '@/components/chat/chat.css'

const UNICODE = ['😀', '😂', '🥲', '😍', '🤔', '👍', '🙏', '🔥', '🎉', '💜', '😭', '😎', '🤯', '🕊️', '✨', '💀']

type CommandOption = NonNullable<ChannelCommandDto['options']>[number]
type CommandValues = Record<string, string | number | boolean>
type CommandToken = { key: string | null; value: string }
type SendOptions = { ttl?: number | null; sendAt?: number | null }

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
  onSubmit: (text: string, options: SendOptions) => void
  onAttach: (file: File, options: SendOptions) => void
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
  const [showOptions, setShowOptions] = useState(false)
  const [ttl, setTtl] = useState<number | null>(null)
  const [sendAt, setSendAt] = useState('')
  const [recording, setRecording] = useState(false)
  const typingAt = useRef(0)
  const requested = useRef<Record<string, boolean>>({})
  const fileRef = useRef<HTMLInputElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const recorderRef = useRef<MediaRecorder | null>(null)
  const recordingStreamRef = useRef<MediaStream | null>(null)
  const chunksRef = useRef<Blob[]>([])
  const toast = useToast()
  const invisible = usePrefs((s) => s.invisible)

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
      .catch(() => {
        requested.current[channelId] = false
        setCommands((prev) => ({ ...prev, [channelId]: [] }))
      })
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
    const candidates = available.filter((command) => command.name.toLowerCase().startsWith(needle))
    const exact = candidates.filter((command) => command.name.toLowerCase() === needle)
    return (exact.length ? exact : candidates).slice(0, 5)
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
    const when = sendAt ? new Date(sendAt).getTime() : null
    if (when && when <= Date.now() + 15_000) {
      toast.error('pick a time at least 15 seconds from now')
      return
    }
    setText('')
    onSubmit(body, { ttl, sendAt: when })
    setSendAt('')
  }

  const stopRecording = () => {
    recorderRef.current?.stop()
    recorderRef.current = null
    setRecording(false)
  }

  const toggleRecording = async () => {
    if (recording) {
      stopRecording()
      return
    }
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      toast.error('voice notes are not supported in this browser')
      return
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const recorder = new MediaRecorder(stream)
      recordingStreamRef.current = stream
      chunksRef.current = []
      recorder.ondataavailable = (event) => {
        if (event.data.size) chunksRef.current.push(event.data)
      }
      recorder.onstop = () => {
        const type = recorder.mimeType || 'audio/webm'
        const blob = new Blob(chunksRef.current, { type })
        recordingStreamRef.current?.getTracks().forEach((track) => track.stop())
        recordingStreamRef.current = null
        chunksRef.current = []
        if (blob.size) {
          onAttach(new File([blob], `voice-${Date.now()}.webm`, { type }), { ttl, sendAt: null })
        }
      }
      recorder.start(250)
      recorderRef.current = recorder
      setRecording(true)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'microphone permission was denied')
    }
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

      {showOptions ? (
        <div className="composer-options">
          <span>disappears</span>
          {[
            { label: 'off', value: null },
            { label: '1h', value: 3600 },
            { label: '1d', value: 86400 },
            { label: '7d', value: 604800 },
          ].map((option) => (
            <button
              key={option.label}
              type="button"
              className={ttl === option.value ? 'composer-options__chip composer-options__chip--on' : 'composer-options__chip'}
              onClick={() => setTtl(option.value)}
            >
              {option.label}
            </button>
          ))}
          <label>
            send later
            <input
              type="datetime-local"
              value={sendAt}
              min={new Date(Date.now() + 60_000).toISOString().slice(0, 16)}
              onChange={(event) => setSendAt(event.target.value)}
            />
          </label>
        </div>
      ) : null}

      <div className={recording ? 'composer composer--recording' : 'composer'}>
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
        <IconButton label="message options" filled={showOptions || !!ttl || !!sendAt} onClick={() => setShowOptions((open) => !open)}>
          <Timer />
        </IconButton>
        <IconButton label={recording ? 'stop voice note' : 'record voice note'} filled={recording} onClick={() => void toggleRecording()}>
          <Mic />
        </IconButton>
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
              if (!invisible) void api.typing(channelId)
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
                if (command) {
                  const exact = command.name.toLowerCase() === head.toLowerCase()
                  const needsOptions = orderedOptions(command).some((option) => option.required)
                  if (exact && !needsOptions) submit()
                  else complete(command)
                }
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
          if (file) {
            if (sendAt) toast.error('scheduled messages cannot include attachments')
            else onAttach(file, { ttl, sendAt: null })
          }
          e.target.value = ''
        }}
        accept="image/*,video/*,audio/*,.pdf,.zip,.txt"
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
  onPin,
  onSuperPin,
  onThread,
  onOpenMedia,
  canReport,
  read,
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
  onPin: (message: ChatMessage) => void
  onSuperPin: (message: ChatMessage) => void
  onThread: (message: ChatMessage) => void
  onOpenMedia: (message: ChatMessage) => void
  canReport: boolean
  read: Record<string, number>
}) {
  return (
    <>
      {messages.map((message, index) => {
        const previous = messages[index - 1]
        const showDay = !previous || !sameDay(previous.created_at ?? 0, message.created_at ?? 0)
        const showAuthor = !previous || previous.author?.id !== message.author?.id || showDay
        return (
          <div
            key={message.id}
            id={`message-${message.id}`}
            className="chat__message-anchor"
          >
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
              onPin={() => onPin(message)}
              onSuperPin={() => onSuperPin(message)}
              onThread={() => onThread(message)}
              onOpenMedia={() => onOpenMedia(message)}
              canReport={canReport}
              seenCount={
                message.seq
                  ? Object.entries(read).filter(([userId, seq]) => userId !== meId && seq >= (message.seq ?? 0)).length
                  : 0
              }
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
  const spaces = useSocial((s) => s.spaces)
  const loadSpaces = useSocial((s) => s.loadSpaces)
  const clearUnread = useSocial((s) => s.clearUnread)

  const messages = useChat((s) => s.channels[channelId]?.messages)
  const typing = useChat((s) => s.channels[channelId]?.typing)
  const read = useChat((s) => s.channels[channelId]?.read)
  const error = useChat((s) => s.channels[channelId]?.error)
  const pins = useChat((s) => s.channels[channelId]?.pins)
  const superPin = useChat((s) => s.channels[channelId]?.superPin)
  const load = useChat((s) => s.load)
  const loadMore = useChat((s) => s.loadMore)
  const loadDetails = useChat((s) => s.loadDetails)
  const send = useChat((s) => s.send)
  const sendSticker = useChat((s) => s.sendSticker)
  const edit = useChat((s) => s.edit)
  const remove = useChat((s) => s.remove)
  const react = useChat((s) => s.react)
  const retry = useChat((s) => s.retry)
  const togglePin = useChat((s) => s.togglePin)
  const makeSuperPin = useChat((s) => s.setSuperPin)
  const removeSuperPin = useChat((s) => s.removeSuperPin)
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
  const [detailsOpen, setDetailsOpen] = useState(false)
  const [wideDetails, setWideDetails] = useState(() => window.matchMedia('(min-width: 1200px)').matches)
  const [mediaMessage, setMediaMessage] = useState<MessageDto | null>(null)
  const [pendingImage, setPendingImage] = useState<{ file: File; options: SendOptions } | null>(null)
  const mediaAsset = useAttachmentUrl(mediaMessage)

  useEffect(() => {
    const media = window.matchMedia('(min-width: 1200px)')
    const update = () => setWideDetails(media.matches)
    update()
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])

  const isSpace = params.get('space') === 'true'
  const dm = dms.find((d) => d.channel_id === channelId)
  const space = spaces.find((item) => item.channels?.some((channel) => channel.id === channelId))
  const spaceChannels = space?.channels ?? []
  const currentSpaceChannel = spaceChannels.find((channel) => channel.id === channelId)
  const unreadStart = currentSpaceChannel?.unread && currentSpaceChannel.unread > 0
    ? currentSpaceChannel.last_read_seq ?? 0
    : dm?.unread && dm.unread > 0
      ? dm.last_read_seq ?? 0
      : undefined
  const title =
    params.get('name') ??
    currentSpaceChannel?.name ??
    dm?.peer.display_name ??
    dm?.peer.username ??
    'chat'
  const list = messages ?? []

  useEffect(() => {
    if (isSpace) void loadSpaces()
  }, [isSpace, loadSpaces])

  useEffect(() => {
    void load(channelId, true, unreadStart)
    void loadDetails(channelId)
    void loadEmoji()
    return subscribe()
  }, [channelId, load, loadDetails, loadEmoji, subscribe, unreadStart])

  useEffect(() => {
    if (!list.length) return
    markRead(channelId)
    clearUnread(channelId)
    const targetMessage = params.get('message')
    const scrollToEnd = () => {
      const node = listRef.current
      if (!node) return
      const unreadMessage = unreadStart === undefined
        ? null
        : list.find((message) => (message.seq ?? 0) > unreadStart)
      const target = targetMessage
        ? document.getElementById(`message-${targetMessage}`)
        : unreadMessage
          ? document.getElementById(`message-${unreadMessage.id}`)
          : null
      if (target) {
        target.scrollIntoView({ block: 'center' })
        target.classList.add('chat__message-anchor--highlight')
        window.setTimeout(() => target.classList.remove('chat__message-anchor--highlight'), 2200)
      } else {
        node.scrollTop = node.scrollHeight
      }
    }
    scrollToEnd()
    const frame = requestAnimationFrame(scrollToEnd)
    const timeout = window.setTimeout(scrollToEnd, 80)
    return () => {
      cancelAnimationFrame(frame)
      window.clearTimeout(timeout)
    }
  }, [list.length, channelId, markRead, clearUnread, params, unreadStart])

  const jumpToUnread = useCallback(() => {
    if (unreadStart === undefined) return
    const message = list.find((item) => (item.seq ?? 0) > unreadStart)
    if (!message) return
    document.getElementById(`message-${message.id}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [list, unreadStart])

  const submit = useCallback(
    (body: string, options: SendOptions) => {
      if (editing) {
        void edit(channelId, editing.id, body)
        setEditing(null)
        return
      }
      void send(channelId, body, { replyTo, ...options })
      setReplyTo(null)
    },
    [channelId, edit, editing, replyTo, send],
  )

  const attach = useCallback(
    async (file: File, options: SendOptions) => {
      try {
        const protectedFile = dm && usePrefs.getState().e2ee ? await protectAttachment(file) : null
        const attachment = await api.uploadResumable(protectedFile?.file ?? file)
        await send(channelId, '', {
          attachment,
          attachmentSecret: protectedFile?.secret,
          ttl: options.ttl,
          sendAt: options.sendAt,
        })
      } catch (err) {
        toast.error(err instanceof Error ? err.message : 'upload failed')
      }
    },
    [channelId, dm, send, toast],
  )

  const prepareAttachment = useCallback(
    (file: File, options: SendOptions) => {
      if (file.type.startsWith('image/')) setPendingImage({ file, options })
      else void attach(file, options)
    },
    [attach],
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

  const jumpToMessage = useCallback((id: string) => {
    const target = document.getElementById(`message-${id}`)
    if (!target) {
      toast.error('load older messages to jump to this one')
      return
    }
    target.scrollIntoView({ behavior: 'smooth', block: 'center' })
    target.classList.add('chat__message-anchor--highlight')
    window.setTimeout(() => target.classList.remove('chat__message-anchor--highlight'), 2200)
    setDetailsOpen(false)
  }, [toast])

  const onPin = useCallback(
    async (message: ChatMessage) => {
      try {
        await togglePin(channelId, message.id, !!message.pinned || (pins ?? []).some((item) => item.id === message.id))
        toast.show(message.pinned ? 'message unpinned' : 'message pinned')
      } catch (err) {
        toast.error(err instanceof Error ? err.message : 'could not change pin')
      }
    },
    [channelId, pins, toast, togglePin],
  )

  const onSuperPin = useCallback(
    async (message: ChatMessage) => {
      try {
        await makeSuperPin(channelId, message.id)
        toast.show('super pin updated')
      } catch (err) {
        toast.error(err instanceof Error ? err.message : 'could not set super pin')
      }
    },
    [channelId, makeSuperPin, toast],
  )

  const onThread = useCallback(
    async (message: ChatMessage) => {
      try {
        const thread = message.thread_id
          ? { id: message.thread_id }
          : await api.createThread(channelId, message.id, message.content.slice(0, 60) || undefined, 'branch', 7 * 86400)
        navigate(`/thread/${thread.id}`)
      } catch (err) {
        toast.error(err instanceof Error ? err.message : 'could not open branch')
      }
    },
    [channelId, navigate, toast],
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

  const openSpaceChannel = (id: string, kind: string | undefined, name: string) => {
    const encodedName = encodeURIComponent(name)
    if (kind === 'forum') navigate(`/forum/${id}?name=${encodedName}&spaceId=${encodeURIComponent(space?.id ?? '')}`)
    else if (kind === 'voice') navigate(`/call/${id}?name=${encodedName}&spaceId=${encodeURIComponent(space?.id ?? '')}`)
    else navigate(`/chat/${id}?space=true&name=${encodedName}`)
  }

  return (
    <Screen className={detailsOpen ? 'chat chat--workspace chat--details-open' : 'chat chat--workspace'}>
      <aside className={isSpace ? 'chat__sidebar chat__sidebar--space' : 'chat__sidebar'}>
        <div className="chat__sidebar-head">
          {isSpace ? (
            <span className="chat__sidebar-heading">
              <NestIcon size={18} />
              <span>{space?.name ?? 'nest'}</span>
            </span>
          ) : (
            <span>chats</span>
          )}
          <button
            type="button"
            onClick={() => navigate(isSpace && space ? `/nest/${space.id}` : '/')}
          >
            {isSpace ? 'view nest' : 'view all'}
          </button>
        </div>
        <div className="chat__sidebar-list">
          {isSpace
            ? spaceChannels.map((channel) => {
                const channelName = channel.name ?? 'channel'
                const kind = channel.kind
                return (
                  <button
                    key={channel.id}
                    type="button"
                    className={
                      channel.id === channelId
                        ? 'chat__sidebar-row chat__sidebar-row--on'
                        : 'chat__sidebar-row'
                    }
                    onClick={() => openSpaceChannel(channel.id, kind, channelName)}
                  >
                    <span className="chat__sidebar-channel-icon">
                      {kind === 'forum' ? <Forum size={18} /> : kind === 'voice' ? <Campaign size={18} /> : <Tag size={18} />}
                    </span>
                    <span className="chat__sidebar-copy">
                      <strong>#{channelName}</strong>
                      <small>{channel.topic || (kind === 'forum' ? 'forum' : kind === 'voice' ? 'voice' : 'text channel')}</small>
                    </span>
                    <span className="chat__sidebar-meta">
                      {channel.unread && channel.unread > 0 ? <b>{channel.unread > 99 ? '99+' : channel.unread}</b> : null}
                    </span>
                  </button>
                )
              })
            : dms.map((item) => {
                const itemName = item.peer.display_name || item.peer.username
                return (
              <button
                key={item.channel_id}
                type="button"
                className={item.channel_id === channelId ? 'chat__sidebar-row chat__sidebar-row--on' : 'chat__sidebar-row'}
                onClick={() => navigate(`/chat/${item.channel_id}?name=${encodeURIComponent(itemName)}`)}
              >
                <Avatar name={itemName} avatarKey={item.peer.avatar_key} size="sm" />
                <span className="chat__sidebar-copy">
                  <strong>{itemName}</strong>
                  <small>{item.last_message?.content || 'start a conversation'}</small>
                </span>
                <span className="chat__sidebar-meta">
                  <small>{relativeTime(item.last_message?.created_at ?? 0)}</small>
                  {item.unread > 0 ? <b>{item.unread > 99 ? '99+' : item.unread}</b> : null}
                </span>
              </button>
                )
              })}
          {isSpace && spaceChannels.length === 0 ? (
            <div className="chat__sidebar-empty">loading channels...</div>
          ) : null}
        </div>
      </aside>

      <div className="chat__conversation">
      <TopBar
        title={title}
        subtitle={typingNames.length ? `${typingNames.join(', ')} typing…` : undefined}
        onBack={() => navigate(-1)}
        leading={
          <Avatar
            name={isSpace ? space?.name ?? 'nest' : title}
            avatarKey={isSpace ? space?.icon_square_key || space?.icon_key : dm?.peer.avatar_key}
            size="sm"
          />
        }
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
            <IconButton
              label="search"
              onClick={() =>
                navigate(
                  `/search?channel=${channelId}${space?.id ? `&space=${encodeURIComponent(space.id)}` : ''}`,
                )
              }
            >
              <Search />
            </IconButton>
            <IconButton label="conversation details" filled={detailsOpen} onClick={() => setDetailsOpen((open) => !open)}>
              <MoreVert />
            </IconButton>
          </>
        }
      />

      {superPin ? (
        <div
          className="chat__super-pin"
          role="button"
          tabIndex={0}
          onClick={() => jumpToMessage(superPin.message.id)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') jumpToMessage(superPin.message.id)
          }}
        >
          <PushPin size={17} />
          <span>
            <strong>super pin</strong>
            {superPin.message.content || superPin.message.attachment?.name || 'pinned message'}
          </span>
          <button
            type="button"
            aria-label="dismiss super pin"
            onClick={(event) => {
              event.stopPropagation()
              void api.dismissSuperPin(channelId).then(() => loadDetails(channelId))
            }}
          >
            <Close size={17} />
          </button>
        </div>
      ) : null}

      {error ? (
        <div className="chat__error" role="alert">
          <span>{error}</span>
          <Button variant="text" onClick={() => void load(channelId, true)}>retry</Button>
        </div>
      ) : null}

      <div
        className="chat__list"
        ref={listRef}
        onScroll={(e) => {
          if (e.currentTarget.scrollTop < 60) void loadMore(channelId)
        }}
          >
          {unreadStart !== undefined && list.some((message) => (message.seq ?? 0) > unreadStart) ? (
            <button type="button" className="chat__unread-jump" onClick={jumpToUnread}>
              jump to unread
            </button>
          ) : null}
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
          onPin={(message) => void onPin(message)}
          onSuperPin={(message) => void onSuperPin(message)}
          onThread={(message) => void onThread(message)}
          onOpenMedia={setMediaMessage}
          canReport={isSpace}
          read={read ?? {}}
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
        onAttach={prepareAttachment}
        onSticker={(id) => void sendSticker(channelId, id)}
        onPoll={() => setPollOpen(true)}
      />
      </div>

      {detailsOpen && wideDetails ? (
        <aside className="chat__details">
          <ConversationDetails
            channelId={channelId}
            title={title}
            avatarKey={isSpace ? space?.icon_square_key || space?.icon_key : dm?.peer.avatar_key}
            spaceId={space?.id}
            messages={list}
            pins={pins ?? []}
            superPin={superPin ?? null}
            onClose={() => setDetailsOpen(false)}
            onSearch={() =>
              navigate(`/search?channel=${channelId}${space?.id ? `&space=${encodeURIComponent(space.id)}` : ''}`)
            }
            onJump={jumpToMessage}
            onMedia={setMediaMessage}
            onUnpin={(id) => {
              const message = list.find((item) => item.id === id)
              if (message) void onPin(message)
              else void api.unpin(id).then(() => loadDetails(channelId))
            }}
            onRemoveSuperPin={() => void removeSuperPin(channelId)}
          />
        </aside>
      ) : null}

      {!wideDetails ? <div className="chat__details-sheet">
        <Sheet open={detailsOpen} title={undefined} onClose={() => setDetailsOpen(false)}>
          <ConversationDetails
            channelId={channelId}
            title={title}
            avatarKey={isSpace ? space?.icon_square_key || space?.icon_key : dm?.peer.avatar_key}
            spaceId={space?.id}
            messages={list}
            pins={pins ?? []}
            superPin={superPin ?? null}
            onClose={() => setDetailsOpen(false)}
            onSearch={() =>
              navigate(`/search?channel=${channelId}${space?.id ? `&space=${encodeURIComponent(space.id)}` : ''}`)
            }
            onJump={jumpToMessage}
            onMedia={setMediaMessage}
            onUnpin={(id) => void api.unpin(id).then(() => loadDetails(channelId))}
            onRemoveSuperPin={() => void removeSuperPin(channelId)}
          />
        </Sheet>
      </div> : null}

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

      {mediaMessage?.attachment ? (
        <div
          className="media-viewer"
          role="dialog"
          aria-modal="true"
          aria-label={mediaMessage.attachment.name || 'media viewer'}
          onClick={() => setMediaMessage(null)}
        >
          <button type="button" className="media-viewer__close" aria-label="close media" onClick={() => setMediaMessage(null)}>
            <Close />
          </button>
          {mediaAsset.error ? (
            <div className="media-viewer__error">could not decrypt this attachment</div>
          ) : mediaAsset.loading ? (
            <div className="media-viewer__loading">decrypting attachment…</div>
          ) : mediaMessage.attachment.type?.startsWith('video/') ? (
            <video
              src={mediaAsset.url ?? undefined}
              controls
              autoPlay
              onClick={(event) => event.stopPropagation()}
            />
          ) : (
            <img
              src={mediaAsset.url ?? undefined}
              alt={mediaMessage.attachment.name || 'shared image'}
              onClick={(event) => event.stopPropagation()}
            />
          )}
          <a
            className="media-viewer__download"
            href={mediaAsset.url ?? undefined}
            download={mediaAsset.encrypted ? mediaMessage.attachment.name ?? 'attachment' : undefined}
            target={mediaAsset.encrypted ? undefined : '_blank'}
            rel={mediaAsset.encrypted ? undefined : 'noreferrer noopener'}
            onClick={(event) => event.stopPropagation()}
          >
            open original
          </a>
        </div>
      ) : null}

      {pendingImage ? (
        <ImageEditor
          file={pendingImage.file}
          onCancel={() => setPendingImage(null)}
          onSave={(file) => {
            const options = pendingImage.options
            setPendingImage(null)
            void attach(file, options)
          }}
        />
      ) : null}
    </Screen>
  )
}
