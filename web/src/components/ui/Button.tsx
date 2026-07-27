import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Pressable } from '@/components/ui/Pressable'
import { Spinner } from '@/components/ui/Spinner'
import './Button.css'

export type ButtonVariant = 'filled' | 'tonal' | 'text' | 'danger'
export type ButtonSize = 'md' | 'cta'

export type ButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> & {
  children: ReactNode
  variant?: ButtonVariant
  size?: ButtonSize
  fullWidth?: boolean
  loading?: boolean
  leading?: ReactNode
  trailing?: ReactNode
}

export function Button({
  children,
  variant = 'filled',
  size = 'md',
  fullWidth = false,
  loading = false,
  leading,
  trailing,
  disabled = false,
  className,
  ...rest
}: ButtonProps) {
  const classes = [
    'ui-button',
    `ui-button--${variant}`,
    `ui-button--${size}`,
    fullWidth ? 'ui-button--full' : undefined,
    className,
  ]
    .filter(Boolean)
    .join(' ')
  return (
    <Pressable
      className={classes}
      pressedScale={0.96}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? (
        <Spinner size={18} stroke={2} className="ui-button__spinner" />
      ) : (
        leading && <span className="ui-button__slot">{leading}</span>
      )}
      <span className="ui-button__label">{children}</span>
      {trailing && <span className="ui-button__slot">{trailing}</span>}
    </Pressable>
  )
}

export default Button
