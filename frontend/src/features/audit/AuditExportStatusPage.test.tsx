import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AuditExportStatusPage } from './AuditExportStatusPage'

const jobId = '11111111-1111-4111-8111-111111111111'

const job = {
  id: jobId,
  status: 'REQUESTED',
  createdAt: '2026-08-14T09:00:00Z',
  format: 'CSV',
  fields: ['occurredAt', 'action'],
  artifact: { state: 'NOT_CREATED', generationAvailable: false },
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

function mockAuditExportStatusApi({
  status = 200,
  requestId,
}: { status?: number; requestId?: string } = {}) {
  return vi.fn(() => {
    if (status !== 200) {
      return Promise.resolve(
        jsonResponse({ title: 'problem', status, requestId }, status),
      )
    }
    return Promise.resolve(jsonResponse(job))
  })
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/agent/audit/exports/${jobId}`]}>
        <Routes>
          <Route
            path="/agent/audit/exports/:jobId"
            element={<AuditExportStatusPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('AuditExportStatusPage', () => {
  it('shows a loading state before the job resolves', () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise(() => {})),
    )

    renderPage()

    expect(
      screen.getByText('내보내기 작업 정보를 불러오고 있습니다.'),
    ).toBeVisible()
  })

  it('renders the export job summary once it resolves', async () => {
    vi.stubGlobal('fetch', mockAuditExportStatusApi())

    renderPage()

    expect(await screen.findByText(jobId)).toBeVisible()
    expect(screen.getByText('occurredAt, action')).toBeVisible()
    expect(screen.getByText('생성 중…')).toBeVisible()
  })

  it('shows a not-found state for a 404 response', async () => {
    vi.stubGlobal('fetch', mockAuditExportStatusApi({ status: 404 }))

    renderPage()

    expect(
      await screen.findByText('내보내기 작업을 찾을 수 없습니다.'),
    ).toBeVisible()
  })

  it('shows a denied state for a 403 response', async () => {
    vi.stubGlobal('fetch', mockAuditExportStatusApi({ status: 403 }))

    renderPage()

    expect(
      await screen.findByText('내보내기 작업에 접근할 수 없습니다.'),
    ).toBeVisible()
  })

  it('shows an error state with retry for other failures', async () => {
    const user = userEvent.setup()
    const fetchMock = mockAuditExportStatusApi({
      status: 503,
      requestId: 'req-503',
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    expect(
      await screen.findByText('내보내기 작업 상태를 불러오지 못했습니다.'),
    ).toBeVisible()
    expect(screen.getByText(/req-503/)).toBeVisible()

    const callsBefore = fetchMock.mock.calls.length
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(fetchMock.mock.calls.length).toBeGreaterThan(callsBefore)
  })
})
