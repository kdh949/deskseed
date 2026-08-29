import { useCallback, useEffect, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import {
  SeedNavigationRail,
  SeedPageShell,
  SeedNotificationMenu,
  SeedTopBar,
  type SeedNavigationItem,
} from '../../design-system/canonical'
import {
  ApiError,
  listAgentNotifications,
  markAgentNotificationRead,
} from '../../api/client'
import { frontendExtensions } from '../../extension-host/catalog'
import { useStaffSession } from '../staff-auth/StaffSessionContext'

export function AgentShellLayout() {
  const session = useStaffSession()
  const staff = session.staff
  const location = useLocation()
  const navigate = useNavigate()
  const notifications = useAgentNotifications()

  useEffect(() => {
    const openSearch = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        navigate('/agent/search')
      }
    }
    window.addEventListener('keydown', openSearch)
    return () => window.removeEventListener('keydown', openSearch)
  }, [navigate])

  if (!staff?.displayName) return null

  const canCreateTicket =
    (staff.role === 'AGENT' || staff.role === 'ADMIN') &&
    staff.capabilities.includes('AGENT_WORKSPACE')
  const extensionItems = frontendExtensions.agentNavigationFor({
    role: staff.role,
    capabilities: staff.capabilities,
  })
  const items: SeedNavigationItem[] = [
    {
      id: '/agent/views/my-open',
      label: '티켓',
      icon: 'ticket',
      active:
        location.pathname.startsWith('/agent/views') ||
        location.pathname.startsWith('/agent/tickets'),
    },
    {
      id: '/agent/search',
      label: '검색',
      icon: 'search',
      active: location.pathname === '/agent/search',
    },
    ...extensionItems.map((item) => ({
      id: item.to,
      label: item.label,
      icon: 'columns' as const,
      active: location.pathname.startsWith(item.to),
    })),
  ]
  const footerItems: SeedNavigationItem[] = [
    ...(staff.role === 'ADMIN' || staff.capabilities.includes('AUDIT_VIEW')
      ? [
          {
            id: '/agent/audit',
            label: '감사',
            icon: 'eye' as const,
            active: location.pathname.startsWith('/agent/audit'),
          },
        ]
      : []),
    { id: 'sign-out', label: '로그아웃', icon: 'back' },
  ]
  const initials = staff.displayName
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0])
    .join('')
    .toUpperCase()

  const navigateFromRail = (id: string) => {
    if (id === 'sign-out') {
      void session
        .signOut()
        .finally(() => navigate('/agent/login', { replace: true }))
      return
    }
    navigate(id)
  }

  return (
    <SeedPageShell
      rail={
        <SeedNavigationRail
          footerItems={footerItems}
          items={items}
          onNavigate={navigateFromRail}
        />
      }
      topbar={
        <SeedTopBar
          breadcrumb={breadcrumbFor(location.pathname)}
          onCreate={
            canCreateTicket ? () => navigate('/agent/tickets/new') : undefined
          }
          onSearch={() => navigate('/agent/search')}
          notifications={
            <SeedNotificationMenu
              items={notifications.items.map((item) => ({
                id: item.id,
                title: `${item.actor.displayName} 님이 회원님을 멘션했습니다`,
                description: `티켓 #${item.ticketNumber}의 내부 협업 메모`,
                timestamp: formatNotificationTime(item.createdAt),
                unread: item.readAt === null,
              }))}
              onRetry={notifications.load}
              onSelect={(id) => {
                const notification = notifications.items.find(
                  (item) => item.id === id,
                )
                if (!notification) return
                void notifications
                  .markRead(id)
                  .finally(() =>
                    navigate(
                      `/agent/tickets/${notification.ticketNumber}#collaboration`,
                    ),
                  )
              }}
              state={notifications.state}
              unreadCount={notifications.unreadCount}
            />
          }
          profileInitials={initials || 'DS'}
          profileName={staff.displayName}
        />
      }
    >
      <Outlet />
    </SeedPageShell>
  )
}

function useAgentNotifications() {
  const [items, setItems] = useState<
    Awaited<ReturnType<typeof listAgentNotifications>>['items']
  >([])
  const [unreadCount, setUnreadCount] = useState(0)
  const [state, setState] = useState<'idle' | 'loading' | 'empty' | 'error'>(
    'loading',
  )
  const load = useCallback(async () => {
    setState('loading')
    try {
      const page = await listAgentNotifications()
      setItems(page.items)
      setUnreadCount(page.unreadCount)
      setState(page.items.length ? 'idle' : 'empty')
    } catch {
      setState('error')
    }
  }, [])
  useEffect(() => {
    void load()
  }, [load])
  useEffect(() => {
    if (typeof WebSocket === 'undefined') return
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    let socket: WebSocket | null = null
    try {
      socket = new WebSocket(
        `${scheme}//${window.location.host}/ws/agent/collaboration`,
      )
      socket.onmessage = (event) => {
        if (typeof event.data !== 'string' || event.data.length > 4096) return
        try {
          const message = JSON.parse(event.data) as Record<string, unknown>
          if (message.version === 1 && message.type === 'notification.created')
            void load()
        } catch {
          // Invalid realtime hints are ignored; REST remains authoritative.
        }
      }
    } catch {
      return
    }
    return () => socket?.close()
  }, [load])
  const markRead = async (id: string) => {
    try {
      await markAgentNotificationRead(id)
      setItems((current) =>
        current.map((item) =>
          item.id === id
            ? { ...item, readAt: item.readAt ?? new Date().toISOString() }
            : item,
        ),
      )
      setUnreadCount((current) =>
        Math.max(
          0,
          current -
            (items.find((item) => item.id === id)?.readAt === null ? 1 : 0),
        ),
      )
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) void load()
    }
  }
  return { items, load: () => void load(), markRead, state, unreadCount }
}

function formatNotificationTime(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(value))
}

function breadcrumbFor(pathname: string) {
  if (pathname === '/agent/search') return '검색 / 티켓 전체 검색'
  if (pathname === '/agent/tickets/new') return '티켓 / 새 티켓'
  if (/^\/agent\/tickets\/\d+$/.test(pathname)) {
    return `티켓 / #${pathname.split('/').at(-1)}`
  }
  if (pathname.startsWith('/agent/audit')) return '감사 / 활동 기록'
  return '티켓 / 저장 보기'
}
