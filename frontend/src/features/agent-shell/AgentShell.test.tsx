import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DeskseedThemeProvider } from '../../shared/ui/DeskseedThemeProvider'
import { StaffSessionProvider } from '../staff-auth/StaffSessionContext'
import { AgentShell } from './AgentShell'

const staff = {
  id: 'agent-id',
  email: 'agent@example.com',
  displayName: '상담사',
  role: 'AGENT',
  capabilities: ['AGENT_WORKSPACE'],
}

const views = [
  {
    key: 'my-open',
    name: '내 open',
    scope: 'SYSTEM',
    categoryPath: ['Views'],
    ticketCount: 4,
    readScope: 'ALL_TICKETS',
  },
  {
    key: 'pending',
    name: 'Pending',
    scope: 'SYSTEM',
    categoryPath: ['Views'],
    ticketCount: 2,
    readScope: 'ALL_TICKETS',
  },
]

function renderShell() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <DeskseedThemeProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/agent/views/my-open']}>
          <StaffSessionProvider>
            <Routes>
              <Route path="/agent" element={<AgentShell />}>
                <Route
                  path="views/:viewKey"
                  element={<main>View content</main>}
                />
              </Route>
            </Routes>
          </StaffSessionProvider>
        </MemoryRouter>
      </QueryClientProvider>
    </DeskseedThemeProvider>,
  )
}

describe('AgentShell', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('renders fetched Views in the desktop frame and exposes keyboard focusable navigation', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((url: string) =>
        Promise.resolve(
          new Response(
            JSON.stringify(url.endsWith('/api/v1/agent/me') ? staff : views),
            {
              status: 200,
              headers: { 'Content-Type': 'application/json' },
            },
          ),
        ),
      ),
    )

    renderShell()

    expect(await screen.findByText('View content')).toBeVisible()
    expect(
      await screen.findByRole('link', { name: '내 open, 티켓 4개' }),
    ).toBeVisible()
    expect(
      screen.getByRole('link', { name: 'Pending, 티켓 2개' }),
    ).toBeVisible()

    await user.tab()
    expect(
      screen.getByRole('link', { name: '상담사 작업 내용으로 건너뛰기' }),
    ).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('link', { name: 'Deskseed Views' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('link', { name: 'Views' })).toHaveFocus()
    expect(
      screen.queryByRole('link', { name: '관리자 설정' }),
    ).not.toBeInTheDocument()
  })

  it('persists work navigation collapse per staff account', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((url: string) =>
        Promise.resolve(
          new Response(
            JSON.stringify(url.endsWith('/api/v1/agent/me') ? staff : views),
            {
              status: 200,
              headers: { 'Content-Type': 'application/json' },
            },
          ),
        ),
      ),
    )

    renderShell()
    await screen.findByRole('link', { name: '내 open, 티켓 4개' })
    await user.click(screen.getByRole('button', { name: '작업 탐색 접기' }))

    expect(
      screen.getByRole('button', { name: '작업 탐색 펼치기' }),
    ).toBeVisible()
    expect(
      localStorage.getItem('deskseed:agent:agent-id:work-nav-collapsed:v1'),
    ).toBe('true')
  })
})
