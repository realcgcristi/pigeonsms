import type { ButtonHTMLAttributes, CSSProperties, ReactNode } from 'react'
import './Pressable.css'

export type PressableProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  pressedScale?: number
  children?: ReactNode
}

export function Pressable({
  pressedScale = 0.97,
  className,
  style,
  type = 'button',
  children,
  ...rest
}: PressableProps) {
  const vars = { ...style, '--pressable-scale': String(pressedScale) } as CSSProperties
  return (
    <button
      type={type}
      className={['ui-pressable', className].filter(Boolean).join(' ')}
      style={vars}
      {...rest}
    >
      {children}
    </button>
  )
}

export default Pressable
