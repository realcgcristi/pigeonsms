import type { CSSProperties, ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowBack } from '@/components/icons'
import { IconButton } from '@/components/ui/IconButton'
import './ui.css'

export function TopBar({
  title,
  subtitle,
  leading,
  onBack,
  actions,
  elevated,
}: {
  title?: ReactNode
  subtitle?: ReactNode
  leading?: ReactNode
  onBack?: () => void
  actions?: ReactNode
  elevated?: boolean
}) {
  return (
    <header className={elevated ? 'ui-topbar ui-topbar--elevated' : 'ui-topbar'}>
      {onBack ? <IconButton label="back" onClick={onBack}><ArrowBack /></IconButton> : null}
      {leading}
      <div className="ui-topbar__title">
        {title}
        {subtitle ? <div className="ui-topbar__sub">{subtitle}</div> : null}
      </div>
      {actions ? <div className="ui-topbar__actions">{actions}</div> : null}
    </header>
  )
}

export function Screen({
  children,
  className,
  style,
}: {
  children: ReactNode
  className?: string
  style?: CSSProperties
}) {
  return (
    <div className={['ui-screen', className].filter(Boolean).join(' ')} style={style}>
      {children}
    </div>
  )
}

export function ScreenBody({
  children,
  tabbed,
  className,
  onScroll,
  innerRef,
}: {
  children: ReactNode
  tabbed?: boolean
  className?: string
  onScroll?: (e: React.UIEvent<HTMLDivElement>) => void
  innerRef?: React.Ref<HTMLDivElement>
}) {
  return (
    <div
      ref={innerRef}
      onScroll={onScroll}
      className={['ui-screen__body', tabbed ? 'ui-screen__body--tabbed' : '', className]
        .filter(Boolean)
        .join(' ')}
    >
      {children}
    </div>
  )
}

export function SubScreen({
  title,
  actions,
  children,
  onBack,
}: {
  title: ReactNode
  actions?: ReactNode
  children: ReactNode
  onBack?: () => void
}) {
  const navigate = useNavigate()
  return (
    <Screen>
      <TopBar title={title} actions={actions} onBack={onBack ?? (() => navigate(-1))} />
      <ScreenBody>{children}</ScreenBody>
    </Screen>
  )
}

export function ListRow({
  leading,
  title,
  subtitle,
  trailing,
  onClick,
  onContextMenu,
  className,
}: {
  leading?: ReactNode
  title: ReactNode
  subtitle?: ReactNode
  trailing?: ReactNode
  onClick?: () => void
  onContextMenu?: (e: React.MouseEvent) => void
  className?: string
}) {
  return (
    <button
      type="button"
      className={['ui-row', className].filter(Boolean).join(' ')}
      onClick={onClick}
      onContextMenu={onContextMenu}
    >
      {leading}
      <span className="ui-row__text">
        <span className="ui-row__title">{title}</span>
        {subtitle ? <span className="ui-row__subtitle">{subtitle}</span> : null}
      </span>
      {trailing ? <span className="ui-row__trailing">{trailing}</span> : null}
    </button>
  )
}

export function SettingsGroup({ label, children }: { label?: string; children: ReactNode }) {
  return (
    <section>
      {label ? <div className="ui-group__label">{label}</div> : null}
      <div className="ui-group">{children}</div>
    </section>
  )
}

export function SettingsRow({
  icon,
  title,
  value,
  trailing,
  onClick,
  danger,
}: {
  icon?: ReactNode
  title: ReactNode
  value?: ReactNode
  trailing?: ReactNode
  onClick?: () => void
  danger?: boolean
}) {
  return (
    <button
      type="button"
      className={danger ? 'ui-setting ui-setting--danger' : 'ui-setting'}
      onClick={onClick}
    >
      {icon ? <span className="ui-setting__badge">{icon}</span> : null}
      <span className="ui-setting__text">
        <span className="ui-setting__title">{title}</span>
        {value ? <span className="ui-setting__value">{value}</span> : null}
      </span>
      {trailing}
    </button>
  )
}

export function Badge({ count }: { count: number }) {
  if (!count) return null
  return <span className="ui-badge">{count > 99 ? '99+' : count}</span>
}

export function Chip({
  label,
  active,
  onClick,
  leading,
}: {
  label: ReactNode
  active?: boolean
  onClick?: () => void
  leading?: ReactNode
}) {
  return (
    <button type="button" className={active ? 'ui-chip ui-chip--on' : 'ui-chip'} onClick={onClick}>
      {leading}
      {label}
    </button>
  )
}

export function ChipRow({ children }: { children: ReactNode }) {
  return <div className="ui-chips">{children}</div>
}

export function Tabs<T extends string>({
  tabs,
  value,
  onChange,
}: {
  tabs: { key: T; label: string }[]
  value: T
  onChange: (key: T) => void
}) {
  return (
    <div className="ui-tabs">
      {tabs.map((tab) => (
        <button
          key={tab.key}
          type="button"
          className={tab.key === value ? 'ui-tab ui-tab--on' : 'ui-tab'}
          onClick={() => onChange(tab.key)}
        >
          {tab.label}
        </button>
      ))}
    </div>
  )
}

export function Divider() {
  return <div className="ui-divider" />
}

export function EmptyState({
  icon,
  title,
  subtitle,
  action,
}: {
  icon?: ReactNode
  title: string
  subtitle?: string
  action?: ReactNode
}) {
  return (
    <div className="ui-empty">
      {icon ? <div className="ui-empty__icon">{icon}</div> : null}
      <div className="ui-empty__title">{title}</div>
      {subtitle ? <div className="ui-empty__sub">{subtitle}</div> : null}
      {action}
    </div>
  )
}

export function Fab({ icon, label, onClick }: { icon: ReactNode; label?: string; onClick: () => void }) {
  return (
    <button type="button" className="ui-fab" onClick={onClick}>
      {icon}
      {label}
    </button>
  )
}

export function Skeleton({ height = 16, width = '100%' }: { height?: number; width?: number | string }) {
  return <div className="ui-skeleton" style={{ height, width }} />
}
