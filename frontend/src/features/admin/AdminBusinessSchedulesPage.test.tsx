import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BusinessSchedule } from '../../api/types'
import { DeskseedThemeProvider } from '../../design-system'
import { AdminBusinessSchedulesPage } from './AdminBusinessSchedulesPage'

const schedule: BusinessSchedule = {
  id: '11111111-1111-4111-8111-111111111111',
  name: '한국 고객지원 운영시간',
  timeZone: 'Asia/Seoul',
  weekdays: [
    {
      weekday: 'MONDAY',
      enabled: true,
      intervals: [{ start: '09:00', end: '18:00' }],
    },
    {
      weekday: 'TUESDAY',
      enabled: true,
      intervals: [{ start: '09:00', end: '18:00' }],
    },
    {
      weekday: 'WEDNESDAY',
      enabled: true,
      intervals: [{ start: '09:00', end: '18:00' }],
    },
    {
      weekday: 'THURSDAY',
      enabled: true,
      intervals: [{ start: '09:00', end: '18:00' }],
    },
    {
      weekday: 'FRIDAY',
      enabled: true,
      intervals: [{ start: '09:00', end: '18:00' }],
    },
    { weekday: 'SATURDAY', enabled: false, intervals: [] },
    { weekday: 'SUNDAY', enabled: false, intervals: [] },
  ],
  exceptions: [],
  version: 1,
  activeVersion: null,
  activeTimeZone: null,
  aggregateVersion: 2,
  active: false,
  createdAt: '2026-08-15T09:00:00Z',
  createdBy: {
    actorType: 'STAFF',
    actorId: '22222222-2222-4222-8222-222222222222',
    displayName: '운영 관리자',
  },
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <DeskseedThemeProvider>
      <QueryClientProvider client={queryClient}>
        <AdminBusinessSchedulesPage />
      </QueryClientProvider>
    </DeskseedThemeProvider>,
  )
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe('AdminBusinessSchedulesPage', () => {
  it('blocks resubmission and refreshes schedule and version reads after an ambiguous version POST', async () => {
    const user = userEvent.setup()
    let scheduleReads = 0
    let versionReads = 0
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), 'http://deskseed.test').pathname
        if (path === '/api/v1/admin/business-schedules') {
          scheduleReads += 1
          return json([schedule])
        }
        if (
          path === `/api/v1/admin/business-schedules/${schedule.id}/versions`
        ) {
          if (init?.method === 'POST') return json({ status: 503 }, 503)
          versionReads += 1
          return json([schedule])
        }
        if (path === '/api/v1/agent/csrf') {
          return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
        }
        throw new Error(`Unexpected request: ${path}`)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    await user.click(await screen.findByRole('button', { name: '시간표 관리' }))
    await user.click(
      await screen.findByRole('button', { name: '새 version 작성' }),
    )
    const name = await screen.findByLabelText('시간표 이름')
    await user.click(screen.getByRole('button', { name: '새 version 저장' }))

    expect(
      await screen.findByText('시간표 저장 결과를 확인할 수 없습니다.'),
    ).toBeVisible()
    expect(name).toHaveValue(schedule.name)
    expect(
      screen.getByRole('button', { name: '새 version 저장' }),
    ).toBeDisabled()
    await waitFor(() => {
      expect(scheduleReads).toBeGreaterThanOrEqual(2)
      expect(versionReads).toBeGreaterThanOrEqual(2)
    })
    await user.click(screen.getByRole('button', { name: '작성 닫기' }))
    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: '새 version 작성' }),
      ).toBeEnabled()
    })
  })
})
