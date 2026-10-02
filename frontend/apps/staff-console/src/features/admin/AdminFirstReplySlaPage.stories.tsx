import type { Meta, StoryObj } from '@storybook/react-vite'
import MockDate from 'mockdate'
import { delay, http, HttpResponse } from 'msw'
import { expect, userEvent, waitFor } from 'storybook/test'
import { AdminFirstReplySlaPage } from './AdminFirstReplySlaPage'

const schedule = {
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

const group = {
  id: '33333333-3333-4333-8333-333333333333',
  name: '결제 지원',
  status: 'ACTIVE',
  memberCount: 3,
}

const policy = {
  id: '44444444-4444-4444-8444-444444444444',
  name: '결제 문의 기본 First Reply SLA',
  position: 10,
  scheduleId: schedule.id,
  scheduleVersion: 1,
  conditions: { groupId: group.id, channel: 'WEB' },
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

const handlers = [
  http.get('/api/v1/admin/sla-policies', () => HttpResponse.json([policy])),
  http.get(`/api/v1/admin/sla-policies/${policy.id}/versions`, () =>
    HttpResponse.json([policy]),
  ),
  http.get('/api/v1/admin/business-schedules', () =>
    HttpResponse.json([schedule]),
  ),
  http.get('/api/v1/admin/groups', () => HttpResponse.json([group])),
  http.get('/api/v1/analytics/first-reply-sla', () =>
    HttpResponse.json({
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
    }),
  ),
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.post(`/api/v1/admin/sla-policies/${policy.id}/versions`, () =>
    HttpResponse.json(
      { ...policy, version: 2, aggregateVersion: 3 },
      { status: 201 },
    ),
  ),
  http.put(
    `/api/v1/admin/sla-policies/${policy.id}/versions/1/activation`,
    () =>
      HttpResponse.json({
        ...policy,
        active: true,
        activeVersion: 1,
        aggregateVersion: 3,
      }),
  ),
  http.post('/api/v1/admin/sla-policies', () =>
    HttpResponse.json(policy, { status: 201 }),
  ),
  http.post('/api/v1/admin/sla-policies/preview', () =>
    HttpResponse.json({
      matched: true,
      dueAt: '2026-08-15T14:00:00Z',
      targetMinutes: 240,
      policyId: policy.id,
      policyVersion: 1,
      scheduleId: schedule.id,
      scheduleVersion: 1,
      dstPolicy: 'GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH',
    }),
  ),
]

const meta = {
  title: '06 Admin/Admin First Reply SLA Page',
  component: AdminFirstReplySlaPage,
  beforeEach: () => {
    MockDate.set('2026-10-03T03:00:00Z')
    return () => MockDate.reset()
  },
  parameters: {
    docs: {
      description: {
        component:
          'REQ-SLA-001/003/SLA-008/SLA-009 First Reply SLA 운영 route입니다. 성과는 기간을 적용하지 않은 티켓별 현재 상태이며, 정책/우선순위 필터는 정책 편집 대상과 독립적입니다. 달성률 분모는 달성+위반이고 조회 시각은 클라이언트의 마지막 성공 조회 시각입니다.',
      },
    },
    msw: { handlers },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AdminFirstReplySlaPage>

export default meta
type Story = StoryObj<typeof meta>

export const VersionReviewAndEdit: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: 'First Reply SLA' }),
    ).toBeVisible()
    await expect(await canvas.findByText('20')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: 'SLA 정책 관리' }))
    await expect(
      await canvas.findByRole('heading', {
        name: '결제 문의 기본 First Reply SLA',
      }),
    ).toBeVisible()
    const newVersionButton = await canvas.findByRole('button', {
      name: '새 version 작성',
    })
    await waitFor(() => expect(newVersionButton).toBeEnabled())
    await userEvent.click(newVersionButton)
    await expect(
      canvas.getByRole('heading', {
        name: '결제 문의 기본 First Reply SLA 새 version',
      }),
    ).toBeVisible()
    await expect(
      canvas.getByRole('checkbox', { name: '고객 답변 대기' }),
    ).toBeChecked()
  },
}

