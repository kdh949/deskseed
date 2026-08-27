import {
  useLayoutEffect,
  useState,
  type FormEvent,
  type ReactNode,
} from 'react'
import { Link, useNavigate } from 'react-router'
import { CustomerBrand, DsButton } from './CustomerPrimitives'
import { CustomerIcon } from './CustomerIcon'

export interface CustomerSiteSession {
  customer?: {
    displayName?: string | null
    email?: string
    id?: string
    verifiedAt?: string
  } | null
  signingOut?: boolean
  status: 'anonymous' | 'authenticated' | 'error' | 'loading'
}

export function CustomerSiteLayout({
  children,
  onSignOut,
  session,
}: {
  children: ReactNode
  onSignOut?: () => void
  presentation?: 'site' | 'workspace'
  session: CustomerSiteSession
}) {
  useCustomerNoReferrerPolicy()
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const customer = session.status === 'authenticated' ? session.customer : null
  const submitSearch = (event: FormEvent) => {
    event.preventDefault()
    const normalized = query.trim()
    if (normalized) navigate(`/search?q=${encodeURIComponent(normalized)}`)
  }

  return (
    <div className="customer-app">
      <a className="customer-skip-link" href="#customer-main-content">
        본문으로 건너뛰기
      </a>
      <header className="customer-header">
        <div className="customer-header__inner">
          <Link aria-label="DeskSeed 고객 지원 홈" to="/">
            <CustomerBrand />
          </Link>
          <form
            className="customer-header-search"
            onSubmit={submitSearch}
            role="search"
          >
            <CustomerIcon name="search" />
            <label className="customer-sr-only" htmlFor="global-help-search">
              도움말 검색
            </label>
            <input
              id="global-help-search"
              onChange={(event) => setQuery(event.target.value)}
              placeholder="도움말 문서 검색..."
              value={query}
            />
            <span aria-hidden="true" className="customer-search-shortcut">
              ⌘ K
            </span>
          </form>
          <nav aria-label="고객 메뉴" className="customer-header__nav">
            {customer ? (
              <Link to="/account/requests">내 문의</Link>
            ) : (
              <Link to="/search">문서 둘러보기</Link>
            )}
            <Link className="customer-header__cta" to="/requests/new">
              문의 접수
            </Link>
            {customer ? (
              <div className="customer-profile-menu">
                <span className="customer-avatar" aria-hidden="true">
                  {(customer.displayName || customer.email || 'D')
                    .slice(0, 1)
                    .toUpperCase()}
                </span>
                <Link to="/account/settings">
                  {customer.displayName || customer.email}
                </Link>
                {onSignOut ? (
                  <DsButton
                    disabled={session.signingOut}
                    onClick={onSignOut}
                    tone="ghost"
                  >
                    로그아웃
                  </DsButton>
                ) : null}
              </div>
            ) : (
              <Link to="/customer/sign-in">로그인</Link>
            )}
          </nav>
        </div>
      </header>
      <main id="customer-main-content">{children}</main>
      <footer className="customer-footer">
        <div className="customer-footer__inner">
          <section className="customer-footer__brand">
            <CustomerBrand />
            <p>DeskSeed를 더 잘 활용할 수 있도록 도와드릴게요.</p>
            <small>© 2026 DeskSeed. All rights reserved.</small>
          </section>
          <FooterLinks
            title="지원"
            links={[
              ['고객 지원 홈', '/'],
              ['문의 접수', '/requests/new'],
              ['내 문의', '/account/requests'],
            ]}
          />
          <FooterLinks
            title="리소스"
            links={[
              ['도움말 검색', '/search'],
              ['가이드', '/search?q=가이드'],
              ['시스템 상태', '/'],
            ]}
          />
          <FooterLinks
            title="회사"
            links={[
              ['DeskSeed 소개', '/'],
              ['개인정보 처리방침', '/'],
              ['이용약관', '/'],
            ]}
          />
        </div>
      </footer>
    </div>
  )
}

function FooterLinks({
  title,
  links,
}: {
  title: string
  links: [string, string][]
}) {
  return (
    <section>
      <h2>{title}</h2>
      {links.map(([label, to]) => (
        <Link key={label} to={to}>
          {label}
        </Link>
      ))}
    </section>
  )
}

function useCustomerNoReferrerPolicy() {
  useLayoutEffect(() => {
    const existing = document.querySelector<HTMLMetaElement>(
      'meta[name="referrer"]',
    )
    const meta = existing ?? document.createElement('meta')
    const previousContent = meta.content
    if (!existing) {
      meta.name = 'referrer'
      document.head.append(meta)
    }
    meta.content = 'no-referrer'
    return () => {
      if (existing) meta.content = previousContent
      else meta.remove()
    }
  }, [])
}
