import type { CSSProperties, ReactNode } from 'react'
import { api } from '@/api/client'
import { avatarColor, initial, ON_AVATAR } from '@/lib/hash'
import './ui.css'

export type AvatarSize = 'xs' | 'sm' | 'md' | 'lg' | 'hero'

const SIZES: Record<AvatarSize, number> = { xs: 24, sm: 32, md: 44, lg: 56, hero: 96 }

export type AvatarProps = {
  name: string
  avatarKey?: string | null
  size?: AvatarSize | number
  online?: boolean | null
  showPresence?: boolean
  className?: string
  style?: CSSProperties
  onClick?: () => void
  badge?: ReactNode
}

export function Avatar({
  name,
  avatarKey,
  size = 'md',
  online,
  showPresence = false,
  className,
  style,
  onClick,
  badge,
}: AvatarProps) {
  const px = typeof size === 'number' ? size : SIZES[size]
  const vars: CSSProperties = {
    ...style,
    width: px,
    height: px,
    fontSize: Math.round(px * 0.42),
    ['--on-avatar' as string]: ON_AVATAR,
  }
  return (
    <span
      className={['ui-avatar', className].filter(Boolean).join(' ')}
      style={vars}
      onClick={onClick}
      role={onClick ? 'button' : undefined}
    >
      {avatarKey ? (
        <img className="ui-avatar__img" src={api.mediaUrl(avatarKey)} alt={name} loading="lazy" />
      ) : (
        <span className="ui-avatar__fallback" style={{ background: avatarColor(name) }}>
          {initial(name)}
        </span>
      )}
      {showPresence ? <span className={online ? 'ui-avatar__dot' : 'ui-avatar__dot ui-avatar__dot--off'} /> : null}
      {badge}
    </span>
  )
}

export function AvatarStack({
  people,
  max = 4,
  size = 'xs',
}: {
  people: { id: string; name: string; avatarKey?: string | null }[]
  max?: number
  size?: AvatarSize
}) {
  return (
    <span className="ui-avatar-stack">
      {people.slice(0, max).map((p) => (
        <Avatar key={p.id} name={p.name} avatarKey={p.avatarKey} size={size} />
      ))}
    </span>
  )
}

export default Avatar
