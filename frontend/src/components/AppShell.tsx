import type { PropsWithChildren } from 'react'
import { Link, NavLink } from 'react-router'

export function AppShell({ children }: PropsWithChildren) {
  return (
    <div className="app-shell">
      <header className="site-header">
        <div className="header-inner">
          <Link className="brand" to="/">
            <span className="brand-mark" aria-hidden="true">
              D
            </span>
            <span>
              <strong>Deskseed</strong>
              <small>고객지원 포털</small>
            </span>
          </Link>
          <nav aria-label="주요 메뉴">
            <NavLink to="/requests/new">문의 접수</NavLink>
            <NavLink to="/lookup">문의 조회</NavLink>
          </nav>
        </div>
      </header>
      <main className="page">{children}</main>
      <footer>
        <p>현재 M1 데모: 웹 문의 접수와 공개 대화 조회만 구현되어 있습니다.</p>
      </footer>
    </div>
  )
}
