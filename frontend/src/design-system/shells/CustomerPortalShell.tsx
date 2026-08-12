import type { PropsWithChildren } from 'react'
import { Link, NavLink } from 'react-router'
import { DeskseedBrandMark } from '../primitives/DeskseedPrimitives'

export function CustomerPortalShell({ children }: PropsWithChildren) {
  return (
    <div className="customer-portal-shell">
      <a className="skip-link" href="#main-content">
        본문으로 건너뛰기
      </a>
      <header className="customer-portal-header">
        <Link className="customer-portal-brand" to="/">
          <DeskseedBrandMark />
          <span>
            <strong>Deskseed</strong>
            <small>고객지원 포털</small>
          </span>
        </Link>
        <nav aria-label="주요 메뉴">
          <NavLink to="/requests/new">문의 접수</NavLink>
          <NavLink to="/requests/lookup">문의 조회</NavLink>
        </nav>
      </header>
      <main className="customer-portal-main" id="main-content" tabIndex={-1}>
        {children}
      </main>
      <footer className="customer-portal-footer">Deskseed 고객지원</footer>
    </div>
  )
}
