import { create } from 'zustand'
import { queuedMessages } from '@/lib/localFirst'
import { NetworklessSession, type NetworklessState } from '@/lib/networkless'
import { useChat } from './chat'
import { useSession } from './session'
import { useSocial } from './social'

interface NetworklessRuntimeState {
  status: NetworklessState
  spaceId: string
  offer: string
  answer: string
  shared: number
  host: (spaceId: string, passphrase: string) => Promise<void>
  join: (spaceId: string, passphrase: string, offer: string) => Promise<void>
  accept: (answer: string) => Promise<void>
  stop: () => void
}

let activeSession: NetworklessSession | null = null
let removeState: (() => void) | null = null
let removeMessage: (() => void) | null = null
const sent = new Set<string>()

function allowedChannels(spaceId: string): Set<string> {
  const space = useSocial.getState().spaces.find((item) => item.id === spaceId)
  return new Set(space?.channels?.filter((channel) => channel.kind !== 'voice').map((channel) => channel.id) ?? [])
}

async function drain() {
  const session = activeSession
  const { user } = useSession.getState()
  const { spaceId } = useNetworkless.getState()
  if (!session || !user || !spaceId) return
  const allowed = allowedChannels(spaceId)
  const queued = await queuedMessages(user.id)
  for (const item of queued) {
    if (!allowed.has(item.channelId) || item.options.attachment || sent.has(item.nonce)) continue
    const nearby = {
      version: 1 as const,
      spaceId,
      channelId: item.channelId,
      nonce: item.nonce,
      author: {
        id: user.id,
        username: user.username,
        display_name: user.display_name,
        avatar_key: user.avatar_key,
        accent: user.accent,
      },
      content: item.content,
      createdAt: item.createdAt,
    }
    if (await session.send(nearby)) {
      sent.add(item.nonce)
      useChat.getState().receiveNearby(nearby)
      useNetworkless.setState((state) => ({ shared: state.shared + 1 }))
    }
  }
}

function releaseSession() {
  window.removeEventListener('pigeon:outbox', drainOutbox)
  removeState?.()
  removeMessage?.()
  removeState = null
  removeMessage = null
  activeSession?.close()
  activeSession = null
  sent.clear()
}

function drainOutbox() {
  void drain()
}

function attach(session: NetworklessSession, spaceId: string, offer: string, answer: string) {
  releaseSession()
  activeSession = session
  useNetworkless.setState({ status: 'pairing', spaceId, offer, answer, shared: 0 })
  removeState = session.onState((status) => {
    useNetworkless.setState({ status })
    if (status === 'connected') void drain()
  })
  removeMessage = session.onMessage((message) => {
    if (!allowedChannels(spaceId).has(message.channelId)) return
    useChat.getState().receiveNearby(message)
    useNetworkless.setState((state) => ({ shared: state.shared + 1 }))
  })
  window.addEventListener('pigeon:outbox', drainOutbox)
}

export function stopNetworkless() {
  releaseSession()
  useNetworkless.setState({ status: 'closed', spaceId: '', offer: '', answer: '', shared: 0 })
}

export const useNetworkless = create<NetworklessRuntimeState>(() => ({
  status: 'closed',
  spaceId: '',
  offer: '',
  answer: '',
  shared: 0,
  host: async (spaceId, passphrase) => {
    const created = await NetworklessSession.host(spaceId, passphrase)
    attach(created.session, spaceId, created.offer, '')
  },
  join: async (spaceId, passphrase, offer) => {
    const created = await NetworklessSession.join(spaceId, passphrase, offer)
    attach(created.session, spaceId, '', created.answer)
  },
  accept: async (answer) => {
    if (!activeSession) throw new Error('start a nearby session first')
    await activeSession.accept(answer)
  },
  stop: stopNetworkless,
}))
