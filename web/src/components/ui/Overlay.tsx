import { useEffect, useId, useRef } from 'react'
import type { ReactNode } from 'react'
import { Button } from '@/components/ui/Button'
import './ui.css'

export function Backdrop({ onClose }: { onClose: () => void }) {
  return <div className="ui-backdrop" aria-hidden="true" onClick={onClose} />
}

function useModalFocus(open: boolean, onClose: () => void) {
  const ref = useRef<HTMLDivElement>(null)
  const closeRef = useRef(onClose)
  closeRef.current = onClose
  useEffect(() => {
    if (!open) return
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const node = ref.current
    const focusable = () =>
      Array.from(
        node?.querySelectorAll<HTMLElement>(
          'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ) ?? [],
      )
    ;(focusable()[0] ?? node)?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        closeRef.current()
        return
      }
      if (event.key !== 'Tab') return
      const items = focusable()
      if (!items.length) {
        event.preventDefault()
        node?.focus()
        return
      }
      const first = items[0]
      const last = items[items.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      previous?.focus()
    }
  }, [open])
  return ref
}

export function Sheet({
  open,
  title,
  onClose,
  children,
}: {
  open: boolean
  title?: ReactNode
  onClose: () => void
  children: ReactNode
}) {
  const titleId = useId()
  const ref = useModalFocus(open, onClose)
  if (!open) return null
  return (
    <>
      <Backdrop onClose={onClose} />
      <div
        ref={ref}
        className="ui-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? titleId : undefined}
        tabIndex={-1}
      >
        <div className="ui-sheet__handle" />
        {title ? <div className="ui-sheet__title" id={titleId}>{title}</div> : null}
        <div className="ui-sheet__body">{children}</div>
      </div>
    </>
  )
}

export function Dialog({
  open,
  title,
  onClose,
  children,
  actions,
}: {
  open: boolean
  title?: ReactNode
  onClose: () => void
  children?: ReactNode
  actions?: ReactNode
}) {
  const titleId = useId()
  const ref = useModalFocus(open, onClose)
  if (!open) return null
  return (
    <>
      <Backdrop onClose={onClose} />
      <div
        ref={ref}
        className="ui-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? titleId : undefined}
        tabIndex={-1}
      >
        {title ? <div className="ui-dialog__title" id={titleId}>{title}</div> : null}
        <div className="ui-dialog__body">{children}</div>
        {actions ? <div className="ui-dialog__actions">{actions}</div> : null}
      </div>
    </>
  )
}

export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel = 'confirm',
  danger,
  onConfirm,
  onClose,
}: {
  open: boolean
  title: string
  body?: string
  confirmLabel?: string
  danger?: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  return (
    <Dialog
      open={open}
      title={title}
      onClose={onClose}
      actions={
        <>
          <Button variant="text" onClick={onClose}>
            cancel
          </Button>
          <Button
            variant={danger ? 'danger' : 'filled'}
            onClick={() => {
              onConfirm()
              onClose()
            }}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      {body}
    </Dialog>
  )
}

export type MenuItem = {
  key: string
  label: string
  icon?: ReactNode
  danger?: boolean
  onSelect: () => void
}

export function ContextMenu({
  open,
  x,
  y,
  items,
  onClose,
}: {
  open: boolean
  x: number
  y: number
  items: MenuItem[]
  onClose: () => void
}) {
  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose, open])
  if (!open) return null
  const top = Math.min(y, window.innerHeight - items.length * 44 - 40)
  return (
    <>
      <Backdrop onClose={onClose} />
      <div className="ui-menu" role="menu" style={{ left: Math.max(8, Math.min(x, 260)), top: Math.max(8, top) }}>
        {items.map((item) => (
          <button
            key={item.key}
            type="button"
            role="menuitem"
            className={item.danger ? 'ui-menu__item ui-menu__item--danger' : 'ui-menu__item'}
            onClick={() => {
              item.onSelect()
              onClose()
            }}
          >
            {item.icon}
            {item.label}
          </button>
        ))}
      </div>
    </>
  )
}
