export const AVATAR_PALETTE = ['#FF9D76', '#B8A7F5', '#7FD8A4', '#76BEFF', '#FF8FB0', '#FFC46B']
export const ON_AVATAR = '#201018'

export function hashIndex(value: string, buckets: number): number {
  let hash = 0
  for (let i = 0; i < value.length; i += 1) {
    hash = (hash * 31 + value.charCodeAt(i)) | 0
  }
  return Math.abs(hash) % buckets
}

export function avatarColor(name: string): string {
  return AVATAR_PALETTE[hashIndex(name || '?', AVATAR_PALETTE.length)]
}

export function initial(name: string): string {
  const trimmed = (name || '?').trim()
  return (trimmed[0] ?? '?').toUpperCase()
}