export const AmbiguousVersionSave: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/sla-policies', () =>
          HttpResponse.json([policy]),
        ),
        http.get(`/api/v1/admin/sla-policies/${policy.id}/versions`, () =>
          HttpResponse.json([policy]),
        ),
        http.get('/api/v1/admin/business-schedules', () =>
          HttpResponse.json([schedule]),
        ),
        http.get('/api/v1/admin/groups', () => HttpResponse.json([group])),
        http.get('/api/v1/analytics/first-reply-sla', () =>
          HttpResponse.json({
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
          }),
        ),
        http.get('/api/v1/agent/csrf', () =>
          HttpResponse.json({
            token: 'storybook-csrf',
            headerName: 'X-CSRF-TOKEN',
          }),
        ),
        http.post(`/api/v1/admin/sla-policies/${policy.id}/versions`, () =>
          HttpResponse.json({ status: 503 }, { status: 503 }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: 'SLA 정책 관리' }),
    )
    const newVersionButton = await canvas.findByRole('button', {
      name: '새 version 작성',
    })
    await waitFor(() => expect(newVersionButton).toBeEnabled())
    await userEvent.click(newVersionButton)
    await userEvent.click(
      canvas.getByRole('button', { name: '새 version 저장' }),
    )
    await expect(
      await canvas.findByText('SLA 정책 저장 결과를 확인할 수 없습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '새 version 저장' }),
    ).toBeDisabled()
  },
}

export const Empty: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/sla-policies', () => HttpResponse.json([])),
        http.get('/api/v1/admin/business-schedules', () =>
          HttpResponse.json([schedule]),
        ),
        http.get('/api/v1/admin/groups', () => HttpResponse.json([group])),
        http.get('/api/v1/analytics/first-reply-sla', () =>
          HttpResponse.json({
            metric: 'FIRST_REPLY',
            calculationVersion: 'v1',
            active: 0,
            paused: 0,
            achieved: 0,
            breached: 0,
            cancelled: 0,
            noPolicy: 0,
            achievedRateDenominator: 0,
            achievedRate: null,
          }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('집계 대상 없음')).toBeVisible()
    await expect(canvas.queryByText('0%')).not.toBeInTheDocument()
    await expect(
      await canvas.findByText('등록된 First Reply SLA 정책이 없습니다.'),
    ).toBeVisible()
  },
}

export const AnalyticsFilters: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/analytics/first-reply-sla', ({ request }) => {
          const params = new URL(request.url).searchParams
          const filtered =
            params.get('policyId') === policy.id &&
            params.get('priority') === 'HIGH'
          return HttpResponse.json({
            metric: 'FIRST_REPLY',
            calculationVersion: 'v1',
            active: 3,
            paused: 1,
            achieved: filtered ? 8 : 20,
            breached: 2,
            cancelled: 7,
            noPolicy: 1,
            achievedRateDenominator: filtered ? 10 : 22,
            achievedRate: filtered ? 0.8 : 0.909,
          })
        }),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('22건')).toBeVisible()
    await userEvent.selectOptions(canvas.getByLabelText('집계 정책'), policy.id)
    await userEvent.tab()
    await expect(canvas.getByLabelText('집계 우선순위')).toHaveFocus()
    await userEvent.selectOptions(
      canvas.getByLabelText('집계 우선순위'),
      'HIGH',
    )
    await expect(await canvas.findByText('80%')).toBeVisible()
    await expect(canvas.getByText('10건')).toBeVisible()
    await expect(canvas.getByText('계산 버전: v1')).toBeVisible()
  },
}

export const AnalyticsLoading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/analytics/first-reply-sla', async () => {
          await delay('infinite')
          return HttpResponse.json({})
        }),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('First Reply SLA 성과를 불러오는 중'),
    ).toBeVisible()
    await expect(canvas.getByLabelText('집계 정책')).toBeEnabled()
  },
}

export const AnalyticsError: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/analytics/first-reply-sla', () =>
          HttpResponse.json({ status: 503 }, { status: 503 }),
        ),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('First Reply SLA 성과를 불러오지 못했습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '다시 시도' }),
    ).toBeEnabled()
    await expect(
      canvas.getByRole('button', { name: 'SLA 정책 관리' }),
    ).toBeEnabled()
  },
}

export const AnalyticsDenied: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/analytics/first-reply-sla', () =>
          HttpResponse.json({ status: 403 }, { status: 403 }),
        ),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('SLA 성과 조회 권한이 없습니다.'),
    ).toBeVisible()
    await expect(
      canvas.queryByRole('button', { name: '다시 시도' }),
    ).not.toBeInTheDocument()
  },
}
