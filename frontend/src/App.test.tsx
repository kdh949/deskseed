import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { DeskseedThemeProvider } from './shared/ui/DeskseedThemeProvider'

function TestApp() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return (
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  )
}

function sessionFetch(role: 'ADMIN' | 'AGENT' | 'SECURITY_AUDITOR') {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith('/api/v1/agent/me')) {
      return new Response(
        JSON.stringify({
          id: `${role.toLowerCase()}-id`,
          email: `${role.toLowerCase()}@example.com`,
          displayName:
            role === 'ADMIN'
              ? '관리자'
              : role === 'SECURITY_AUDITOR'
                ? '보안 감사자'
                : '상담사',
          role,
          capabilities:
            role === 'ADMIN'
              ? ['ADMIN_MANAGE', 'AGENT_WORKSPACE']
              : role === 'SECURITY_AUDITOR'
                ? ['audit:activity:read', 'audit:search-query:reveal']
                : ['AGENT_WORKSPACE'],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )
    }
    if (url.endsWith('/api/v1/admin/staff')) {
      return new Response('[]', {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (url.endsWith('/api/v1/agent/views')) {
      return new Response(
        JSON.stringify([
          {
            key: 'my-open',
            name: '내 open',
            scope: 'SYSTEM',
            categoryPath: ['Views'],
            ticketCount: 0,
            readScope: 'ALL_TICKETS',
          },
        ]),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )
    }
    if (url.includes('/api/v1/agent/views/my-open/tickets')) {
      return new Response(
        JSON.stringify({
          items: [],
          nextCursor: null,
          totalApproximate: null,
          sort: 'updatedAt:desc,ticketNumber:desc',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )
    }
    if (url.includes('/api/v1/audit/activities')) {
      return new Response(
        JSON.stringify({
          items: [],
          nextCursor: null,
          snapshotAt: '2026-08-11T00:00:00Z',
          projection: {
            state: 'CURRENT',
            projectedCount: 0,
            lastRebuiltAt: null,
          },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )
    }
    throw new Error(`Unexpected request: ${url}`)
  })
}

describe('App', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('renders the Agent Shell without the public customer portal chrome', async () => {
    vi.stubGlobal('fetch', sessionFetch('AGENT'))
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/agent/home']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(await screen.findByRole('main', { name: '내 open' })).toBeVisible()
    expect(
      screen.queryByRole('navigation', { name: '주요 메뉴' }),
    ).not.toBeInTheDocument()
  })

  it('does not render admin screens when an agent enters a direct admin URL', async () => {
    vi.stubGlobal('fetch', sessionFetch('AGENT'))
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/admin/staff']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(
      await screen.findByRole('heading', { name: '관리자 권한이 필요합니다.' }),
    ).toBeVisible()
    expect(
      screen.queryByRole('heading', { name: '직원 계정' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('navigation', { name: '관리자 설정 메뉴' }),
    ).not.toBeInTheDocument()
  })

  it('renders admin screens only for an admin session', async () => {
    vi.stubGlobal('fetch', sessionFetch('ADMIN'))
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/admin/staff']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(
      await screen.findByRole('heading', { name: '직원 계정' }),
    ).toBeVisible()
    expect(
      screen.getByRole('navigation', { name: '관리자 설정 메뉴' }),
    ).toBeVisible()
  })

  it('renders the audit explorer only for a security auditor session', async () => {
    vi.stubGlobal('fetch', sessionFetch('SECURITY_AUDITOR'))
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/audit/activity']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(
      await screen.findByRole('heading', { name: '활동 조사' }),
    ).toBeVisible()
    expect(screen.getByText('READ ONLY')).toBeVisible()
    expect(
      screen.queryByRole('link', { name: '상담사 화면' }),
    ).not.toBeInTheDocument()
  })

  it('does not call audit APIs when an agent enters a direct audit URL', async () => {
    const fetchMock = sessionFetch('AGENT')
    vi.stubGlobal('fetch', fetchMock)
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/audit/activity']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(
      await screen.findByRole('heading', {
        name: '보안 감사자 권한이 필요합니다.',
      }),
    ).toBeVisible()
    expect(
      fetchMock.mock.calls.some(([url]) =>
        String(url).includes('/api/v1/audit/'),
      ),
    ).toBe(false)
  })

  it('does not call ticket APIs when a security auditor enters a direct agent URL', async () => {
    const fetchMock = sessionFetch('SECURITY_AUDITOR')
    vi.stubGlobal('fetch', fetchMock)
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/agent/tickets/1042']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(
      await screen.findByRole('heading', {
        name: '상담사 작업 공간 권한이 필요합니다.',
      }),
    ).toBeVisible()
    expect(
      fetchMock.mock.calls.some(([url]) =>
        String(url).includes('/api/v1/agent/tickets/'),
      ),
    ).toBe(false)
  })

  it('returns to login when an authenticated admin request reports an expired session', async () => {
    const fetchMock = sessionFetch('ADMIN')
    fetchMock.mockImplementationOnce(
      async () =>
        new Response(
          JSON.stringify({
            id: 'admin-id',
            email: 'admin@example.com',
            displayName: '관리자',
            role: 'ADMIN',
            capabilities: ['ADMIN_MANAGE', 'AGENT_WORKSPACE'],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
    )
    fetchMock.mockImplementationOnce(
      async () =>
        new Response(JSON.stringify({ status: 401 }), {
          status: 401,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    )
    vi.stubGlobal('fetch', fetchMock)
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/admin/staff']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(
      await screen.findByRole('heading', { name: '직원 로그인' }),
    ).toBeVisible()
    expect(
      screen.queryByRole('heading', { name: '직원 계정' }),
    ).not.toBeInTheDocument()
  })
})
