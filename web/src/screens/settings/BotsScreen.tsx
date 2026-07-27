import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import type {
  BotCommandDto,
  BotCommandInput,
  BotCommandOptionChoiceDto,
  BotCommandOptionDto,
  BotCommandOptionType,
  BotDto,
  BotSpaceDto,
} from '@/api/dto'
import { Add, Bolt, ChevronRight, ContentCopy, Delete, Key } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { Switch } from '@/components/ui/Switch'
import { TextField } from '@/components/ui/TextField'
import { ConfirmDialog, Dialog, Sheet } from '@/components/ui/Overlay'
import {
  Chip,
  ChipRow,
  EmptyState,
  ListRow,
  Screen,
  ScreenBody,
  SettingsGroup,
  SettingsRow,
  TopBar,
} from '@/components/ui/Layout'
import { LoadingState } from '@/components/ui/Spinner'
import { useToast } from '@/components/ui/Toast'
import { useSession } from '@/store/session'
import { useSocial } from '@/store/social'
import './Settings.css'
import './Bots.css'

const NAME_RE = /^[a-z0-9_-]{1,32}$/

const OPTION_TYPES: BotCommandOptionType[] = [
  'string',
  'integer',
  'number',
  'boolean',
  'user',
  'channel',
]

type CommandDraft = {
  id: string | null
  name: string
  description: string
  space_id: string | null
  dm_enabled: boolean
  options: BotCommandOptionDto[]
}

function reason(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback
}

function botLabel(bot: BotDto): string {
  return bot.display_name || bot.name
}

function numeric(type: BotCommandOptionType): boolean {
  return type === 'integer' || type === 'number'
}

function normalizeOption(option: BotCommandOptionDto): BotCommandOptionDto {
  const choices = (option.choices ?? [])
    .filter((choice) => choice.name.trim() !== '')
    .map<BotCommandOptionChoiceDto>((choice) => {
      const raw = String(choice.value).trim()
      if (!numeric(option.type)) return { name: choice.name.trim(), value: raw }
      const parsed = Number(raw)
      return { name: choice.name.trim(), value: Number.isFinite(parsed) ? parsed : 0 }
    })
  const next: BotCommandOptionDto = {
    name: option.name.trim().toLowerCase(),
    description: option.description.trim(),
    type: option.type,
    required: option.required === true,
  }
  if (choices.length > 0) next.choices = choices
  if (typeof option.min === 'number') next.min = option.min
  if (typeof option.max === 'number') next.max = option.max
  return next
}

function toInput(command: BotCommandDto): BotCommandInput {
  return {
    name: command.name,
    description: command.description,
    options: (command.options ?? []).map(normalizeOption),
    space_id: command.space_id ?? null,
    dm_enabled: command.dm_enabled !== false,
  }
}

function TokenDialog({ token, onClose }: { token: string | null; onClose: () => void }) {
  const toast = useToast()
  const copy = async () => {
    if (!token) return
    try {
      await navigator.clipboard.writeText(token)
      toast.show('token copied')
    } catch {
      toast.error('could not copy — select it by hand')
    }
  }
  return (
    <Dialog
      open={!!token}
      title="bot token"
      onClose={onClose}
      actions={
        <>
          <Button variant="text" leading={<ContentCopy size={18} />} onClick={() => void copy()}>
            copy
          </Button>
          <Button onClick={onClose}>done</Button>
        </>
      }
    >
      <div className="bots__warning">
        this token is shown once. save it now — if you lose it you have to rotate it.
      </div>
      <code className="bots__token">{token}</code>
    </Dialog>
  )
}

