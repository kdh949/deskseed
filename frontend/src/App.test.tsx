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

function sessionFetch(role: 'ADMIN' | 'AGENT') {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith('/api/v1/agent/me')) {
      return new Response(
        JSON.stringify({
          id: `${role.toLowerCase()}-id`,
          email: `${role.toLowerCase()}@example.com`,
          displayName: role === 'ADMIN' ? '관리자' : '상담사',
          role,
          capabilities:
            role === 'ADMIN'
              ? ['ADMIN_MANAGE', 'AGENT_WORKSPACE']
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
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
