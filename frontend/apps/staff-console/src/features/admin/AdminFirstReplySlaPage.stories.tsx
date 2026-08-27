import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
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
  parameters: {
    docs: {
      description: {
        component:
          'REQ-SLA-001/003/SLA-009 First Reply SLA 운영 route입니다. 실제 schedule/group projection을 조건 option으로 사용하고 policy version/activation/preview/analytics를 API에 연결합니다.',
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
    await expect(
      await canvas.findByText('등록된 First Reply SLA 정책이 없습니다.'),
    ).toBeVisible()
  },
}
