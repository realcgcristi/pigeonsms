import type { TransparencyEntryDto, TransparencyResponse } from '@/api/dto'

const encoder = new TextEncoder()

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(value))
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

function canonical(entry: Omit<TransparencyEntryDto, 'entry_hash'>): string {
  return JSON.stringify([
    entry.id,
    entry.user_id,
    entry.device_id,
    entry.action,
    entry.public_key ?? null,
    entry.previous_hash ?? null,
    entry.created_at,
  ])
}

export async function verifyTransparency(response: TransparencyResponse): Promise<boolean> {
  let previous: string | null = null
  for (const entry of response.entries) {
    if ((entry.previous_hash ?? null) !== previous) return false
    const { entry_hash: _, ...unsigned } = entry
    const hash = await sha256(`pigeon-key-v1:${canonical(unsigned)}`)
    if (hash !== entry.entry_hash) return false
    previous = hash
  }
  return merkleRoot(response.entries.map((entry) => entry.entry_hash)).then(
    (root) => root === response.checkpoint.root_hash && response.checkpoint.tree_size === response.entries.length,
  )
}

export async function merkleRoot(hashes: string[]): Promise<string> {
  if (hashes.length === 0) return sha256('pigeon-empty-v1')
  let level = hashes.slice()
  while (level.length > 1) {
    const next: string[] = []
    for (let index = 0; index < level.length; index += 2) {
      const left = level[index] ?? ''
      const right = level[index + 1] ?? left
      next.push(await sha256(`pigeon-node-v1:${left}:${right}`))
    }
    level = next
  }
  return level[0] ?? sha256('pigeon-empty-v1')
}

export function pinnedCheckpoint(userId: string): { tree_size: number; root_hash: string } | null {
  try {
    const value = localStorage.getItem(`pigeon.transparency.v1:${userId}`)
    return value ? JSON.parse(value) as { tree_size: number; root_hash: string } : null
  } catch {
    return null
  }
}

export function pinCheckpoint(userId: string, checkpoint: { tree_size: number; root_hash: string }) {
  localStorage.setItem(`pigeon.transparency.v1:${userId}`, JSON.stringify(checkpoint))
}

export function checkpointChanged(
  previous: { tree_size: number; root_hash: string } | null,
  current: { tree_size: number; root_hash: string },
): boolean {
  if (!previous) return false
  if (current.tree_size < previous.tree_size) return true
  return current.tree_size === previous.tree_size && current.root_hash !== previous.root_hash
}

export async function checkpointConsistent(
  previous: { tree_size: number; root_hash: string } | null,
  entries: TransparencyEntryDto[],
): Promise<boolean> {
  if (!previous) return true
  if (entries.length < previous.tree_size) return false
  const prefix = entries.slice(0, previous.tree_size).map((entry) => entry.entry_hash)
  return (await merkleRoot(prefix)) === previous.root_hash
}
