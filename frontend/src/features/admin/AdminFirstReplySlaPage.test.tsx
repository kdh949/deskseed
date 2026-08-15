import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  BusinessSchedule,
  FirstReplySlaPolicy,
  SupportGroup,
} from '../../api/types'
import { DeskseedThemeProvider } from '../../design-system'
import { AdminFirstReplySlaPage } from './AdminFirstReplySlaPage'

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
  activeVersion: 1,
  activeTimeZone: 'Asia/Seoul',
  aggregateVersion: 2,
  active: true,
  createdAt: '2026-08-15T09:00:00Z',
  createdBy: {
    actorType: 'STAFF',
    actorId: '22222222-2222-4222-8222-222222222222',
    displayName: '운영 관리자',
  },
}

function groupId(index: number) {
  return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`
}

function createGroup(index: number): SupportGroup {
  return {
    id: groupId(index),
    name: `그룹 ${index}`,
    status: 'ACTIVE',
    memberCount: 0,
  }
}

function createPolicy(groupId: string): FirstReplySlaPolicy {
  return {
    id: '33333333-3333-4333-8333-333333333333',
    name: '결제 문의 First Reply SLA',
    position: 10,
    scheduleId: schedule.id,
    scheduleVersion: 1,
    conditions: { groupId, channel: 'WEB' },
    targets: { LOW: 480, NORMAL: 240, HIGH: 120, URGENT: 60 },
    pauseStatuses: ['PENDING'],
    version: 1,
    activeVersion: null,
    aggregateVersion: 2,
    active: false,
    createdAt: '2026-08-15T10:00:00Z',
    createdBy: {
      actorType: 'STAFF',
      actorId: '22222222-2222-4222-8222-222222222222',
      displayName: '운영 관리자',
    },
  }
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <DeskseedThemeProvider>
      <QueryClientProvider client={queryClient}>
        <AdminFirstReplySlaPage />
      </QueryClientProvider>
    </DeskseedThemeProvider>,
  )
}

function json(
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

function installSlaFetch({
  groups,
  policy,
  saveStatus,
}: {
  groups: SupportGroup[]
  policy: FirstReplySlaPolicy
  saveStatus?: number
}) {
  let policyReads = 0
  let versionReads = 0
  const fetchMock = vi.fn(
    async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), 'http://deskseed.test')
      if (url.pathname === '/api/v1/admin/sla-policies') {
        policyReads += 1
        return json([policy])
      }
      if (url.pathname === `/api/v1/admin/sla-policies/${policy.id}/versions`) {
        if (init?.method === 'POST')
          return json({ status: saveStatus }, saveStatus)
        versionReads += 1
        return json([policy])
      }
      if (url.pathname === '/api/v1/admin/business-schedules')
        return json([schedule])
      if (url.pathname === '/api/v1/admin/groups') {
        const page = Number(url.searchParams.get('page') ?? '0')
        const size = Number(url.searchParams.get('size') ?? '100')
        const totalPages =
          groups.length === 0 ? 0 : Math.ceil(groups.length / size)
        return json(groups.slice(page * size, (page + 1) * size), 200, {
          'X-Page-Number': String(page),
          'X-Page-Size': String(size),
          'X-Total-Count': String(groups.length),
          'X-Total-Pages': String(totalPages),
        })
      }
      if (url.pathname === '/api/v1/analytics/first-reply-sla') {
        return json({
          metric: 'FIRST_REPLY',
          calculationVersion: 'v1',
          active: 3,
          paused: 1,
          achieved: 20,
          breached: 2,
          cancelled: 0,
          noPolicy: 1,
          achievedRateDenominator: 22,
          achievedRate: 0.909,
        })
      }
      if (url.pathname === '/api/v1/agent/csrf') {
        return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      }
      throw new Error(`Unexpected request: ${url.pathname}`)
    },
  )
  vi.stubGlobal('fetch', fetchMock)
  return {
    fetchMock,
    policyReads: () => policyReads,
    versionReads: () => versionReads,
  }
}

async function openVersionEditor(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'SLA 정책 관리' }))
  await user.click(
    await screen.findByRole('button', { name: '새 version 작성' }),
  )
  return screen.findByLabelText('그룹 조건')
}

afterEach(() => vi.unstubAllGlobals())

describe('AdminFirstReplySlaPage', () => {
  it('loads group conditions beyond the first 100 groups', async () => {
    const user = userEvent.setup()
    const groups = Array.from({ length: 101 }, (_, index) =>
      createGroup(index + 1),
    )
    const finalGroup = groups.at(-1)!
    const { fetchMock } = installSlaFetch({
      groups,
      policy: createPolicy(finalGroup.id),
    })
    renderPage()

    const select = await openVersionEditor(user)

    expect(
      within(select).getByRole('option', { name: '그룹 101' }),
    ).toBeVisible()
    expect(select).toHaveValue(finalGroup.id)
    expect(
      fetchMock.mock.calls.some(([input]) =>
        String(input).includes('/api/v1/admin/groups?page=1&size=100'),
      ),
    ).toBe(true)
  })

  it('preserves an unavailable existing group condition as a disabled option', async () => {
    const user = userEvent.setup()
    const unavailableGroupId = '99999999-9999-4999-8999-999999999999'
    installSlaFetch({
      groups: [createGroup(1)],
      policy: createPolicy(unavailableGroupId),
    })
    renderPage()

    await openVersionEditor(user)

    const retained = await screen.findByRole('option', {
      name: `현재 조건: ${unavailableGroupId} (조회 불가)`,
    })
    expect(retained).toBeDisabled()
  })

  it('blocks duplicate SLA version submission and refreshes reads after an ambiguous POST', async () => {
    const user = userEvent.setup()
    const policy = createPolicy(groupId(1))
    const { policyReads, versionReads } = installSlaFetch({
      groups: [createGroup(1)],
      policy,
      saveStatus: 503,
    })
    renderPage()

    await openVersionEditor(user)
    const name = screen.getByLabelText('정책 이름')
    await user.click(screen.getByRole('button', { name: '새 version 저장' }))

    expect(
      await screen.findByText('SLA 정책 저장 결과를 확인할 수 없습니다.'),
    ).toBeVisible()
    expect(name).toHaveValue(policy.name)
    expect(
      screen.getByRole('button', { name: '새 version 저장' }),
    ).toBeDisabled()
    await waitFor(() => {
      expect(policyReads()).toBeGreaterThanOrEqual(2)
      expect(versionReads()).toBeGreaterThanOrEqual(2)
    })
    await user.click(screen.getByRole('button', { name: '작성 닫기' }))
    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: '새 version 작성' }),
      ).toBeEnabled()
    })
  })
})