function OptionEditor({
  option,
  index,
  onChange,
  onRemove,
}: {
  option: BotCommandOptionDto
  index: number
  onChange: (next: BotCommandOptionDto) => void
  onRemove: () => void
}) {
  const choices = option.choices ?? []
  const setChoice = (at: number, next: BotCommandOptionChoiceDto) =>
    onChange({ ...option, choices: choices.map((choice, i) => (i === at ? next : choice)) })

  return (
    <div className="bots__option">
      <div className="bots__option-head">
        <span className="bots__option-index">option {index + 1}</span>
        <IconButton label="remove option" tone="danger" onClick={onRemove}>
          <Delete />
        </IconButton>
      </div>
      <TextField
        label="name"
        value={option.name}
        onChange={(value) => onChange({ ...option, name: value })}
        maxLength={32}
      />
      <TextField
        label="description"
        value={option.description}
        onChange={(value) => onChange({ ...option, description: value })}
        maxLength={100}
      />
      <ChipRow>
        {OPTION_TYPES.map((type) => (
          <Chip
            key={type}
            label={type}
            active={option.type === type}
            onClick={() => onChange({ ...option, type })}
          />
        ))}
      </ChipRow>
      <div className="bots__toggle">
        <span className="bots__toggle-text">
          <span>required</span>
          <span className="bots__hint">the caller has to fill this in</span>
        </span>
        <Switch
          checked={option.required === true}
          onChange={(checked) => onChange({ ...option, required: checked })}
          label="required"
        />
      </div>
      {choices.map((choice, at) => (
        <div className="bots__choice" key={`choice-${at}`}>
          <TextField
            label="choice"
            value={choice.name}
            onChange={(value) => setChoice(at, { ...choice, value: choice.value, name: value })}
            maxLength={32}
          />
          <TextField
            label="value"
            value={String(choice.value)}
            onChange={(value) => setChoice(at, { ...choice, name: choice.name, value })}
            maxLength={64}
          />
          <IconButton
            label="remove choice"
            tone="danger"
            onClick={() => onChange({ ...option, choices: choices.filter((_, i) => i !== at) })}
          >
            <Delete />
          </IconButton>
        </div>
      ))}
      <Button
        variant="text"
        leading={<Add size={18} />}
        onClick={() => onChange({ ...option, choices: choices.concat({ name: '', value: '' }) })}
      >
        add a choice
      </Button>
    </div>
  )
}

