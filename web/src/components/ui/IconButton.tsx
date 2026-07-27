import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Pressable } from '@/components/ui/Pressable'
import './IconButton.css'

export type IconButtonTone = 'default' | 'accent' | 'danger'

export type IconButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> & {
  children: ReactNode
  label?: string
  tone?: IconButtonTone
  filled?: boolean
  badge?: boolean
  size?: number
}

export function IconButton({
  children,
  label,
  tone = 'default',
  filled = false,
  badge = false,
  size,
  className,
  style,
  ...rest
}: IconButtonProps) {
  const classes = [
    'ui-icon-button',
    `ui-icon-button--${tone}`,
    filled ? 'ui-icon-button--filled' : undefined,
    badge ? 'ui-icon-button--badge' : undefined,
    className,
  ]
    .filter(Boolean)
    .join(' ')
  return (
    <Pressable
      className={classes}
      pressedScale={0.9}
      aria-label={label}
      title={label}
      style={size ? { ...style, width: size, height: size, minWidth: size, minHeight: size } : style}
      {...rest}
    >
      {children}
    </Pressable>
  )
}

export default IconButton
