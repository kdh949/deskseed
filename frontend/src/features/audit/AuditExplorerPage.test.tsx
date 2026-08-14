import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AuditExplorerPage } from './AuditExplorerPage'

const activity = {
  id: '11111111-1111-4111-8111-111111111111',
  ledger: 'TICKET_CHANGE',
  action: 'TICKET_PRIORITY_CHANGED',
  actor: { id: 'staff-1', type: 'STAFF', displayName: '이서연' },
  occurredAt: '2026-08-14T09:12:00Z',
  ticketNumber: 1050,
  groupId: null,
  field: 'priority',
  resourceType: null,
  resourceId: null,
  summary: '우선순위를 보통에서 높음으로 변경',
  source: 'AGENT_UI',
  outcome: 'SUCCEEDED',
  requestId: 'req-a1b2',
  correlationId: 'corr-a1b2',
  protectedContentAvailable: false,
  searchFingerprint: null,
}

const activityPage = {
  items: [activity],
  nextCursor: null,
  snapshotAt: '2026-08-14T09:20:00Z',
  projection: { state: 'CURRENT', projectedCount: 1, lastRebuiltAt: null },
}

const activityDetail = {
  ...activity,
  canonicalEventId: '99999999-9999-4999-8999-999999999999',
  canonicalParentId: null,
  fieldChange: { field: 'priority', before: 'NORMAL', after: 'HIGH' },
  interactionId: 'int-1',
  sessionFingerprint: null,
  authType: null,
  ipAddress: null,
  userAgent: null,
  search: null,
  metadata: {},
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type':
        status >= 400 ? 'application/problem+json' : 'application/json',
    },
  })
}

function mockAuditExplorerApi({
  activitiesStatus = 200,
  exportStatus = 202,
}: { activitiesStatus?: number; exportStatus?: number } = {}) {
  return vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (url.includes('/api/v1/audit/activities/') && method === 'GET') {
      return Promise.resolve(jsonResponse(activityDetail))
    }
    if (url.includes('/api/v1/audit/activities') && method === 'GET') {
      if (activitiesStatus !== 200) {
        return Promise.resolve(
          jsonResponse(
            { title: 'denied', status: activitiesStatus },
            activitiesStatus,
          ),
        )
      }
      return Promise.resolve(jsonResponse(activityPage))
    }
    if (url.endsWith('/api/v1/agent/csrf')) {
      return Promise.resolve(
        jsonResponse({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }),
      )
    }
    if (url.endsWith('/api/v1/audit/exports') && method === 'POST') {
      return Promise.resolve(
        jsonResponse(
          {
            id: '22222222-2222-4222-8222-222222222222',
            status: 'REQUESTED',
            createdAt: '2026-08-14T09:30:00Z',
            format: 'CSV',
            fields: ['occurredAt'],
            artifact: { state: 'NOT_CREATED', generationAvailable: false },
          },
          exportStatus,
        ),
      )
    }
    return Promise.resolve(jsonResponse({ title: 'Not found' }, 404))
  })
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/agent/audit']}>
        <Routes>
          <Route path="/agent/audit" element={<AuditExplorerPage />} />
          <Route
            path="/agent/audit/exports/:jobId"
            element={<p>내보내기 작업 열림</p>}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('AuditExplorerPage', () => {
  it('shows a loading state before activities resolve', () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise(() => {})),
    )

    renderPage()

    expect(screen.getByText('감사 활동 불러오는 중')).toBeVisible()
  })

  it('renders activities and opens the detail drawer on row click', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', mockAuditExplorerApi())

    renderPage()

    await screen.findByRole('link', {
      name: 'TICKET_PRIORITY_CHANGED 상세 보기',
    })
    await user.click(
      screen.getByRole('link', { name: 'TICKET_PRIORITY_CHANGED 상세 보기' }),
    )

    expect(
      await screen.findByText('99999999-9999-4999-8999-999999999999'),
    ).toBeVisible()
  })

  it('shows a denied state when the actor lacks audit read authority', async () => {
    vi.stubGlobal('fetch', mockAuditExplorerApi({ activitiesStatus: 403 }))

    renderPage()

    expect(
      await screen.findByText('감사 활동을 조회할 권한이 없습니다.'),
    ).toBeVisible()
  })

  it('lets the auditor request an export and navigates to its status page', async () => {
    const user = userEvent.setup()
    const fetchMock = mockAuditExplorerApi()
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await screen.findByRole('link', {
      name: 'TICKET_PRIORITY_CHANGED 상세 보기',
    })

    await user.click(screen.getByRole('button', { name: '내보내기' }))
    await user.click(await screen.findByLabelText('레저'))
    await user.type(screen.getByLabelText('사유'), '월간 점검')
    await user.click(screen.getByRole('button', { name: '내보내기 요청' }))

    await screen.findByText('내보내기 작업 열림')

    const exportCall = fetchMock.mock.calls.find(
      ([requestUrl, requestInit]) =>
        String(requestUrl).endsWith('/api/v1/audit/exports') &&
        (requestInit as RequestInit | undefined)?.method === 'POST',
    )
    expect(exportCall).toBeDefined()
    const body = JSON.parse(String(exportCall?.[1]?.body))
    expect(body).toMatchObject({ format: 'CSV', reason: '월간 점검' })
  })
})
