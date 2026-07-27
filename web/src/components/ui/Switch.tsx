import type { CSSProperties } from 'react'
import './Switch.css'

export type SwitchProps = {
  checked: boolean
  onChange: (checked: boolean) => void
  disabled?: boolean
  label?: string
  className?: string
  style?: CSSProperties
}

export function Switch({ checked, onChange, disabled = false, label, className, style }: SwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      className={['ui-switch', checked ? 'is-on' : undefined, className].filter(Boolean).join(' ')}
      style={style}
      onClick={() => onChange(!checked)}
    >
      <span className="ui-switch__track">
        <span className="ui-switch__thumb" />
      </span>
    </button>
  )
}

export default Switch
