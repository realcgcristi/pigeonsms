import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { PhotoCamera } from '@/components/icons'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { TextField } from '@/components/ui/TextField'
import { Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { useSession } from '@/store/session'
import './Settings.css'

export default function EditProfileScreen() {
  const navigate = useNavigate()
  const toast = useToast()
  const user = useSession((s) => s.user)
  const patchUser = useSession((s) => s.patchUser)
  const refresh = useSession((s) => s.refresh)
  const fileRef = useRef<HTMLInputElement>(null)
  const [displayName, setDisplayName] = useState('')
  const [about, setAbout] = useState('')
  const [pronouns, setPronouns] = useState('')
  const [status, setStatus] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!user) return
    void api.profile(user.id).then((res) => {
      setDisplayName(res.profile.display_name ?? '')
      setAbout(res.profile.about ?? '')
      setPronouns(res.profile.pronouns ?? '')
      setStatus(res.profile.status_text ?? '')
    })
  }, [user])

  const save = async () => {
    setSaving(true)
    try {
      await api.updateProfile({
        display_name: displayName,
        about,
        pronouns,
        status_text: status,
      })
      patchUser({ display_name: displayName })
      await refresh()
      toast.show('profile saved')
      navigate(-1)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not save')
    }
    setSaving(false)
  }

  const upload = async (file: File) => {
    try {
      const key = await api.uploadAvatar(file, file.type || 'image/png')
      patchUser({ avatar_key: key })
      toast.show('avatar updated')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'upload failed')
    }
  }

  return (
    <Screen>
      <TopBar title="edit profile" onBack={() => navigate(-1)} />
      <ScreenBody>
        <div className="settings__form" style={{ alignItems: 'center' }}>
          <Avatar name={displayName || user?.username || 'you'} avatarKey={user?.avatar_key} size="hero" />
          <Button variant="tonal" leading={<PhotoCamera size={18} />} onClick={() => fileRef.current?.click()}>
            change avatar
          </Button>
        </div>
        <div className="settings__form">
          <TextField label="display name" value={displayName} onChange={setDisplayName} />
          <TextField label="pronouns" value={pronouns} onChange={setPronouns} />
          <TextField label="status" value={status} onChange={setStatus} />
          <TextField label="about" value={about} onChange={setAbout} multiline />
          <Button size="cta" fullWidth loading={saving} onClick={() => void save()}>
            save
          </Button>
        </div>
      </ScreenBody>
      <input
        ref={fileRef}
        type="file"
        accept="image/*"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) void upload(file)
          e.target.value = ''
        }}
      />
    </Screen>
  )
}
