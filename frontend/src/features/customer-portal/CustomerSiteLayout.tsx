import { useLayoutEffect, type ReactNode } from 'react'
import { Link } from 'react-router'
import { DeskseedBrandMark, DsButton } from '../../design-system'
import type { CurrentCustomer } from '../customer-auth/api/customerAuthClient'
import type { CustomerSessionStatus } from '../customer-auth/CustomerSessionContext'

export interface CustomerSiteSession {
  customer?: CurrentCustomer | null
  signingOut?: boolean
  status: CustomerSessionStatus
}

export function CustomerSiteLayout({
  children,
  onSignOut,
  session,
}: {
  children: ReactNode
  onSignOut?: () => void
  session: CustomerSiteSession
}) {
  useCustomerNoReferrerPolicy()
  const authenticatedCustomer =
    session.status === 'authenticated' ? session.customer : null

  return (
    <div className="customer-site">
      <a className="skip-link" href="#customer-main-content">
        본문으로 건너뛰기
      </a>
      <header className="customer-site-header">
        <Link
          aria-label="Deskseed 고객 지원 홈"
          className="customer-site-brand"
          to="/"
        >
          <DeskseedBrandMark size="sm" />
          <span>Deskseed 지원</span>
        </Link>
        <nav aria-label="고객 탐색" className="customer-site-navigation">
          <Link to="/requests/new">문의하기</Link>
          <Link to="/requests/lookup">문의 조회</Link>
          {authenticatedCustomer ? (
            <>
              <Link to="/account/requests">내 문의</Link>
              <span className="customer-site-identity">
                {authenticatedCustomer.displayName}
              </span>
              {onSignOut ? (
                <DsButton
                  disabled={session.signingOut}
                  onClick={onSignOut}
                  tone="ghost"
                >
                  {session.signingOut ? '로그아웃 중…' : '로그아웃'}
                </DsButton>
              ) : null}
            </>
          ) : session.status === 'loading' ? (
            <span aria-live="polite" className="customer-site-session-status">
              세션 확인 중
            </span>
          ) : (
            <Link to="/customer/sign-in">로그인</Link>
          )}
        </nav>
      </header>
      <div id="customer-main-content">{children}</div>
    </div>
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
