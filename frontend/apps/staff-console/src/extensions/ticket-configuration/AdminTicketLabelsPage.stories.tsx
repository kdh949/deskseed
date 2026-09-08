import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse, delay } from 'msw'
import { expect, userEvent, waitFor } from 'storybook/test'
import { AdminTicketLabelsPage } from './AdminTicketLabelsPage'
const tag = {
  id: '22222222-2222-4222-8222-222222222222',
  value: 'refund',
  label: '환불 요청',
  active: true,
  version: 1,
  highCardinalityWarning: false,
}
const status = {
  id: '33333333-3333-4333-8333-333333333333',
  machineKey: 'waiting-customer',
  agentLabel: '고객 확인 대기',
  customerLabel: '추가 정보 필요',
  statusCategory: 'PENDING',
  active: true,
  order: 0,
  defaultForCategory: false,
  allowedFormIds: [],
  description: null,
  version: 1,
}
let body: unknown = null
const handlers = [
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'story-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.get('/api/v1/admin/ticket-tags', () => HttpResponse.json([tag])),
  http.get('/api/v1/admin/ticket-statuses', () => HttpResponse.json([status])),
  http.get('/api/v1/admin/ticket-forms', () => HttpResponse.json([])),
]
const meta = {
  title: '07 Screens/Admin Ticket Labels',
  component: AdminTicketLabelsPage,
  args: { kind: 'tags' },
  beforeEach: () => {
    body = null
  },
  parameters: { msw: { handlers } },
} satisfies Meta<typeof AdminTicketLabelsPage>
export default meta
type Story = StoryObj<typeof meta>
export const CreateTag: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post('/api/v1/admin/ticket-tags', async ({ request }) => {
          body = await request.json()
          return HttpResponse.json(
            { ...tag, ...(body as object) },
            { status: 201 },
          )
        }),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '새 태그' }))
    const drawer = canvas
    await userEvent.type(drawer.getByLabelText(/식별 이름/), 'vip')
    await userEvent.type(drawer.getByLabelText(/상담사 표시 이름/), '우선 지원')
    await userEvent.click(drawer.getByRole('button', { name: '설정 저장' }))
    await waitFor(() =>
      expect(body).toEqual({ value: 'vip', label: '우선 지원', active: true }),
    )
    await waitFor(() =>
      expect(drawer.queryByRole('dialog')).not.toBeInTheDocument(),
    )
  },
}
export const EditStatus: Story = {
  args: { kind: 'statuses' },
  parameters: {
    msw: {
      handlers: [
        http.put('/api/v1/admin/ticket-statuses/:id', async ({ request }) => {
          expect(request.headers.get('If-Match')).toBe('"1"')
          body = await request.json()
          return HttpResponse.json({
            ...status,
            ...(body as object),
            version: 2,
          })
        }),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '고객 확인 대기 편집' }),
    )
    const drawer = canvas
    await expect(drawer.getByLabelText(/식별 이름/)).toBeDisabled()
    await userEvent.clear(drawer.getByLabelText(/고객 표시 이름/))
    await userEvent.type(
      drawer.getByLabelText(/고객 표시 이름/),
      '답변을 기다립니다',
    )
    await userEvent.click(drawer.getByRole('button', { name: '설정 저장' }))
    await waitFor(() =>
      expect(body).toMatchObject({
        customerLabel: '답변을 기다립니다',
        statusCategory: 'PENDING',
        allowedFormIds: [],
      }),
    )
  },
}
export const CreateOnHoldStatus: Story = {
  args: { kind: 'statuses' },
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/ticket-statuses', () =>
          HttpResponse.json([{ ...status, statusCategory: 'ON_HOLD' }]),
        ),
        http.post('/api/v1/admin/ticket-statuses', async ({ request }) => {
          body = await request.json()
          return HttpResponse.json(
            { ...status, ...(body as object) },
            { status: 201 },
          )
        }),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('button', { name: '고객 확인 대기 편집' }),
    ).toBeVisible()
    await expect(canvas.getByText('보류 · 사용 중')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '새 상태' }))
    await userEvent.type(canvas.getByLabelText(/식별 이름/), 'waiting-team')
    await userEvent.type(
      canvas.getByLabelText(/상담사 표시 이름/),
      '협업 회신 대기',
    )
    await userEvent.selectOptions(
      canvas.getByLabelText('기본 처리 단계'),
      'ON_HOLD',
    )
    await userEvent.click(canvas.getByRole('button', { name: '설정 저장' }))
    await waitFor(() =>
      expect(body).toMatchObject({ statusCategory: 'ON_HOLD' }),
    )
    await waitFor(() =>
      expect(canvas.queryByRole('dialog')).not.toBeInTheDocument(),
    )
  },
}
export const ConflictKeepsDraft: Story = {
  parameters: {
    msw: {
      handlers: [
        http.put('/api/v1/admin/ticket-tags/:id', () =>
          HttpResponse.json({ status: 412 }, { status: 412 }),
        ),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '환불 요청 편집' }),
    )
    const drawer = canvas
    await userEvent.type(drawer.getByLabelText(/상담사 표시 이름/), ' 확인')
    await userEvent.click(drawer.getByRole('button', { name: '설정 저장' }))
    await drawer.findByText(/다른 변경과 충돌/)
    await expect(drawer.getByLabelText(/상담사 표시 이름/)).toHaveValue(
      '환불 요청 확인',
    )
    await expect(
      drawer.getByRole('button', { name: '설정 저장' }),
    ).toBeDisabled()
  },
}
export const UnknownCreateRequiresListReview: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post('/api/v1/admin/ticket-tags', () => HttpResponse.error()),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '새 태그' }))
    const drawer = canvas
    await userEvent.type(drawer.getByLabelText(/식별 이름/), 'refund')
    await userEvent.type(drawer.getByLabelText(/상담사 표시 이름/), '환불')
    await userEvent.click(drawer.getByRole('button', { name: '설정 저장' }))
    await drawer.findByText(/저장 결과를 확인하지/)
    await expect(
      drawer.getByRole('button', { name: '설정 저장' }),
    ).toBeDisabled()
  },
}
export const Empty: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/ticket-tags', () => HttpResponse.json([])),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await canvas.findByText('등록된 티켓 태그가 없습니다.')
  },
}
export const Denied: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/ticket-tags', () =>
          HttpResponse.json({ status: 403 }, { status: 403 }),
        ),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await canvas.findByText('관리 권한이 없습니다.')
  },
}
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/ticket-tags', async () => {
          await delay('infinite')
          return HttpResponse.json([])
        }),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await canvas.findByText('목록을 불러오는 중…')
  },
}
export const Error: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/ticket-tags', () =>
          HttpResponse.json({ status: 503 }, { status: 503 }),
        ),
        ...handlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await canvas.findByText('목록을 불러오지 못했습니다.')
  },
}
