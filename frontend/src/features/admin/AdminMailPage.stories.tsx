import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect, userEvent } from 'storybook/test'
import { AdminMailPage } from './AdminMailPage'

const intent = {
  id: '11111111-1111-4111-8111-111111111111',
  template: 'REQUEST_RECEIVED',
  templateVersion: 1,
  status: 'FAILED',
  recipientMasked: '***@example.test',
  attemptCount: 3,
  maxAttempts: 3,
  retryCycle: 0,
  manualRetryCount: 0,
  nextAttemptAt: null,
  leaseExpiresAt: null,
  lastErrorCode: 'MAIL_DELIVERY_FAILURE',
  queuedAt: '2026-08-15T10:00:00Z',
  sentAt: null,
  failedAt: '2026-08-15T10:03:00Z',
  attempts: [
    {
      attemptNumber: 3,
      retryCycle: 0,
      cycleAttemptNumber: 3,
      status: 'PERMANENT_FAILED',
      failureClass: 'PERMANENT',
      failureCode: 'MAIL_DELIVERY_FAILURE',
      startedAt: '2026-08-15T10:02:00Z',
      finishedAt: '2026-08-15T10:03:00Z',
      nextRetryAt: null,
    },
  ],
}

const summary = {
  deliveryEnabled: true,
  schedulingEnabled: true,
  transport: 'SMTP',
  queuedCount: 0,
  sendingCount: 0,
  retryWaitCount: 0,
  failedCount: 1,
  sentCount: 12,
  oldestPendingAt: null,
}

const readyHandlers = [
  http.get('/api/v1/admin/mail/summary', () => HttpResponse.json(summary)),
  http.get('/api/v1/admin/mail/intents', () =>
    HttpResponse.json({ items: [intent], nextCursor: null }),
  ),
  http.get(`/api/v1/admin/mail/intents/${intent.id}`, () =>
    HttpResponse.json(intent),
  ),
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.post(`/api/v1/admin/mail/intents/${intent.id}/retry`, () =>
    HttpResponse.json({ ...intent, status: 'QUEUED', manualRetryCount: 1 }),
  ),
]

const meta = {
  title: '06 Admin/Admin Mail Page',
  component: AdminMailPage,
  parameters: {
    docs: {
      description: {
        component:
          'REQ-NOTIF-001/REQ-CHAN-003 관리자 메일 운영 route입니다. 마스킹된 projection만 소비하며, FAILED intent는 CSRF가 필요한 사유 기반 재시도로 같은 intent를 재큐잉합니다.',
      },
    },
    msw: { handlers: readyHandlers },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AdminMailPage>

export default meta
type Story = StoryObj<typeof meta>

export const FailedIntentRetry: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '메일 운영' }),
    ).toBeVisible()
    await expect(canvas.getByText('***@example.test')).toBeVisible()
    await userEvent.click(
      canvas.getByRole('button', { name: '운영 상세 보기' }),
    )
    await userEvent.click(
      await canvas.findByRole('button', { name: '실패 메일 재시도' }),
    )
    await userEvent.type(
      canvas.getByRole('textbox', { name: '재시도 사유' }),
      '주소 정정 후 재시도',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '사유와 함께 재시도' }),
    )
    await expect(
      await canvas.findByText('메일 재시도 요청을 등록했습니다.'),
    ).toBeVisible()
  },
}

export const LoadDenied: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/mail/summary', () =>
          HttpResponse.json({ status: 403 }, { status: 403 }),
        ),
        http.get('/api/v1/admin/mail/intents', () =>
          HttpResponse.json({ status: 403 }, { status: 403 }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('메일 운영 권한이 없습니다.'),
    ).toBeVisible()
  },
}
