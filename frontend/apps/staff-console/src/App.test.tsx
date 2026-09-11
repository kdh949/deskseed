import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
    if (url.includes('/api/v1/admin/staff')) {
      return new Response('[]', {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (url.endsWith('/api/v1/agent/views')) {
      return new Response(
        JSON.stringify([
          {
            id: '00000000-0000-4000-8000-000000000001',
            key: 'my-open',
            name: '내 open',
            scope: 'SYSTEM',
            ownerStaffId: null,
            active: true,
            definitionVersion: 1,
            orderVersion: 1,
            categoryPath: ['Views'],
            conditions: {
              version: 1,
              all: [
                { field: 'STATUS', operator: 'LESS_THAN_SOLVED', values: [] },
              ],
              any: [],
            },
            columns: ['TICKET_NUMBER', 'SUBJECT', 'STATUS'],
            sort: 'updatedAt:desc,ticketNumber:desc',
            ticketCount: 0,
            ticketCountState: 'EXACT',
            readScope: 'ALL_TICKETS',
            createdAt: '2026-08-10T00:00:00Z',
            updatedAt: '2026-08-10T00:00:00Z',
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

  it('renders the canonical not-found state for an unknown product route', async () => {
    vi.stubGlobal('fetch', sessionFetch('AGENT'))
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/unknown-route']}>
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
      screen.queryByRole('heading', { name: '직원' }),
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
        level: 1,
        name: '상담사 작업 공간 권한이 필요합니다.',
      }),
    ).toBeVisible()
    expect(
      screen.queryByRole('region', { name: '티켓 큐' }),
    ).not.toBeInTheDocument()
  })

  it('redirects an authenticated admin from login to the admin workspace', async () => {
    vi.stubGlobal('fetch', sessionFetch('ADMIN'))
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/agent/login']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(
      await screen.findByRole('navigation', { name: '관리자 설정 메뉴' }),
    ).toBeVisible()
    expect(
      screen.queryByRole('heading', { name: 'Deskseed 로그인' }),
    ).not.toBeInTheDocument()
  })

  it('renders an ADMIN-only staff route for an admin session', async () => {
    vi.stubGlobal('fetch', sessionFetch('ADMIN'))
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/admin/staff']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(await screen.findByRole('heading', { name: '직원' })).toBeVisible()
    expect(
      screen.getByRole('navigation', { name: '관리자 설정 메뉴' }),
    ).toBeVisible()
  })

  it('denies an agent from an ADMIN-only route before an admin operation is queried', async () => {
    const fetchMock = sessionFetch('AGENT')
    vi.stubGlobal('fetch', fetchMock)
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/admin/operations/mail']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: '관리자 운영 권한이 필요합니다.',
      }),
    ).toBeVisible()
    expect(
      fetchMock.mock.calls.some(([input]) =>
        String(input).includes('/api/v1/admin/mail'),
      ),
    ).toBe(false)
  })
})

it.each(['/agent/audit/typo', '/agent/unknown'])(
  'renders auditor not-found within a single shell main: %s',
  async (path) => {
    vi.stubGlobal('fetch', sessionFetch('SECURITY_AUDITOR'))
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={[path]}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )
    expect(
      await screen.findByRole('heading', {
        name: '페이지를 찾을 수 없습니다.',
      }),
    ).toBeVisible()
    expect(screen.getAllByRole('main')).toHaveLength(1)
    expect(document.querySelector('main main')).toBeNull()
    expect(
      screen.queryByText('상담사 작업 공간 권한이 필요합니다.'),
    ).not.toBeInTheDocument()
  },
)

it.each([503, 'network', 401])(
  'switches accounts only after confirmed logout, retaining denial on %s',
  async (failure) => {
    const baseFetch = sessionFetch('SECURITY_AUDITOR')
    let attempts = 0
    const fetcher = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        if (path === '/api/v1/agent/csrf')
          return new Response(
            JSON.stringify({
              token: 'synthetic-csrf',
              headerName: 'X-CSRF-TOKEN',
            }),
            { status: 200 },
          )
        if (path === '/api/v1/agent/session' && init?.method === 'DELETE') {
          attempts++
          if (attempts === 1 && failure === 'network')
            throw new TypeError('offline')
          return new Response(null, {
            status: attempts === 1 ? (failure as number) : 204,
          })
        }
        return baseFetch(input)
      },
    )
    vi.stubGlobal('fetch', fetcher)
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/agent/views/my-open']}>
          <TestApp />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )
    const user = userEvent.setup()
    await user.click(
      await screen.findByRole('button', { name: '다른 계정으로 로그인' }),
    )
    if (failure !== 401) {
      expect(
        await screen.findByText('로그아웃하지 못했습니다. 다시 시도해 주세요.'),
      ).toBeVisible()
      expect(
        screen.queryByRole('heading', { name: 'Deskseed 로그인' }),
      ).not.toBeInTheDocument()
      await user.click(
        screen.getByRole('button', { name: '다른 계정으로 로그인' }),
      )
    }
    expect(
      await screen.findByRole('heading', { name: 'Deskseed 로그인' }),
    ).toBeVisible()
    expect(attempts).toBe(failure === 401 ? 1 : 2)
  },
)
