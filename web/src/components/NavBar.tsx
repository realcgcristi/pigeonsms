import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Forum, Mood, Person } from '@/components/icons'
import { Logo, NestIcon } from '@/components/Logo'
import { Avatar } from '@/components/ui/Avatar'
import { useSession } from '@/store/session'
import { totalDmUnread, totalSpaceUnread, useSocial } from '@/store/social'
import { syncDesktopUnread } from '@/desktop/runtime'
import './NavBar.css'

const TABS = ['/', '/friends', '/spaces', '/you'] as const

function activeRoute(path: string, search: string) {
  const isNestChat = path.startsWith('/chat/') && new URLSearchParams(search).get('space') === 'true'
  const isNestCall = path.startsWith('/call/') && !!new URLSearchParams(search).get('spaceId')
  if (
    path === '/' ||
    (path.startsWith('/chat/') && !isNestChat) ||
    path.startsWith('/thread') ||
    path.startsWith('/search') ||
    (path.startsWith('/call/') && !isNestCall)
  ) {
    return '/'
  }
  if (path.startsWith('/friends') || path.startsWith('/profile/')) return '/friends'
  if (isNestChat || isNestCall || path.startsWith('/spaces') || path.startsWith('/nest/') || path.startsWith('/forum/')) {
    return '/spaces'
  }
  return '/you'
}

export function NavBar() {
  const location = useLocation()
  const navigate = useNavigate()
  const user = useSession((s) => s.user)
  const dms = useSocial((s) => s.dms)
  const spaces = useSocial((s) => s.spaces)
  const loadAll = useSocial((s) => s.loadAll)
  const subscribe = useSocial((s) => s.subscribe)

  useEffect(() => {
    void loadAll()
    return subscribe()
  }, [loadAll, subscribe])

  const path = location.pathname
  const topLevel = TABS.includes(path as (typeof TABS)[number])
  const currentRoute = activeRoute(path, location.search)

  const messageBadge = totalDmUnread(dms)
  const nestBadge = totalSpaceUnread(spaces)
  const name = user?.display_name || user?.username || 'you'

  useEffect(() => {
    void syncDesktopUnread(messageBadge + nestBadge).catch(() => undefined)
  }, [messageBadge, nestBadge])

  const tabs = [
    { route: '/', label: 'messages', icon: <Forum />, badge: messageBadge },
    { route: '/friends', label: 'friends', icon: <Mood />, badge: 0 },
    { route: '/spaces', label: 'bird nests', icon: <NestIcon />, badge: nestBadge },
    {
      route: '/you',
      label: 'you',
      icon: user?.avatar_square_key || user?.avatar_key
        ? <Avatar name={name} avatarKey={user.avatar_square_key || user.avatar_key} size={24} />
        : <Person />,
      badge: 0,
    },
  ]

  return (
    <nav className={topLevel ? 'navbar' : 'navbar navbar--detail'} aria-label="primary navigation">
      <button type="button" className="navbar__brand" onClick={() => navigate('/')} aria-label="pigeonsms home">
        <Logo size={42} />
        <span className="navbar__brand-copy">
          <strong>pigeonsms</strong>
          <small>your cozy corner</small>
        </span>
      </button>
      <div className="navbar__section-label">workspace</div>
      <div className="navbar__bar">
        {tabs.map((tab) => {
          const active = tab.route === currentRoute
          return (
            <button
              key={tab.route}
              type="button"
              className={active ? 'navbar__tab navbar__tab--on' : 'navbar__tab'}
              onClick={() => navigate(tab.route)}
              aria-label={tab.label}
              aria-current={active ? 'page' : undefined}
            >
              <span className="navbar__icon">{tab.icon}</span>
              <span className="navbar__label">{tab.label}</span>
              {tab.badge > 0 ? (
                <span className="navbar__badge">{tab.badge > 99 ? '99+' : tab.badge}</span>
              ) : null}
            </button>
          )
        })}
      </div>
      <button type="button" className="navbar__account" onClick={() => navigate('/you')}>
        <Avatar name={name} avatarKey={user?.avatar_square_key || user?.avatar_key} size="sm" showPresence online />
        <span className="navbar__account-copy">
          <strong>{name}</strong>
          <small>@{user?.username}</small>
        </span>
      </button>
    </nav>
  )
}

export default NavBar
