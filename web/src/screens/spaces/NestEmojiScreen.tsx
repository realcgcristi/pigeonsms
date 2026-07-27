import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { SpaceEmojiDto } from '@/api/dto'
import { Add, Delete, EmojiEmotions } from '@/components/icons'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { TextField } from '@/components/ui/TextField'
import { ConfirmDialog, Sheet } from '@/components/ui/Overlay'
import { Chip, ChipRow, EmptyState, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import './Spaces.css'

export default function NestEmojiScreen() {
  const { spaceId = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const fileRef = useRef<HTMLInputElement>(null)
  const [emojis, setEmojis] = useState<SpaceEmojiDto[]>([])
  const [kind, setKind] = useState<'emoji' | 'sticker'>('emoji')
  const [uploading, setUploading] = useState(false)
  const [pending, setPending] = useState<{ key: string; type: string } | null>(null)
  const [name, setName] = useState('')
  const [deleting, setDeleting] = useState<SpaceEmojiDto | null>(null)

  const load = useCallback(async () => {
    setEmojis(await api.spaceEmojis(spaceId))
  }, [spaceId])

  useEffect(() => {
    void load()
  }, [load])

  const pick = async (file: File) => {
    setUploading(true)
    try {
      const attachment = await api.uploadFile(file, file.name, file.type || 'image/png')
      setPending({ key: attachment.key, type: attachment.type || file.type })
      setName(file.name.replace(/\.[^.]+$/, '').replace(/[^a-z0-9_]/gi, '_').toLowerCase().slice(0, 32))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'upload failed')
    }
    setUploading(false)
  }

  const create = async () => {
    if (!pending) return
    try {
      await api.createSpaceEmoji(spaceId, name.trim(), pending.key, kind, pending.type)
      setPending(null)
      setName('')
      await load()
      toast.show(kind === 'emoji' ? 'emoji added' : 'sticker added')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not add')
    }
  }

  const shown = emojis.filter((e) => e.kind === kind)

  return (
    <Screen>
      <TopBar
        title="nest emoji"
        onBack={() => navigate(-1)}
        actions={
          <IconButton
            label="add"
            tone="accent"
            filled
            onClick={() => fileRef.current?.click()}
          >
            <Add />
          </IconButton>
        }
      />
      <ChipRow>
        <Chip label="emoji" active={kind === 'emoji'} onClick={() => setKind('emoji')} />
        <Chip label="stickers" active={kind === 'sticker'} onClick={() => setKind('sticker')} />
      </ChipRow>
      <ScreenBody>
        {shown.length === 0 ? (
          <EmptyState
            icon={<EmojiEmotions size={28} />}
            title={kind === 'emoji' ? 'no emoji yet' : 'no stickers yet'}
            subtitle="upload a png or gif — the server compresses it"
            action={
              <Button variant="tonal" loading={uploading} onClick={() => fileRef.current?.click()}>
                upload
              </Button>
            }
          />
        ) : (
          <div className="nest__emoji-grid">
            {shown.map((emoji) => (
              <button
                key={emoji.id}
                type="button"
                className="nest__emoji"
                onClick={() => setDeleting(emoji)}
              >
                <img src={api.mediaUrl(emoji.media_key ?? '')} alt={emoji.name} />
                <span>:{emoji.name}:</span>
              </button>
            ))}
          </div>
        )}
      </ScreenBody>

      <input
        ref={fileRef}
        type="file"
        accept="image/*"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) void pick(file)
          e.target.value = ''
        }}
      />

      <Sheet open={!!pending} title={`name this ${kind}`} onClose={() => setPending(null)}>
        <div className="nest__form">
          {pending ? (
            <img
              src={api.mediaUrl(pending.key)}
              alt="preview"
              style={{ width: 88, height: 88, objectFit: 'contain', alignSelf: 'center' }}
            />
          ) : null}
          <TextField label="name" value={name} onChange={setName} />
          <Button size="cta" fullWidth onClick={() => void create()}>
            add {kind}
          </Button>
        </div>
      </Sheet>

      <ConfirmDialog
        open={!!deleting}
        title={`delete :${deleting?.name ?? ''}:?`}
        confirmLabel="delete"
        danger
        onConfirm={async () => {
          if (!deleting) return
          await api.deleteSpaceEmoji(spaceId, deleting.id)
          await load()
        }}
        onClose={() => setDeleting(null)}
      />
      <Delete size={0} />
    </Screen>
  )
}
