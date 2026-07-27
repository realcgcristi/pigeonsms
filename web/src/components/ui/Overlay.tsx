import { useEffect } from 'react'
import type { ReactNode } from 'react'
import { Button } from '@/components/ui/Button'
import './ui.css'

export function Backdrop({ onClose }: { onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return <div className="ui-backdrop" onClick={onClose} />
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
  if (!open) return null
  return (
    <>
      <Backdrop onClose={onClose} />
      <div className="ui-sheet" role="dialog">
        <div className="ui-sheet__handle" />
        {title ? <div className="ui-sheet__title">{title}</div> : null}
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
  if (!open) return null
  return (
    <>
      <Backdrop onClose={onClose} />
      <div className="ui-dialog" role="dialog">
        {title ? <div className="ui-dialog__title">{title}</div> : null}
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
  if (!open) return null
  const top = Math.min(y, window.innerHeight - items.length * 44 - 40)
  return (
    <>
      <Backdrop onClose={onClose} />
      <div className="ui-menu" style={{ left: Math.max(8, Math.min(x, 260)), top: Math.max(8, top) }}>
        {items.map((item) => (
          <button
            key={item.key}
            type="button"
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
