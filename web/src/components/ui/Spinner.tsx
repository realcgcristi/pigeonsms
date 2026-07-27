import type { CSSProperties } from 'react'
import './Spinner.css'

export type SpinnerProps = {
  size?: number
  stroke?: number
  label?: string
  className?: string
  style?: CSSProperties
}

export function Spinner({ size = 28, stroke = 2, label, className, style }: SpinnerProps) {
  const vars = {
    ...style,
    '--spinner-size': `${size}px`,
    '--spinner-stroke': `${stroke}px`,
  } as CSSProperties
  return (
    <span
      className={['ui-spinner', className].filter(Boolean).join(' ')}
      style={vars}
      role="progressbar"
      aria-label={label ?? 'loading'}
    >
      <span className="ui-spinner__ring" />
    </span>
  )
}

export type LoadingStateProps = {
  label: string
  className?: string
}

export function LoadingState({ label, className }: LoadingStateProps) {
  return (
    <div className={['ui-loading-state', className].filter(Boolean).join(' ')}>
      <Spinner size={28} stroke={2} label={label} />
      <p className="ui-loading-state__label">{label}</p>
    </div>
  )
}

export default Spinner
