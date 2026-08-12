import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { DeskseedThemeProvider } from './design-system'

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
              : role === 'AGENT'
                ? ['AGENT_WORKSPACE']
                : ['audit:activity:read'],
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

  it('redirects the root route to the Agent Queue', async () => {
    vi.stubGlobal('fetch', sessionFetch('AGENT'))
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(await screen.findByRole('main', { name: '티켓 큐' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '내 티켓' })).toBeVisible()
    expect(
      screen.getByRole('navigation', { name: '상담사 전역 탐색' }),
    ).toBeVisible()
    expect(
      screen.queryByRole('navigation', { name: '주요 메뉴' }),
    ).not.toBeInTheDocument()
  })

  it('renders the canonical not-found state for a removed product route', async () => {
    vi.stubGlobal('fetch', sessionFetch('AGENT'))
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/admin/staff']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(
      await screen.findByRole('heading', {
        name: '페이지를 찾을 수 없습니다.',
      }),
    ).toBeVisible()
    expect(
      screen.queryByRole('heading', { name: '직원 계정' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('navigation', { name: '관리자 설정 메뉴' }),
    ).not.toBeInTheDocument()
  })

  it('denies the Agent Workspace to a security auditor', async () => {
    vi.stubGlobal('fetch', sessionFetch('SECURITY_AUDITOR'))
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/agent/views/my-open']}>
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
      screen.queryByRole('main', { name: '티켓 큐' }),
    ).not.toBeInTheDocument()
  })

  it('redirects an authenticated admin from login to the Agent Queue', async () => {
    vi.stubGlobal('fetch', sessionFetch('ADMIN'))
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/agent/login']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(await screen.findByRole('main', { name: '티켓 큐' })).toBeVisible()
    expect(
      screen.queryByRole('heading', { name: '직원 로그인' }),
    ).not.toBeInTheDocument()
  })
})
