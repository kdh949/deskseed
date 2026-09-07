import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect, userEvent, waitFor } from 'storybook/test'
import { AdminTicketFormsPage } from './AdminTicketFormsPage'
import type { FieldDefinition, TicketForm } from './api'

const field: FieldDefinition = {
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
const form: TicketForm = {
  id: '22222222-2222-4222-8222-222222222222',
  version: 1,
  lifecycle: 'DRAFT',
  name: '환불 문의',
  defaultForCustomer: true,
  defaultForAgent: true,
  placements: [
    {
      fieldId: field.id,
      order: 0,
      customer: { visible: true, editable: true, required: true },
      agent: { visible: true, editable: true, required: false },
    },
  ],
  conditionalRules: [],
  allowedCustomStatusIds: [],
}
const handlers = [
  http.get('/api/v1/admin/ticket-fields', () => HttpResponse.json([field])),
  http.get('/api/v1/admin/ticket-forms', () => HttpResponse.json([form])),
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
]
const meta = {
  title: '07 Screens/Admin Ticket Forms',
  component: AdminTicketFormsPage,
  parameters: { msw: { handlers } },
} satisfies Meta<typeof AdminTicketFormsPage>
export default meta
type Story = StoryObj<typeof meta>

export const CreateForm: Story = {
  parameters: {
    msw: {
      handlers: [
        ...handlers,
        http.post('/api/v1/admin/ticket-forms', async ({ request }) => {
          const body = (await request.json()) as TicketForm
          await expect(body.defaultForCustomer).toBe(true)
          await expect(body.placements[0]?.customer.required).toBe(true)
          return HttpResponse.json({ ...form, ...body }, { status: 201 })
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await waitFor(() =>
      expect(canvas.getByRole('button', { name: '폼 만들기' })).toBeEnabled(),
    )
    await userEvent.click(canvas.getByRole('button', { name: '폼 만들기' }))
    await userEvent.type(canvas.getByLabelText(/폼 이름/), '환불 문의')
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '고객 기본 폼' }),
    )
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '주문번호 포함' }),
    )
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '주문번호 고객 필수' }),
    )
    await userEvent.click(canvas.getByRole('button', { name: '폼 초안 저장' }))
    await waitFor(() =>
      expect(
        canvas.queryByRole('form', { name: '폼 편집' }),
      ).not.toBeInTheDocument(),
    )
  },
}
export const Publish: Story = {
  parameters: {
    msw: {
      handlers: [
        ...handlers,
        http.post(
          '/api/v1/admin/ticket-forms/:id/publish',
          async ({ request }) => {
            await expect(request.headers.get('If-Match')).toBe('"1"')
            return HttpResponse.json({
              ...form,
              lifecycle: 'PUBLISHED',
              version: 2,
            })
          },
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '발행: 환불 문의' }),
    )
    await expect(canvas.queryByRole('alert')).not.toBeInTheDocument()
  },
}
export const Conflict: Story = {
  parameters: {
    msw: {
      handlers: [
        ...handlers,
        http.put('/api/v1/admin/ticket-forms/:id', () =>
          HttpResponse.json({ status: 412 }, { status: 412 }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '편집: 환불 문의' }),
    )
    await userEvent.type(canvas.getByLabelText(/폼 이름/), ' 변경')
    await userEvent.click(canvas.getByRole('button', { name: '폼 초안 저장' }))
    await expect(
      await canvas.findByText(
        '다른 변경 사항이 있습니다. 입력을 확인해 주세요.',
      ),
    ).toBeVisible()
    await expect(canvas.getByLabelText(/폼 이름/)).toHaveValue('환불 문의 변경')
  },
}
export const Empty: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/ticket-forms', () => HttpResponse.json([])),
        ...handlers,
      ],
    },
  },
}
