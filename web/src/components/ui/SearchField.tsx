import { useId } from 'react'
import type { CSSProperties, KeyboardEvent } from 'react'
import { Close, Search } from '@/components/icons'
import { IconButton } from '@/components/ui/IconButton'
import './SearchField.css'

export type SearchFieldProps = {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  maxLength?: number
  disabled?: boolean
  autoFocus?: boolean
  onSubmit?: (value: string) => void
  onClear?: () => void
  className?: string
  style?: CSSProperties
}

export function SearchField({
  value,
  onChange,
  placeholder = 'search',
  maxLength = 64,
  disabled = false,
  autoFocus = false,
  onSubmit,
  onClear,
  className,
  style,
}: SearchFieldProps) {
  const id = useId()

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter' && onSubmit) {
      event.preventDefault()
      onSubmit(value.trim())
    }
    if (event.key === 'Escape' && value.length > 0) {
      event.preventDefault()
      onChange('')
      onClear?.()
    }
  }

  return (
    <div className={['ui-searchfield', className].filter(Boolean).join(' ')} style={style}>
      <Search size={20} className="ui-searchfield__icon" />
      <input
        id={id}
        type="search"
        role="searchbox"
        className="ui-searchfield__input"
        value={value}
        placeholder={placeholder}
        maxLength={maxLength}
        disabled={disabled}
        autoFocus={autoFocus}
        autoComplete="off"
        autoCorrect="off"
        spellCheck={false}
        enterKeyHint="search"
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={onKeyDown}
      />
      {value.length > 0 && (
        <IconButton
          label="clear"
          className="ui-searchfield__clear"
          onClick={() => {
            onChange('')
            onClear?.()
          }}
        >
          <Close size={18} />
        </IconButton>
      )}
    </div>
  )
}

export default SearchField
