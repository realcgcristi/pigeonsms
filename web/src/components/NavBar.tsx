import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Forum, Mood, Person } from '@/components/icons'
import { NestIcon } from '@/components/Logo'
import { Avatar } from '@/components/ui/Avatar'
import { useSession } from '@/store/session'
import { totalDmUnread, totalSpaceUnread, useSocial } from '@/store/social'
import './NavBar.css'

const TABS = ['/', '/friends', '/spaces', '/you'] as const

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
  if (!TABS.includes(path as (typeof TABS)[number])) return null

  const messageBadge = totalDmUnread(dms)
  const nestBadge = totalSpaceUnread(spaces)
  const name = user?.display_name || user?.username || 'you'

  const tabs = [
    { route: '/', label: 'messages', icon: <Forum />, badge: messageBadge },
    { route: '/friends', label: 'friends', icon: <Mood />, badge: 0 },
    { route: '/spaces', label: 'bird nests', icon: <NestIcon />, badge: nestBadge },
    {
      route: '/you',
      label: 'you',
      icon: user?.avatar_key ? <Avatar name={name} avatarKey={user.avatar_key} size={24} /> : <Person />,
      badge: 0,
    },
  ]

  return (
    <nav className="navbar">
      <div className="navbar__bar">
        {tabs.map((tab) => {
          const active = tab.route === path
          return (
            <button
              key={tab.route}
              type="button"
              className={active ? 'navbar__tab navbar__tab--on' : 'navbar__tab'}
              onClick={() => navigate(tab.route)}
              aria-label={tab.label}
              aria-current={active ? 'page' : undefined}
            >
              {tab.icon}
              {active ? <span className="navbar__label">{tab.label}</span> : null}
              {tab.badge > 0 && !active ? (
                <span className="navbar__badge">{tab.badge > 99 ? '99+' : tab.badge}</span>
              ) : null}
            </button>
          )
        })}
      </div>
    </nav>
  )
}

export default NavBar
