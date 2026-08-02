import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { MessageDto } from '@/api/dto'
import { protectedMediaUrl, type AttachmentSecret } from '@/lib/e2ee/manager'

function attachmentSecret(message: MessageDto | null | undefined): AttachmentSecret | null {
  const value = message?.metadata?.['e2ee_attachment']
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const candidate = value as Record<string, unknown>
  if (
    candidate['v'] !== 1 ||
    typeof candidate['k'] !== 'string' ||
    typeof candidate['i'] !== 'string' ||
    typeof candidate['n'] !== 'string' ||
    typeof candidate['t'] !== 'string' ||
    typeof candidate['z'] !== 'number'
  ) return null
  return candidate as unknown as AttachmentSecret
}

export function useAttachmentUrl(message: MessageDto | null | undefined) {
  const attachment = message?.attachment
  const secret = attachmentSecret(message)
  const [state, setState] = useState<{ key: string; url: string | null; error: boolean }>({
    key: '',
    url: null,
    error: false,
  })

  useEffect(() => {
    let active = true
    if (!attachment) {
      setState({ key: '', url: null, error: false })
      return () => {
        active = false
      }
    }
    if (!secret) {
      setState({ key: attachment.key, url: api.mediaUrl(attachment.key), error: false })
      return () => {
        active = false
      }
    }
    setState({ key: attachment.key, url: null, error: false })
    void protectedMediaUrl(attachment, secret).then(
      (url) => {
        if (active) setState({ key: attachment.key, url, error: false })
      },
      () => {
        if (active) setState({ key: attachment.key, url: null, error: true })
      },
    )
    return () => {
      active = false
    }
  }, [attachment?.key, secret?.i, secret?.k, secret?.n, secret?.t, secret?.z])

  return {
    url: attachment && state.key === attachment.key ? state.url : null,
    loading: !!attachment && state.key === attachment.key && !state.url && !state.error,
    error: !!attachment && state.key === attachment.key && state.error,
    encrypted: !!secret,
  }
}