function CommandSheet({
  draft,
  scopes,
  onClose,
  onSave,
}: {
  draft: CommandDraft
  scopes: BotSpaceDto[]
  onClose: () => void
  onSave: (next: CommandDraft) => Promise<void>
}) {
  const toast = useToast()
  const [name, setName] = useState(draft.name)
  const [description, setDescription] = useState(draft.description)
  const [spaceId, setSpaceId] = useState<string | null>(draft.space_id)
  const [dmEnabled, setDmEnabled] = useState(draft.dm_enabled)
  const [options, setOptions] = useState<BotCommandOptionDto[]>(draft.options)
  const [saving, setSaving] = useState(false)

  const submit = async () => {
    const cleaned = name.trim().toLowerCase()
    if (!NAME_RE.test(cleaned)) {
      toast.error('command names use a-z, 0-9, _ and -')
      return
    }
    if (!description.trim()) {
      toast.error('give the command a description')
      return
    }
    if (options.length > 25) {
      toast.error('a command takes at most 25 options')
      return
    }
    for (const option of options) {
      if (!NAME_RE.test(option.name.trim().toLowerCase())) {
        toast.error(`option "${option.name || 'unnamed'}" needs a name like a-z, 0-9, _ and -`)
        return
      }
      if (!option.description.trim()) {
        toast.error(`option "${option.name}" needs a description`)
        return
      }
    }
    setSaving(true)
    try {
      await onSave({
        id: draft.id,
        name: cleaned,
        description: description.trim(),
        space_id: spaceId,
        dm_enabled: dmEnabled,
        options: options.map(normalizeOption),
      })
      onClose()
    } catch (err) {
      toast.error(reason(err, 'could not save command'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Sheet open title={draft.id ? 'edit command' : 'new command'} onClose={onClose}>
      <div className="settings__form">
        <TextField
          label="name"
          value={name}
          onChange={setName}
          maxLength={32}
          helper="lowercase, no spaces — people type /name"
        />
        <TextField label="description" value={description} onChange={setDescription} maxLength={100} />
      </div>

      <div className="ui-group__label">scope</div>
      <ChipRow>
        <Chip label="global" active={spaceId === null} onClick={() => setSpaceId(null)} />
        {scopes.map((scope) => (
          <Chip
            key={scope.id}
            label={scope.name || 'nest'}
            active={spaceId === scope.id}
            onClick={() => setSpaceId(scope.id)}
          />
        ))}
      </ChipRow>

      <div className="settings__form">
        <div className="bots__toggle">
          <span className="bots__toggle-text">
            <span>usable in dms</span>
            <span className="bots__hint">only applies to global commands</span>
          </span>
          <Switch checked={dmEnabled} onChange={setDmEnabled} label="usable in dms" />
        </div>
      </div>

      <div className="bots__section">
        <span className="bots__section-label">options</span>
        <IconButton
          label="add option"
          tone="accent"
          onClick={() =>
            setOptions(
              options.concat({ name: '', description: '', type: 'string', required: false }),
            )
          }
        >
          <Add />
        </IconButton>
      </div>
      {options.length === 0 ? (
        <div className="bots__empty-note">no options — the command runs on its own</div>
      ) : null}
      <div className="settings__form">
        {options.map((option, index) => (
          <OptionEditor
            key={`option-${index}`}
            option={option}
            index={index}
            onChange={(next) => setOptions(options.map((o, i) => (i === index ? next : o)))}
            onRemove={() => setOptions(options.filter((_, i) => i !== index))}
          />
        ))}
        <Button size="cta" fullWidth loading={saving} onClick={() => void submit()}>
          {draft.id ? 'save command' : 'add command'}
        </Button>
      </div>
    </Sheet>
  )
}

function BotDetail({
  bot,
  onBack,
  onChanged,
  onDeleted,
}: {
  bot: BotDto
  onBack: () => void
  onChanged: () => Promise<void>
  onDeleted: () => void
}) {
  const toast = useToast()
  const me = useSession((s) => s.user)
  const spaces = useSocial((s) => s.spaces)
  const loadSpaces = useSocial((s) => s.loadSpaces)

  const [name, setName] = useState(bot.name)
  const [description, setDescription] = useState(bot.description ?? '')
  const [url, setUrl] = useState(bot.interactions_url ?? '')
  const [dmEnabled, setDmEnabled] = useState(bot.dm_enabled !== false)
  const [saving, setSaving] = useState(false)
  const [commands, setCommands] = useState<BotCommandDto[]>([])
  const [botSpaces, setBotSpaces] = useState<BotSpaceDto[]>([])
  const [draft, setDraft] = useState<CommandDraft | null>(null)
  const [removing, setRemoving] = useState<BotCommandDto | null>(null)
  const [leaving, setLeaving] = useState<BotSpaceDto | null>(null)
  const [picking, setPicking] = useState(false)
  const [rotating, setRotating] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [token, setToken] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const [nextCommands, nextSpaces] = await Promise.all([
        api.botCommands(bot.id),
        api.botSpaces(bot.id),
      ])
      setCommands(nextCommands)
      setBotSpaces(nextSpaces)
    } catch (err) {
      toast.error(reason(err, 'could not load this bot'))
    }
  }, [bot.id, toast])

  useEffect(() => {
    void load()
    void loadSpaces()
  }, [load, loadSpaces])

  const joined = useMemo(() => new Set(botSpaces.map((space) => space.id)), [botSpaces])

  const manageable = useMemo(
    () =>
      spaces.filter(
        (space) => space.owner_id === me?.id || space.role === 'owner' || space.role === 'admin',
      ),
    [spaces, me?.id],
  )

  const spaceName = useCallback(
    (spaceId: string) =>
      botSpaces.find((space) => space.id === spaceId)?.name ||
      spaces.find((space) => space.id === spaceId)?.name ||
      'nest',
    [botSpaces, spaces],
  )

  const save = async () => {
    const trimmed = url.trim()
    if (!name.trim()) {
      toast.error('a bot needs a name')
      return
    }
    if (trimmed && !/^https:\/\//i.test(trimmed)) {
      toast.error('the interactions url has to start with https://')
      return
    }
    setSaving(true)
    try {
      await api.updateBot(bot.id, {
        name: name.trim(),
        description: description.trim() || null,
        interactions_url: trimmed || null,
        dm_enabled: dmEnabled,
      })
      toast.show('bot saved')
      await onChanged()
    } catch (err) {
      toast.error(reason(err, 'could not save the bot'))
    } finally {
      setSaving(false)
    }
  }

  const saveCommand = async (next: CommandDraft) => {
    const rest = commands.filter((command) => command.id !== next.id).map(toInput)
    const merged = rest.concat({
      name: next.name,
      description: next.description,
      options: next.options,
      space_id: next.space_id,
      dm_enabled: next.dm_enabled,
    })
    setCommands(await api.putBotCommands(bot.id, merged))
    toast.show('commands updated')
  }

  const rotate = async () => {
    try {
      setToken(await api.rotateBotToken(bot.id))
    } catch (err) {
      toast.error(reason(err, 'could not rotate the token'))
    }
  }

  const destroy = async () => {
    try {
      await api.deleteBot(bot.id)
      toast.show('bot deleted')
      onDeleted()
    } catch (err) {
      toast.error(reason(err, 'could not delete the bot'))
    }
  }

  const join = async (spaceId: string) => {
    try {
      await api.botJoinSpace(bot.id, spaceId)
      setPicking(false)
      await load()
      toast.show('bot added to the nest')
    } catch (err) {
      toast.error(reason(err, 'could not add the bot'))
    }
  }

  return (
    <Screen>
      <TopBar
        title={bot.name}
        subtitle={bot.username ? `@${bot.username}` : 'bot'}
        onBack={onBack}
      />
      <ScreenBody>
        <div className="bots__hero">
          <Avatar name={botLabel(bot)} avatarKey={bot.avatar_square_key || bot.avatar_key} size="hero" />
          <div className="bots__hero-name">
            {bot.name}
            <span className="bots__tag">bot</span>
          </div>
          {bot.username ? <div className="bots__hero-handle">@{bot.username}</div> : null}
        </div>

        <div className="ui-group__label">profile</div>
        <div className="settings__form">
          <TextField label="name" value={name} onChange={setName} maxLength={32} />
          <TextField
            label="description"
            value={description}
            onChange={setDescription}
            multiline
            rows={2}
            maxLength={200}
          />
          <TextField
            label="interactions url"
            value={url}
            onChange={setUrl}
            type="url"
            placeholder="https://example.com/interactions"
            helper="https only — leave it empty and the bot polls for interactions instead"
          />
          <div className="bots__toggle">
            <span className="bots__toggle-text">
              <span>direct messages</span>
              <span className="bots__hint">let people dm this bot</span>
            </span>
            <Switch checked={dmEnabled} onChange={setDmEnabled} label="direct messages" />
          </div>
          <Button size="cta" fullWidth loading={saving} onClick={() => void save()}>
            save changes
          </Button>
        </div>

        <div className="bots__section">
          <span className="bots__section-label">commands</span>
          <IconButton
            label="add command"
            tone="accent"
            filled
            onClick={() =>
              setDraft({
                id: null,
                name: '',
                description: '',
                space_id: null,
                dm_enabled: true,
                options: [],
              })
            }
          >
            <Add />
          </IconButton>
        </div>
        {commands.length === 0 ? (
          <div className="bots__empty-note">no commands yet — add one so people can call it</div>
        ) : (
          commands.map((command) => (
            <ListRow
              key={command.id}
              title={`/${command.name}`}
              subtitle={`${command.description} · ${
                command.space_id ? spaceName(command.space_id) : 'global'
              } · ${(command.options ?? []).length} options`}
              onClick={() =>
                setDraft({
                  id: command.id,
                  name: command.name,
                  description: command.description,
                  space_id: command.space_id ?? null,
                  dm_enabled: command.dm_enabled !== false,
                  options: (command.options ?? []).map((option) => ({ ...option })),
                })
              }
              trailing={
                <IconButton
                  label="remove command"
                  tone="danger"
                  onClick={(e) => {
                    e.stopPropagation()
                    setRemoving(command)
                  }}
                >
                  <Delete />
                </IconButton>
              }
            />
          ))
        )}

        <div className="bots__section">
          <span className="bots__section-label">nests</span>
          <IconButton label="add to a nest" tone="accent" filled onClick={() => setPicking(true)}>
            <Add />
          </IconButton>
        </div>
        {botSpaces.length === 0 ? (
          <div className="bots__empty-note">this bot is not in any nest yet</div>
        ) : (
          botSpaces.map((space) => (
            <ListRow
              key={space.id}
              leading={
                <Avatar
                  name={space.name || 'nest'}
                  avatarKey={space.icon_square_key || space.icon_key}
                />
              }
              title={space.name || 'nest'}
              subtitle="in this nest"
              trailing={
                <IconButton
                  label="remove from nest"
                  tone="danger"
                  onClick={(e) => {
                    e.stopPropagation()
                    setLeaving(space)
                  }}
                >
                  <Delete />
                </IconButton>
              }
            />
          ))
        )}

        <SettingsGroup label="token">
          <SettingsRow
            icon={<Key size={18} />}
            title="rotate token"
            value="the old token stops working right away"
            onClick={() => setRotating(true)}
          />
          <SettingsRow
            icon={<Delete size={18} />}
            title="delete bot"
            value="removes the bot and its account"
            danger
            onClick={() => setDeleting(true)}
          />
        </SettingsGroup>
      </ScreenBody>

      {draft ? (
        <CommandSheet
          key={draft.id ?? 'new'}
          draft={draft}
          scopes={botSpaces}
          onClose={() => setDraft(null)}
          onSave={saveCommand}
        />
      ) : null}

      <Sheet open={picking} title="add to a nest" onClose={() => setPicking(false)}>
        {manageable.length === 0 ? (
          <div className="bots__empty-note">you don't own or manage any nest yet</div>
        ) : (
          manageable.map((space) => (
            <ListRow
              key={space.id}
              leading={
                <Avatar name={space.name} avatarKey={space.icon_square_key || space.icon_key} />
              }
              title={space.name}
              subtitle={joined.has(space.id) ? 'already added' : space.role || 'owner'}
              trailing={joined.has(space.id) ? undefined : <ChevronRight />}
              onClick={joined.has(space.id) ? undefined : () => void join(space.id)}
            />
          ))
        )}
      </Sheet>

      <ConfirmDialog
        open={!!removing}
        title={`remove /${removing?.name ?? 'command'}?`}
        body="people will stop seeing it in the command palette."
        confirmLabel="remove"
        danger
        onConfirm={async () => {
          if (!removing) return
          try {
            await api.deleteBotCommand(bot.id, removing.id)
            await load()
          } catch (err) {
            toast.error(reason(err, 'could not remove the command'))
          }
        }}
        onClose={() => setRemoving(null)}
      />

      <ConfirmDialog
        open={!!leaving}
        title={`remove from ${leaving?.name ?? 'this nest'}?`}
        body="the bot leaves the nest and its commands go with it."
        confirmLabel="remove"
        danger
        onConfirm={async () => {
          if (!leaving) return
          try {
            await api.botLeaveSpace(bot.id, leaving.id)
            await load()
          } catch (err) {
            toast.error(reason(err, 'could not remove the bot'))
          }
        }}
        onClose={() => setLeaving(null)}
      />

      <ConfirmDialog
        open={rotating}
        title="rotate the token?"
        body="the current token stops working immediately and the new one is shown once."
        confirmLabel="rotate"
        danger
        onConfirm={() => void rotate()}
        onClose={() => setRotating(false)}
      />

      <ConfirmDialog
        open={deleting}
        title={`delete ${bot.name}?`}
        body="the bot loses access everywhere. this can't be undone."
        confirmLabel="delete"
        danger
        onConfirm={() => void destroy()}
        onClose={() => setDeleting(false)}
      />

      <TokenDialog token={token} onClose={() => setToken(null)} />
    </Screen>
  )
}

