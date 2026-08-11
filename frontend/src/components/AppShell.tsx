import type { PropsWithChildren } from 'react'
import { Link, NavLink } from 'react-router'
import { AppShell as AppShellFrame, BrandMark } from '../shared/ui/system'

export function AppShell({ children }: PropsWithChildren) {
  return (
    <AppShellFrame className="app-shell" contentId="main-content">
      <header className="site-header">
        <div className="header-inner">
          <Link className="brand" to="/">
            <BrandMark />
            <span>
              <strong>Deskseed</strong>
              <small>고객지원 포털</small>
            </span>
          </Link>
          <nav aria-label="주요 메뉴">
            <NavLink to="/requests/new">문의 접수</NavLink>
            <NavLink to="/requests/lookup">문의 조회</NavLink>
          </nav>
        </div>
      </header>
      <main className="page" id="main-content" tabIndex={-1}>
        {children}
      </main>
      <footer>
        <p>Deskseed · 익명 문의 접수와 공개 대화 조회</p>
      </footer>
    </AppShellFrame>
  )
}
