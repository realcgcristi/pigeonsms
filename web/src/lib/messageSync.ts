import type { MessageDto } from '@/api/dto'

export type MessageDeliveryState = 'sent' | 'pending' | 'queued' | 'nearby' | 'failed'

export interface SyncedMessage extends MessageDto {
  state: MessageDeliveryState
}

export const LOCAL_MESSAGE_SEQUENCE = Number.MAX_SAFE_INTEGER
export const NEARBY_MESSAGE_SEQUENCE = Number.MAX_SAFE_INTEGER - 1

const states = new Set<MessageDeliveryState>(['sent', 'pending', 'queued', 'nearby', 'failed'])
const stateRank: Record<MessageDeliveryState, number> = {
  failed: 0,
  pending: 1,
  nearby: 2,
  queued: 3,
  sent: 4,
}

function lex(a: string, b: string): number {
  return a === b ? 0 : a < b ? -1 : 1
}

function stable(value: unknown): string {
  if (value === undefined) return 'undefined'
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(stable).join(',')}]`
  const record = value as Record<string, unknown>
  return `{${Object.keys(record).sort().map((key) => `${JSON.stringify(key)}:${stable(record[key])}`).join(',')}}`
}

function isDeliveryState(value: unknown): value is MessageDeliveryState {
  return typeof value === 'string' && states.has(value as MessageDeliveryState)
}

function normalize(message: MessageDto | SyncedMessage, fallback: MessageDeliveryState): SyncedMessage {
  const state = isDeliveryState((message as Partial<SyncedMessage>).state)
    ? (message as SyncedMessage).state
    : fallback
  return { ...message, state }
}

function isSyntheticId(id: string): boolean {
  return id.startsWith('local-') || id.startsWith('nearby-')
}

export function isServerSequence(seq: number | undefined): seq is number {
  return Number.isSafeInteger(seq) && (seq ?? -1) >= 0 && (seq ?? NEARBY_MESSAGE_SEQUENCE) < NEARBY_MESSAGE_SEQUENCE
}

function isAuthoritative(message: SyncedMessage): boolean {
  return message.state === 'sent' && !isSyntheticId(message.id)
}

function revision(message: SyncedMessage): number {
  return message.edited_at ?? message.created_at
}

function conflictOrder(a: SyncedMessage, b: SyncedMessage): number {
  const deleted = Number(!!a.deleted) - Number(!!b.deleted)
  if (deleted) return deleted
  const authoritative = Number(isAuthoritative(a)) - Number(isAuthoritative(b))
  if (authoritative) return authoritative
  const updated = revision(a) - revision(b)
  if (updated) return updated
  const delivery = stateRank[a.state] - stateRank[b.state]
  if (delivery) return delivery
  const sequence = (isServerSequence(a.seq) ? a.seq : -1) - (isServerSequence(b.seq) ? b.seq : -1)
  if (sequence) return sequence
  return lex(stable(a), stable(b))
}

function displayOrder(a: SyncedMessage, b: SyncedMessage): number {
  const aSeq = isServerSequence(a.seq) ? a.seq : null
  const bSeq = isServerSequence(b.seq) ? b.seq : null
  if (aSeq !== null && bSeq !== null && aSeq !== bSeq) return aSeq - bSeq
  if (aSeq !== null) return -1
  if (bSeq !== null) return 1
  if (a.created_at !== b.created_at) return a.created_at - b.created_at
  return lex(`${a.nonce ?? ''}:${a.id}`, `${b.nonce ?? ''}:${b.id}`)
}

function mergeGroup(group: SyncedMessage[]): SyncedMessage {
  const ordered = group.slice().sort(conflictOrder)
  const merged = { ...ordered[ordered.length - 1] }
  if (merged.deleted) merged.content = ''
  return merged
}

export function reconcileMessages(
  ...sets: ReadonlyArray<ReadonlyArray<MessageDto | SyncedMessage>>
): SyncedMessage[] {
  const messages = sets.flatMap((set) => set.map((message) => normalize(message, 'sent')))
  if (messages.length < 2) return messages.sort(displayOrder)

  const parents = messages.map((_, index) => index)
  const find = (index: number): number => {
    let root = index
    while (parents[root] !== root) root = parents[root]
    while (parents[index] !== index) {
      const next = parents[index]
      parents[index] = root
      index = next
    }
    return root
  }
  const union = (a: number, b: number) => {
    const aRoot = find(a)
    const bRoot = find(b)
    if (aRoot !== bRoot) parents[Math.max(aRoot, bRoot)] = Math.min(aRoot, bRoot)
  }
  const ids = new Map<string, number>()
  const nonces = new Map<string, number>()

  messages.forEach((message, index) => {
    const sameId = ids.get(message.id)
    if (sameId === undefined) ids.set(message.id, index)
    else union(index, sameId)
    if (!message.nonce) return
    const sameNonce = nonces.get(message.nonce)
    if (sameNonce === undefined) nonces.set(message.nonce, index)
    else union(index, sameNonce)
  })

  const groups = new Map<number, SyncedMessage[]>()
  messages.forEach((message, index) => {
    const root = find(index)
    groups.set(root, [...(groups.get(root) ?? []), message])
  })

  return [...groups.values()].map(mergeGroup).sort(displayOrder)
}

export function mergeRemoteMessages(
  current: readonly SyncedMessage[],
  incoming: readonly MessageDto[],
): SyncedMessage[] {
  return reconcileMessages(current, incoming.map((message) => ({ ...message, state: 'sent' as const })))
}

export function restoreCachedMessages(messages: readonly MessageDto[]): SyncedMessage[] {
  return reconcileMessages(messages.map((message) => {
    const saved = (message as Partial<SyncedMessage>).state
    const state = isDeliveryState(saved)
      ? saved
      : (message.metadata as { networkless?: boolean } | null)?.networkless
        ? 'nearby'
        : 'sent'
    return { ...message, state }
  }))
}

export function markMessageDeleted(messages: readonly SyncedMessage[], id: string): SyncedMessage[] {
  return messages.map((message) => message.id === id
    ? { ...message, deleted: true, content: '' }
    : message)
}

export function latestServerSequence(messages: readonly MessageDto[]): number {
  return messages.reduce((latest, message) => isServerSequence(message.seq) ? Math.max(latest, message.seq) : latest, 0)
}
