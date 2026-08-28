import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { CustomerSiteLayout } from '../../design-system'

describe('CustomerSiteLayout', () => {
  it('composes the isolated customer header, main, and footer landmarks', () => {
    render(
      <MemoryRouter>
        <CustomerSiteLayout session={{ status: 'anonymous' }}>
          <section>조회 작업</section>
        </CustomerSiteLayout>
      </MemoryRouter>,
    )

    expect(screen.getByRole('navigation', { name: '고객 메뉴' })).toBeVisible()
    expect(screen.queryByText('⌘ K')).not.toBeInTheDocument()
    expect(screen.getByRole('main')).toHaveAttribute(
      'id',
      'customer-main-content',
    )
    expect(screen.getByRole('contentinfo')).toBeVisible()
  })

  it('gives an anonymous visitor request, lookup, and sign-in navigation', () => {
    render(
      <MemoryRouter>
        <CustomerSiteLayout session={{ status: 'anonymous' }}>
          <section>고객 내용</section>
        </CustomerSiteLayout>
      </MemoryRouter>,
    )

    expect(
      screen.getByRole('navigation', { name: '고객 메뉴' }),
    ).toHaveTextContent('문의 접수')
    expect(screen.getByRole('link', { name: '로그인' })).toHaveAttribute(
      'href',
      '/customer/sign-in',
    )
  })

  it('shows an authenticated customer their requests and invokes the supplied logout action', async () => {
    const onSignOut = vi.fn()
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <CustomerSiteLayout
          onSignOut={onSignOut}
          session={{
            customer: {
              id: '11111111-1111-4111-8111-111111111111',
              email: 'customer@example.test',
              displayName: '고객',
              verifiedAt: '2026-08-15T00:00:00Z',
            },
            status: 'authenticated',
          }}
        >
          <section>고객 내용</section>
        </CustomerSiteLayout>
      </MemoryRouter>,
    )

    expect(screen.getAllByRole('link', { name: '내 문의' })[0]).toHaveAttribute(
      'href',
      '/account/requests',
    )
    expect(screen.getByText('고객')).toBeVisible()
    expect(screen.getByText('고객').closest('a')).toBeNull()
    expect(screen.queryByRole('link', { name: '시스템 상태' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'DeskSeed 소개' })).toBeNull()
    expect(screen.queryByRole('link', { name: '개인정보 처리방침' })).toBeNull()
    expect(screen.queryByRole('link', { name: '이용약관' })).toBeNull()
    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    expect(onSignOut).toHaveBeenCalledOnce()
  })
})
