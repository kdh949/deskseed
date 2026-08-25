import { useLayoutEffect, type ReactNode } from 'react'
import { Link } from 'react-router'
import { ViewNavigation } from '../../patterns/ViewNavigation'
import { DsButton } from '../../primitives/DeskseedControls'
import { DeskseedBrandMark } from '../../primitives/DeskseedPrimitives'
import { CustomerSupportShell } from '../CustomerSupportShell/CustomerSupportShell'
import { WorkspaceNavigationRail } from '../WorkspaceNavigationRail/WorkspaceNavigationRail'

export interface CustomerSiteSession {
  customer?: {
    displayName: string
    email?: string
    id?: string
    verifiedAt?: string
  } | null
  signingOut?: boolean
  status: 'anonymous' | 'authenticated' | 'error' | 'loading'
}

type CustomerSiteLayoutProps = {
  children: ReactNode
  onSignOut?: () => void
  presentation?: 'site' | 'workspace'
  session: CustomerSiteSession
}

export function CustomerSiteLayout({
  children,
  onSignOut,
  presentation = 'site',
  session,
}: CustomerSiteLayoutProps) {
  useCustomerNoReferrerPolicy()
  const authenticatedCustomer =
    session.status === 'authenticated' ? session.customer : null

  if (presentation === 'workspace') {
    return (
      <div className="customer-site customer-site--workspace">
        <a className="skip-link" href="#customer-main-content">
          본문으로 건너뛰기
        </a>
        <CustomerSupportShell
          complementary={
            <section className="ds-customer-support-new-request">
              <h2>새 문의 접수</h2>
              <p>새로운 도움이 필요하면 문의 내용을 남겨 주세요.</p>
              <Link to="/requests/new">새 문의 접수</Link>
            </section>
          }
          complementaryLabel="새 문의 접수"
          globalNavigation={
            <WorkspaceNavigationRail
              activeItemId="home"
              ariaLabel="고객 지원 메뉴"
              brandLabel="Deskseed 고객 지원 홈"
              brandTo="/"
              items={[
                { icon: 'home', id: 'home', label: '고객 지원 홈', to: '/' },
                {
                  icon: 'pencil',
                  id: 'new',
                  label: '새 문의 접수',
                  to: '/requests/new',
                },
                authenticatedCustomer
                  ? {
                      icon: 'inbox',
                      id: 'requests',
                      label: '내 문의',
                      to: '/account/requests',
                    }
                  : {
                      icon: 'lock',
                      id: 'login',
                      label: '고객 로그인',
                      to: '/customer/sign-in',
                    },
              ]}
              tone="inverse"
            />
          }
          mainLabel="문의 조회"
          topBar={
            <div className="ds-customer-support-topbar-content">
              <div className="ds-customer-support-breadcrumb">
                <strong>고객 지원</strong>
                <span aria-hidden="true">/</span>
                <span>문의 조회</span>
              </div>
              <CustomerSessionActions
                authenticatedCustomer={authenticatedCustomer}
                onSignOut={onSignOut}
                session={session}
              />
            </div>
          }
          workNavigation={
            <ViewNavigation
              label="고객 지원 탐색"
              sections={[
                {
                  id: 'support',
                  items: [
                    {
                      icon: 'search',
                      key: 'lookup',
                      label: '문의 조회',
                      to: '/',
                    },
                    {
                      icon: 'pencil',
                      key: 'new',
                      label: '새 문의 접수',
                      to: '/requests/new',
                    },
                    authenticatedCustomer
                      ? {
                          icon: 'inbox',
                          key: 'requests',
                          label: '내 문의',
                          to: '/account/requests',
                        }
                      : {
                          icon: 'lock',
                          key: 'login',
                          label: '고객 로그인',
                          to: '/customer/sign-in',
                        },
                  ],
                  label: '지원 메뉴',
                },
              ]}
              title="고객 지원"
            />
          }
        >
          {children}
        </CustomerSupportShell>
      </div>
    )
  }

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
          <CustomerSessionActions
            authenticatedCustomer={authenticatedCustomer}
            onSignOut={onSignOut}
            session={session}
          />
        </nav>
      </header>
      <div id="customer-main-content">{children}</div>
    </div>
  )
}

function CustomerSessionActions({
  authenticatedCustomer,
  onSignOut,
  session,
}: {
  authenticatedCustomer?: {
    displayName: string
    email?: string
    id?: string
    verifiedAt?: string
  } | null
  onSignOut?: () => void
  session: CustomerSiteSession
}) {
  if (authenticatedCustomer) {
    return (
      <div className="customer-site-session-actions">
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
      </div>
    )
  }

  if (session.status === 'loading') {
    return (
      <span aria-live="polite" className="customer-site-session-status">
        세션 확인 중
      </span>
    )
  }

  return <Link to="/customer/sign-in">로그인</Link>
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