export default function BotsScreen() {
  const navigate = useNavigate()
  const toast = useToast()
  const [bots, setBots] = useState<BotDto[]>([])
  const [loading, setLoading] = useState(true)
  const [openId, setOpenId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [saving, setSaving] = useState(false)
  const [token, setToken] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setBots(await api.bots())
    } catch (err) {
      toast.error(reason(err, 'could not load your bots'))
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void load()
  }, [load])

  const create = async () => {
    if (!name.trim()) {
      toast.error('give the bot a name')
      return
    }
    setSaving(true)
    try {
      const trimmed = description.trim()
      const created = await api.createBot(
        trimmed ? { name: name.trim(), description: trimmed } : { name: name.trim() },
      )
      setCreating(false)
      setName('')
      setDescription('')
      setToken(created.token)
      await load()
    } catch (err) {
      toast.error(reason(err, 'could not create the bot'))
    } finally {
      setSaving(false)
    }
  }

  const open = bots.find((bot) => bot.id === openId) ?? null

  if (open) {
    return (
      <BotDetail
        bot={open}
        onBack={() => setOpenId(null)}
        onChanged={load}
        onDeleted={() => {
          setOpenId(null)
          void load()
        }}
      />
    )
  }

  return (
    <Screen>
      <TopBar
        title="bots"
        subtitle="automations you own"
        onBack={() => navigate(-1)}
        actions={
          <IconButton label="new bot" tone="accent" filled onClick={() => setCreating(true)}>
            <Add />
          </IconButton>
        }
      />
      <ScreenBody>
        {loading ? (
          <LoadingState label="loading bots" />
        ) : bots.length === 0 ? (
          <EmptyState
            icon={<Bolt size={28} />}
            title="no bots yet"
            subtitle="bots answer slash commands in your nests and dms"
            action={
              <Button variant="tonal" onClick={() => setCreating(true)}>
                create a bot
              </Button>
            }
          />
        ) : (
          bots.map((bot) => (
            <ListRow
              key={bot.id}
              onClick={() => setOpenId(bot.id)}
              leading={
                <Avatar name={botLabel(bot)} avatarKey={bot.avatar_square_key || bot.avatar_key} />
              }
              title={
                <span className="bots__name">
                  {bot.name}
                  <span className="bots__tag">bot</span>
                </span>
              }
              subtitle={bot.username ? `@${bot.username}` : bot.description || 'no description'}
              trailing={<ChevronRight />}
            />
          ))
        )}
      </ScreenBody>

      <Sheet open={creating} title="new bot" onClose={() => setCreating(false)}>
        <div className="settings__form">
          <TextField
            label="name"
            value={name}
            onChange={setName}
            maxLength={32}
            helper="the username is derived from this"
          />
          <TextField
            label="description"
            value={description}
            onChange={setDescription}
            multiline
            rows={2}
            maxLength={200}
          />
          <Button size="cta" fullWidth loading={saving} onClick={() => void create()}>
            create bot
          </Button>
        </div>
      </Sheet>

      <TokenDialog token={token} onClose={() => setToken(null)} />
    </Screen>
  )
}
