import { type ReactNode } from 'react'
import { NavLink, useOutlet } from 'react-router'
import {
  DeskseedBrandMark,
  DsButton,
  DsInitialAvatar,
} from '../../design-system'

const navigation = [
  { label: '메일 운영', to: '/admin/operations/mail' },
  { label: '직원', to: '/admin/staff' },
  { label: '그룹', to: '/admin/groups' },
  { label: '고객 접근', to: '/admin/settings/customer-access-mode' },
  { label: '영업 시간표', to: '/admin/business-rules/schedules' },
  { label: 'First Reply SLA', to: '/admin/business-rules/sla' },
]

function initials(displayName: string) {
  const words = displayName.trim().split(/\s+/).filter(Boolean)
  if (words.length > 1) {
    return words
      .slice(0, 2)
      .map((word) => Array.from(word)[0])
      .join('')
      .toLocaleUpperCase()
  }
  return Array.from(words[0] ?? '')
    .slice(0, 2)
    .join('')
}

export function AdminShell({
  children,
  displayName,
  onSignOut,
}: {
  children?: ReactNode
  displayName: string
  onSignOut?: () => void
}) {
  const outlet = useOutlet()

  return (
    <div className="admin-shell">
      <a className="skip-link" href="#admin-main-content">
        본문으로 건너뛰기
      </a>
      <header className="admin-shell-header">
        <NavLink
          aria-label="Deskseed 관리자 운영 홈"
          className="admin-shell-brand"
          to="/admin/operations/mail"
        >
          <DeskseedBrandMark size="sm" />
          <span>Deskseed 운영</span>
        </NavLink>
        <div className="admin-shell-identity">
          <DsInitialAvatar
            initials={initials(displayName)}
            label={displayName}
          />
          <span>{displayName}</span>
          {onSignOut ? (
            <DsButton onClick={onSignOut} tone="ghost">
              로그아웃
            </DsButton>
          ) : null}
        </div>
      </header>
      <div className="admin-shell-body">
        <nav aria-label="관리자 설정 메뉴" className="admin-shell-navigation">
          <h2>운영 설정</h2>
          <ul>
            {navigation.map((item) => (
              <li key={item.to}>
                <NavLink
                  className={({ isActive }) =>
                    isActive ? 'is-active' : undefined
                  }
                  to={item.to}
                >
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <div id="admin-main-content">{children ?? outlet}</div>
      </div>
    </div>
  )
}
