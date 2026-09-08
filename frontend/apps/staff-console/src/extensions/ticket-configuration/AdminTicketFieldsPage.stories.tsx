import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse, delay } from 'msw'
import { expect, userEvent, waitFor } from 'storybook/test'
import { AdminTicketFieldsPage } from './AdminTicketFieldsPage'
import type { FieldDefinition } from './api'

const orderField: FieldDefinition = {
  id: '11111111-1111-4111-8111-111111111111',
  version: 1,
  active: true,
  machineKey: 'order.number',
  type: 'SHORT_TEXT',
  staffLabel: '주문번호',
  customerLabel: '주문번호',
  customerVisible: true,
  customerEditable: true,
  agentVisible: true,
  agentEditable: true,
  searchable: true,
  analyticsEligible: false,
  sensitive: false,
}
const baseHandlers = [
  http.get('/api/v1/admin/ticket-fields', () =>
    HttpResponse.json([orderField]),
  ),
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({
      token: 'storybook-csrf',
      headerName: 'X-CSRF-TOKEN',
    }),
  ),
]

const meta = {
  title: '07 Screens/Admin Ticket Fields',
  component: AdminTicketFieldsPage,
  parameters: {
    msw: {
      handlers: baseHandlers,
    },
  },
} satisfies Meta<typeof AdminTicketFieldsPage>
export default meta
type Story = StoryObj<typeof meta>

export const CreateField: Story = {
  parameters: {
    msw: {
      handlers: [
        ...baseHandlers,
        http.post('/api/v1/admin/ticket-fields', async ({ request }) => {
          const draft = (await request.json()) as Record<string, unknown>
          await expect(draft.machineKey).toBe('refund.reason')
          await expect(draft.customerEditable).toBe(true)
          await expect(request.headers.get('X-CSRF-TOKEN')).toBe(
            'storybook-csrf',
          )
          return HttpResponse.json({ ...orderField, ...draft }, { status: 201 })
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('주문번호')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '필드 만들기' }))
    await userEvent.type(canvas.getByLabelText(/식별자/), 'refund.reason')
    await userEvent.type(canvas.getByLabelText(/직원 표시 이름/), '환불 사유')
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '고객에게 표시 가능' }),
    )
    await userEvent.type(canvas.getByLabelText(/고객 표시 이름/), '환불 사유')
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '고객 입력 허용' }),
    )
    await userEvent.click(canvas.getByRole('button', { name: '필드 저장' }))
    await waitFor(() =>
      expect(
        canvas.queryByRole('form', { name: '필드 편집' }),
      ).not.toBeInTheDocument(),
    )
  },
}
export const ConflictPreservesInput: Story = {
  parameters: {
    msw: {
      handlers: [
        ...baseHandlers,
        http.put('/api/v1/admin/ticket-fields/:id', () =>
          HttpResponse.json(
            { title: '최신 버전을 확인하세요.', status: 412 },
            { status: 412 },
          ),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '편집: 주문번호' }),
    )
    await userEvent.clear(canvas.getByLabelText(/직원 표시 이름/))
    await userEvent.type(
      canvas.getByLabelText(/직원 표시 이름/),
      '환불 주문번호',
    )
    await userEvent.click(canvas.getByRole('button', { name: '필드 저장' }))
    await expect(
      await canvas.findByText(
        '다른 변경 사항이 있습니다. 입력을 확인해 주세요.',
      ),
    ).toBeVisible()
    await expect(canvas.getByLabelText(/직원 표시 이름/)).toHaveValue(
      '환불 주문번호',
    )
  },
}
export const Empty: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/ticket-fields', () => HttpResponse.json([])),
      ],
    },
  },
}

export const AddOption: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/ticket-fields', () =>
          HttpResponse.json([
            { ...orderField, type: 'SINGLE_SELECT', staffLabel: '문의 유형' },
          ]),
        ),
        http.get('/api/v1/admin/ticket-fields/:id/options', () =>
          HttpResponse.json([]),
        ),
        http.post(
          '/api/v1/admin/ticket-fields/:id/options',
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            await expect(body).toEqual({
              machineKey: 'refund',
              staffLabel: '환불',
              customerLabel: '환불',
              order: 0,
            })
            return HttpResponse.json(
              {
                ...body,
                id: '33333333-3333-4333-8333-333333333333',
                version: 1,
                active: true,
              },
              { status: 201 },
            )
          },
        ),
        ...baseHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '선택지: 문의 유형' }),
    )
    await userEvent.type(
      await canvas.findByLabelText(/선택지 식별자/),
      'refund',
    )
    await userEvent.type(canvas.getByLabelText(/선택지 직원 이름/), '환불')
    await userEvent.type(canvas.getByLabelText(/선택지 고객 이름/), '환불')
    await userEvent.click(canvas.getByRole('button', { name: '선택지 추가' }))
    await waitFor(() =>
      expect(canvas.getByLabelText(/선택지 식별자/)).toHaveValue(''),
    )
  },
}
export const Denied: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/ticket-fields', () =>
          HttpResponse.json({ status: 403 }, { status: 403 }),
        ),
      ],
    },
  },
}
export const Error: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/ticket-fields', () =>
          HttpResponse.json({ status: 503 }, { status: 503 }),
        ),
      ],
    },
  },
}
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/ticket-fields', async () => {
          await delay('infinite')
          return HttpResponse.json([])
        }),
      ],
    },
  },
}
