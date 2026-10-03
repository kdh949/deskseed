import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { DeskseedThemeProvider } from '../../design-system'
import { AdminGroupsPage } from './AdminGroupsPage'

const groupA = {
  id: '11111111-1111-4111-8111-111111111111',
  name: '결제 지원',
  status: 'ACTIVE',
  memberCount: 1,
}

const groupB = {
  id: '22222222-2222-4222-8222-222222222222',
  name: '배송 지원',
  status: 'ACTIVE',
  memberCount: 0,
}

const memberA = {
  groupId: groupA.id,
  staffId: '33333333-3333-4333-8333-333333333333',
  staffDisplayName: '상담사 A',
  role: 'AGENT',
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const router = createMemoryRouter([
    {
      path: '*',
      element: (
        <DeskseedThemeProvider>
          <QueryClientProvider client={queryClient}>
            <AdminGroupsPage />
          </QueryClientProvider>
        </DeskseedThemeProvider>
      ),
    },
  ])
  const view = render(<RouterProvider router={router} />)
  return { ...view, queryClient }
}

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe('AdminGroupsPage', () => {
  it('keeps query text out of cache keys and clears member search when switching groups', async () => {
    const user = userEvent.setup()
    const query = 'private-member@example.test'
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://deskseed.test').pathname
      if (path === '/api/v1/agent/csrf')
        return json({ token: 'csrf-directory', headerName: 'X-CSRF-TOKEN' })
      if (path === '/api/v1/admin/groups') return json([groupA, groupB])
      if (path.endsWith('/members')) return json([])
      if (path === '/api/v1/admin/staff/search')
        return json([
          {
            id: memberA.staffId,
            email: 'member@example.test',
            displayName: memberA.staffDisplayName,
            role: 'AGENT',
            status: 'ACTIVE',
            memberships: [],
            auditAuthorities: [],
            lastLoginAt: null,
          },
        ])
      throw new Error(`Unexpected request: ${path}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const { queryClient } = renderPage()
    await user.click(
      (await screen.findAllByRole('button', { name: '그룹 관리' }))[0]!,
    )
    await user.type(
      await screen.findByLabelText('구성원 이름 또는 이메일 검색'),
      `${query}{Enter}`,
    )
    await screen.findByText('검색된 구성원 1명')
    expect(
      JSON.stringify(
        queryClient
          .getQueryCache()
          .getAll()
          .map((entry) => entry.queryKey),
      ),
    ).not.toContain(query)
    await user.click(screen.getAllByRole('button', { name: '그룹 관리' })[1]!)
    expect(
      await screen.findByLabelText('구성원 이름 또는 이메일 검색'),
    ).toHaveValue('')
    expect(
      screen.getByLabelText('추가할 직원 이름 또는 이메일 검색'),
    ).toHaveValue('')
    expect(screen.queryByText(memberA.staffDisplayName)).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/search')),
    ).toHaveLength(1)
  })

  it('clears a removal candidate when an administrator switches groups', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://deskseed.test').pathname
      if (path === '/api/v1/admin/groups') return json([groupA, groupB])
      if (path === `/api/v1/admin/groups/${groupA.id}/members`)
        return json([memberA])
      if (path === `/api/v1/admin/groups/${groupB.id}/members`) return json([])
      if (path === '/api/v1/admin/staff') return json([])
      throw new Error(`Unexpected request: ${path}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const manageButtons = await screen.findAllByRole('button', {
      name: '그룹 관리',
    })
    await user.click(manageButtons[0]!)
    await user.click(await screen.findByRole('button', { name: '구성원 제거' }))

    expect(
      screen.getByRole('group', { name: '구성원 제거 최종 확인' }),
    ).toHaveTextContent('상담사 A을(를) 결제 지원 그룹에서 제거할까요?')

    await user.click(manageButtons[1]!)

    expect(
      await screen.findByRole('heading', { name: '배송 지원' }),
    ).toBeVisible()
    expect(
      screen.queryByRole('group', { name: '구성원 제거 최종 확인' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '구성원 제거 확정' }),
    ).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(([input]) =>
        new URL(String(input), 'http://deskseed.test').pathname.endsWith(
          `/members/${memberA.staffId}`,
        ),
      ),
    ).toBe(false)
  })
})
