import {
  canUseAgentWorkspace,
  canReadAudit,
  canManageStaff,
} from '../staff-auth/staffNavigation'
import { useEffect } from 'react'
import { useAgentNotifications } from './useAgentNotifications'
import { Outlet, useLocation, useNavigate } from 'react-router'
import {
  SeedNavigationRail,
  SeedPageShell,
  SeedNotificationMenu,
  SeedTopBar,
  type SeedNavigationItem,
} from '../../design-system/canonical'
import { frontendExtensions } from '../../extension-host/catalog'
import { useStaffSession } from '../staff-auth/StaffSessionContext'

export function AgentShellLayout() {
  const session = useStaffSession()
  const staff = session.staff
  const location = useLocation()
  const navigate = useNavigate()
  const canWork = canUseAgentWorkspace(staff)
  const notifications = useAgentNotifications(
    canWork ? (staff?.id ?? null) : null,
  )

  useEffect(() => {
    if (!canWork) return
    const openSearch = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        navigate('/agent/search')
      }
    }
    window.addEventListener('keydown', openSearch)
    return () => window.removeEventListener('keydown', openSearch)
  }, [navigate, canWork])

  if (!staff?.displayName) return null

  const canCreateTicket = canWork
  const extensionItems = frontendExtensions.agentNavigationFor({
    role: staff.role,
    capabilities: staff.capabilities,
  })
  const items: SeedNavigationItem[] = canWork
    ? [
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
    : []
  const footerItems: SeedNavigationItem[] = [
    ...(canReadAudit(staff)
      ? [
          {
            id: '/agent/audit',
            label: '감사',
            icon: 'eye' as const,
            active: location.pathname.startsWith('/agent/audit'),
          },
        ]
      : []),
    ...(canManageStaff(staff)
      ? [
          {
            id: '/admin/operations/mail',
            label: '관리자 운영',
            icon: 'settings' as const,
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
        canWork ? (
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
                  title:
                    item.type === 'UNASSIGNED_TICKET_ALERT'
                      ? '그룹에 담당자가 없는 티켓이 있습니다'
                      : `${item.actor.displayName} 님이 회원님을 멘션했습니다`,
                  description:
                    item.type === 'UNASSIGNED_TICKET_ALERT'
                      ? `티켓 #${item.ticketNumber} · ${item.actor.displayName}`
                      : `티켓 #${item.ticketNumber}의 내부 협업 메모`,
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
                    .catch(() => notifications.load())
                    .finally(() =>
                      navigate(
                        `/agent/tickets/${notification.ticketNumber}${notification.type === 'COLLABORATION_MENTION' ? '#collaboration' : ''}`,
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
        ) : (
          <header className="seed-audit-topbar">
            <strong>감사 / 활동 기록</strong>
            <span>{staff.displayName}</span>
          </header>
        )
      }
    >
      <Outlet />
    </SeedPageShell>
  )
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
