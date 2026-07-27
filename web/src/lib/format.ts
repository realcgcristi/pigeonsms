const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

export function timeOfDay(ts: number): string {
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

export function relativeTime(ts: number): string {
  if (!ts) return ''
  const delta = Date.now() - ts
  if (delta < MINUTE) return 'now'
  if (delta < HOUR) return `${Math.floor(delta / MINUTE)}m`
  if (delta < DAY) return `${Math.floor(delta / HOUR)}h`
  if (delta < 7 * DAY) return `${Math.floor(delta / DAY)}d`
  return new Date(ts).toLocaleDateString([], { month: 'short', day: 'numeric' })
}

export function lastSeen(ts: number | null | undefined): string {
  if (!ts) return 'offline'
  const delta = Date.now() - ts
  if (delta < 5 * MINUTE) return 'online'
  return `active ${relativeTime(ts)} ago`
}

export function isOnline(ts: number | null | undefined): boolean {
  return !!ts && Date.now() - ts < 5 * MINUTE
}

export function daySeparator(ts: number): string {
  const date = new Date(ts)
  const today = new Date()
  const yesterday = new Date(Date.now() - DAY)
  if (date.toDateString() === today.toDateString()) return 'today'
  if (date.toDateString() === yesterday.toDateString()) return 'yesterday'
  return date.toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' })
}

export function sameDay(a: number, b: number): boolean {
  return new Date(a).toDateString() === new Date(b).toDateString()
}

export function bytes(size: number | null | undefined): string {
  if (!size) return ''
  const units = ['B', 'KB', 'MB', 'GB']
  let value = size
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 10 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}

export function duration(ms: number): string {
  const total = Math.floor(ms / 1000)
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
}

export function fullDate(ts: number): string {
  return new Date(ts).toLocaleString([], {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
