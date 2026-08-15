import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect, userEvent, waitFor } from 'storybook/test'
import { AdminBusinessSchedulesPage } from './AdminBusinessSchedulesPage'

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

const handlers = [
  http.get('/api/v1/admin/business-schedules', () =>
    HttpResponse.json([schedule]),
  ),
  http.get(`/api/v1/admin/business-schedules/${schedule.id}/versions`, () =>
    HttpResponse.json([schedule]),
  ),
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.post(`/api/v1/admin/business-schedules/${schedule.id}/versions`, () =>
    HttpResponse.json(
      { ...schedule, version: 2, aggregateVersion: 3 },
      { status: 201 },
    ),
  ),
  http.put(
    `/api/v1/admin/business-schedules/${schedule.id}/versions/1/activation`,
    () =>
      HttpResponse.json({
        ...schedule,
        active: true,
        activeVersion: 1,
        activeTimeZone: 'Asia/Seoul',
        aggregateVersion: 3,
      }),
  ),
  http.post('/api/v1/admin/business-schedules', () =>
    HttpResponse.json(schedule, { status: 201 }),
  ),
  http.post('/api/v1/admin/business-schedules/preview', () =>
    HttpResponse.json({
      dueAt: '2026-08-18T01:00:00Z',
      elapsedBusinessMinutes: 480,
      nextOpenAt: '2026-08-18T00:00:00Z',
      nextCloseAt: '2026-08-18T09:00:00Z',
      dstPolicy: 'GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH',
    }),
  ),
]

const meta = {
  title: '06 Admin/Admin Business Schedules Page',
  component: AdminBusinessSchedulesPage,
  parameters: {
    docs: {
      description: {
        component:
          'REQ-SLA-002/SCHED-001/SCHED-002 영업 시간표 운영 route입니다. 실제 versioned schedule API의 timezone, 다중 interval, exception, preview, If-Match activation 흐름을 제공합니다.',
      },
    },
    msw: { handlers },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AdminBusinessSchedulesPage>

export default meta
type Story = StoryObj<typeof meta>

export const VersionReviewAndEdit: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '영업 시간표' }),
    ).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '시간표 관리' }))
    await expect(
      await canvas.findByRole('heading', { name: '한국 고객지원 운영시간' }),
    ).toBeVisible()
    const newVersionButton = await canvas.findByRole('button', {
      name: '새 version 작성',
    })
    await waitFor(() => expect(newVersionButton).toBeEnabled())
    await userEvent.click(newVersionButton)
    await expect(
      canvas.getByRole('heading', {
        name: '한국 고객지원 운영시간 새 version',
      }),
    ).toBeVisible()
    const addButtons = canvas.getAllByRole('button', { name: '시간 구간 추가' })
    await userEvent.click(addButtons[0]!)
    await expect(
      canvas.getAllByRole('button', { name: '시간 구간 제거' }),
    ).toHaveLength(6)
  },
}

export const AmbiguousVersionSave: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/business-schedules', () =>
          HttpResponse.json([schedule]),
        ),
        http.get(
          `/api/v1/admin/business-schedules/${schedule.id}/versions`,
          () => HttpResponse.json([schedule]),
        ),
        http.get('/api/v1/agent/csrf', () =>
          HttpResponse.json({
            token: 'storybook-csrf',
            headerName: 'X-CSRF-TOKEN',
          }),
        ),
        http.post(
          `/api/v1/admin/business-schedules/${schedule.id}/versions`,
          () => HttpResponse.json({ status: 503 }, { status: 503 }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '시간표 관리' }),
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
      await canvas.findByText('시간표 저장 결과를 확인할 수 없습니다.'),
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
        http.get('/api/v1/admin/business-schedules', () =>
          HttpResponse.json([]),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('등록된 영업 시간표가 없습니다.'),
    ).toBeVisible()
  },
}
