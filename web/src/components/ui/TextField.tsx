import { useId } from 'react'
import type { CSSProperties, KeyboardEvent, ReactNode } from 'react'
import './TextField.css'

export type TextFieldProps = {
  value: string
  onChange: (value: string) => void
  label?: string
  placeholder?: string
  type?: 'text' | 'password' | 'email' | 'url' | 'number' | 'tel'
  maxLength?: number
  multiline?: boolean
  rows?: number
  disabled?: boolean
  readOnly?: boolean
  error?: string | null
  helper?: string
  counter?: boolean
  leading?: ReactNode
  trailing?: ReactNode
  autoFocus?: boolean
  autoComplete?: string
  name?: string
  id?: string
  inputMode?: 'text' | 'numeric' | 'email' | 'search' | 'tel' | 'url'
  onSubmit?: () => void
  onFocus?: () => void
  onBlur?: () => void
  className?: string
  style?: CSSProperties
}

export function TextField({
  value,
  onChange,
  label,
  placeholder,
  type = 'text',
  maxLength,
  multiline = false,
  rows = 3,
  disabled = false,
  readOnly = false,
  error,
  helper,
  counter = false,
  leading,
  trailing,
  autoFocus = false,
  autoComplete,
  name,
  id,
  inputMode,
  onSubmit,
  onFocus,
  onBlur,
  className,
  style,
}: TextFieldProps) {
  const generated = useId()
  const fieldId = id ?? generated
  const helpId = `${fieldId}-help`
  const message = error ?? helper
  const classes = [
    'ui-textfield',
    label ? 'ui-textfield--labelled' : undefined,
    multiline ? 'ui-textfield--multiline' : undefined,
    value.length > 0 ? 'is-filled' : undefined,
    error ? 'is-error' : undefined,
    disabled ? 'is-disabled' : undefined,
    className,
  ]
    .filter(Boolean)
    .join(' ')

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    if (!onSubmit) return
    if (event.key !== 'Enter') return
    if (multiline && !(event.metaKey || event.ctrlKey)) return
    event.preventDefault()
    onSubmit()
  }

  const shared = {
    id: fieldId,
    name,
    value,
    disabled,
    readOnly,
    autoFocus,
    maxLength,
    placeholder: label && value.length === 0 ? undefined : placeholder,
    'aria-describedby': message ? helpId : undefined,
    'aria-invalid': error ? true : undefined,
    onFocus,
    onBlur,
    onKeyDown,
  }

  return (
    <div className={classes} style={style}>
      <div className="ui-textfield__box">
        {leading && <span className="ui-textfield__leading">{leading}</span>}
        <div className="ui-textfield__field">
          {label && (
            <label className="ui-textfield__label" htmlFor={fieldId}>
              {label}
            </label>
          )}
          {multiline ? (
            <textarea
              {...shared}
              rows={rows}
              className="ui-textfield__input"
              onChange={(event) => onChange(event.target.value)}
            />
          ) : (
            <input
              {...shared}
              type={type}
              inputMode={inputMode}
              autoComplete={autoComplete}
              className="ui-textfield__input"
              onChange={(event) => onChange(event.target.value)}
            />
          )}
        </div>
        {trailing && <span className="ui-textfield__trailing">{trailing}</span>}
      </div>
      {(message || (counter && maxLength)) && (
        <div className="ui-textfield__footer">
          {message && (
            <span className="ui-textfield__help" id={helpId}>
              {message}
            </span>
          )}
          {counter && maxLength && (
            <span className="ui-textfield__counter">
              {value.length}/{maxLength}
            </span>
          )}
        </div>
      )}
    </div>
  )
}

export default TextField
