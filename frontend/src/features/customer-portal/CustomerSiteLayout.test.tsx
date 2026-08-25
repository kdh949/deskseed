import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { CustomerSiteLayout } from '../../design-system'

describe('CustomerSiteLayout', () => {
  it('composes the approved customer workspace navigation and complementary action', () => {
    render(
      <MemoryRouter>
        <CustomerSiteLayout
          presentation="workspace"
          session={{ status: 'anonymous' }}
        >
          <section>조회 작업</section>
        </CustomerSiteLayout>
      </MemoryRouter>,
    )

    expect(
      screen.getByRole('navigation', { name: '고객 지원 메뉴' }),
    ).toBeVisible()
    expect(
      screen.getByRole('navigation', { name: '고객 지원 탐색 목록' }),
    ).toBeVisible()
    expect(screen.getByRole('main', { name: '문의 조회' })).toHaveAttribute(
      'id',
      'customer-main-content',
    )
    expect(
      screen.getByRole('complementary', { name: '새 문의 접수' }),
    ).toBeVisible()
  })

  it('gives an anonymous visitor request, lookup, and sign-in navigation', () => {
    render(
      <MemoryRouter>
        <CustomerSiteLayout session={{ status: 'anonymous' }}>
          <main>고객 내용</main>
        </CustomerSiteLayout>
      </MemoryRouter>,
    )

    expect(
      screen.getByRole('navigation', { name: '고객 탐색' }),
    ).toHaveTextContent('문의하기')
    expect(screen.getByRole('link', { name: '문의 조회' })).toHaveAttribute(
      'href',
      '/requests/lookup',
    )
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
          <main>고객 내용</main>
        </CustomerSiteLayout>
      </MemoryRouter>,
    )

    expect(screen.getByRole('link', { name: '내 문의' })).toHaveAttribute(
      'href',
      '/account/requests',
    )
    expect(screen.getByText('고객')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    expect(onSignOut).toHaveBeenCalledOnce()
  })
})
