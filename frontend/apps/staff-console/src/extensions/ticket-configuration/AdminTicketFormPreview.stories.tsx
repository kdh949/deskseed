import type { Meta, StoryObj } from '@storybook/react-vite'
import { delay, http, HttpResponse } from 'msw'
import { expect, fn, userEvent, waitFor } from 'storybook/test'
import { AdminTicketFormPreview } from './AdminTicketFormPreview'
import type { FieldDefinition, FormPreviewRequest, TicketForm } from './api'
import './configuration.css'
const field: FieldDefinition = {
  id: '11111111-1111-4111-8111-111111111111',
  version: 1,
  active: true,
  machineKey: 'order.number',
  type: 'SHORT_TEXT',
  staffLabel: '내부 주문번호',
  customerLabel: '주문번호',
  customerVisible: true,
  customerEditable: true,
  agentVisible: true,
  agentEditable: true,
  searchable: false,
  analyticsEligible: false,
  sensitive: false,
}
const form: TicketForm = {
  id: '22222222-2222-4222-8222-222222222222',
  version: 1,
  lifecycle: 'DRAFT',
  name: '환불 문의',
  defaultForCustomer: false,
  defaultForAgent: true,
  placements: [],
  conditionalRules: [],
  allowedCustomStatusIds: [],
}
const base = [
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'story-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.get('/api/v1/admin/ticket-statuses', () => HttpResponse.json([])),
]
const projection = (required = false) => ({
  formId: form.id,
  formVersion: 1,
  fields: [
    { field, visible: true, editable: true, required, options: [] },
    {
      field: {
        ...field,
        id: 'staff',
        machineKey: 'staff.note',
        staffLabel: '직원 전용 평가',
        customerLabel: null,
        customerVisible: false,
      },
      visible: true,
      editable: true,
      required: false,
    },
    {
      field: {
        ...field,
        id: 'inactive',
        staffLabel: '사용 중지 필드',
        customerLabel: '사용 중지',
        active: false,
      },
      visible: true,
      editable: true,
      required: false,
    },
  ],
})
const requests = fn()
const dynamic = http.post(
  '/api/v1/admin/ticket-forms/:id/preview',
  async ({ request }) => {
    const body = (await request.json()) as FormPreviewRequest
    requests(body)
    return HttpResponse.json(
      projection(body.fieldValues['order.number']?.shortTextValue === '123'),
    )
  },
)
const meta = {
  title: '07 Screens/Admin Ticket Form Preview',
  component: AdminTicketFormPreview,
  args: { form, onClose: fn() },
  beforeEach: () => {
    requests.mockClear()
  },
  parameters: {
    msw: { handlers: [...base, dynamic] },
    docs: {
      description: {
        component:
          '저장된 폼 설정을 기존 ADMIN preview API로 평가한다. 조건 AST를 클라이언트에서 평가하지 않는다. 고객 보기는 active/visible/customer capability와 고객 표시 이름을 사용한다. 입력한 시험 값은 actor 전환시 폐기되고 서버에 티켓으로 저장되지 않는다.',
      },
    },
  },
} satisfies Meta<typeof AdminTicketFormPreview>
export default meta
type Story = StoryObj<typeof meta>
export const CustomerAndAgentConditions: Story = {
  play: async ({ canvas }) => {
    const input = await canvas.findByLabelText('주문번호')
    await expect(canvas.queryByText('직원 전용 평가')).not.toBeInTheDocument()
    await expect(canvas.queryByText('사용 중지')).not.toBeInTheDocument()
    await userEvent.type(input, '123')
    await expect(canvas.getByText('조건이 변경되었습니다.')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '조건 적용' }))
    await expect(await canvas.findByLabelText('주문번호 (필수)')).toHaveValue(
      '123',
    )
    await userEvent.selectOptions(canvas.getByLabelText('사용자 관점'), 'AGENT')
    await expect(await canvas.findByLabelText('직원 전용 평가')).toBeVisible()
    await expect(canvas.getByLabelText('내부 주문번호')).toHaveValue('')
    await waitFor(() =>
      expect(requests.mock.lastCall?.[0].fieldValues).toEqual({}),
    )
  },
}
export const Close: Story = {
  play: async ({ canvas, args }) => {
    await canvas.findByLabelText('주문번호')
    await userEvent.click(
      canvas.getByRole('button', { name: '목록으로 돌아가기' }),
    )
    await expect(args.onClose).toHaveBeenCalledOnce()
  },
}
export const Empty: Story = {
  parameters: {
    msw: {
      handlers: [
        ...base,
        http.post('/api/v1/admin/ticket-forms/:id/preview', () =>
          HttpResponse.json({ formId: form.id, formVersion: 1, fields: [] }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('이 조건에서 표시할 필드가 없습니다.'),
    ).toBeVisible()
  },
}
export const Denied: Story = {
  parameters: {
    msw: {
      handlers: [
        ...base,
        http.post('/api/v1/admin/ticket-forms/:id/preview', () =>
          HttpResponse.json({ status: 403 }, { status: 403 }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('미리보기를 불러오지 못했습니다.'),
    ).toBeVisible()
  },
}
let failures = 0
export const ErrorRecovery: Story = {
  beforeEach: () => {
    failures = 0
  },
  parameters: {
    msw: {
      handlers: [
        ...base,
        http.post('/api/v1/admin/ticket-forms/:id/preview', () =>
          ++failures === 1
            ? HttpResponse.json({ status: 503 }, { status: 503 })
            : HttpResponse.json(projection()),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await canvas.findByText('미리보기를 불러오지 못했습니다.')
    await userEvent.click(canvas.getByRole('button', { name: '조건 적용' }))
    await expect(await canvas.findByLabelText('주문번호')).toBeVisible()
  },
}
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        ...base,
        http.post('/api/v1/admin/ticket-forms/:id/preview', async () => {
          await delay('infinite')
          return HttpResponse.json(projection())
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('button', { name: '평가 중…' }),
    ).toBeDisabled()
  },
}
export const NewerSavedVersion: Story = {
  parameters: {
    msw: {
      handlers: [
        ...base,
        http.post('/api/v1/admin/ticket-forms/:id/preview', () =>
          HttpResponse.json({ ...projection(), formVersion: 2 }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('목록 이후 설정 버전이 바뀌었습니다.'),
    ).toBeVisible()
  },
}
export const ReadOnlyAndNumber: Story = {
  parameters: {
    msw: {
      handlers: [
        ...base,
        http.post('/api/v1/admin/ticket-forms/:id/preview', () =>
          HttpResponse.json({
            ...projection(),
            fields: [
              { field, visible: true, editable: false, required: false },
              {
                field: {
                  ...field,
                  id: 'number',
                  machineKey: 'amount',
                  type: 'NUMBER',
                  customerLabel: '금액',
                },
                visible: true,
                editable: true,
                required: false,
              },
            ],
          }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByLabelText('주문번호')).toBeDisabled()
    await userEvent.type(canvas.getByLabelText('금액'), '1e3')
    await expect(
      canvas.getByRole('button', { name: '조건 적용' }),
    ).toBeDisabled()
    await expect(canvas.getByText('숫자 입력을 확인해 주세요.')).toBeVisible()
  },
}

export const TypedCandidateValues: Story = {
  parameters: {
    msw: {
      handlers: [
        ...base,
        http.post(
          '/api/v1/admin/ticket-forms/:id/preview',
          async ({ request }) => {
            requests(await request.json())
            return HttpResponse.json({
              formId: form.id,
              formVersion: 1,
              fields: [
                {
                  field: {
                    ...field,
                    id: 'notes',
                    machineKey: 'notes',
                    type: 'LONG_TEXT',
                    customerLabel: '상세 사유',
                  },
                  visible: true,
                  editable: true,
                  required: false,
                },
                {
                  field: {
                    ...field,
                    id: 'agree',
                    machineKey: 'agree',
                    type: 'CHECKBOX',
                    customerLabel: '반품 신청 여부',
                  },
                  visible: true,
                  editable: true,
                  required: false,
                },
                {
                  field: {
                    ...field,
                    id: 'method',
                    machineKey: 'method',
                    type: 'SINGLE_SELECT',
                    customerLabel: '결제 수단',
                  },
                  visible: true,
                  editable: true,
                  required: false,
                  options: [
                    {
                      id: 'choice',
                      version: 1,
                      order: 0,
                      machineKey: 'card',
                      staffLabel: '직원 카드 명칭',
                      customerLabel: '카드',
                      active: true,
                    },
                  ],
                },
                {
                  field: {
                    ...field,
                    id: 'amount',
                    machineKey: 'amount',
                    type: 'NUMBER',
                    customerLabel: '환불 금액',
                  },
                  visible: true,
                  editable: true,
                  required: false,
                },
              ],
            })
          },
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      await canvas.findByLabelText('상세 사유'),
      '첫 줄\n두 번째 줄',
    )
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '반품 신청 여부' }),
    )
    await userEvent.selectOptions(canvas.getByLabelText('결제 수단'), 'choice')
    await expect(canvas.queryByText('직원 카드 명칭')).not.toBeInTheDocument()
    await userEvent.type(
      canvas.getByLabelText('환불 금액'),
      '12345678901234567890.12',
    )
    await userEvent.click(canvas.getByRole('button', { name: '조건 적용' }))
    await waitFor(() =>
      expect(requests.mock.lastCall?.[0].fieldValues).toEqual({
        notes: { longTextValue: '첫 줄\n두 번째 줄' },
        agree: { booleanValue: true },
        method: { optionId: 'choice' },
        amount: { numberValue: '12345678901234567890.12' },
      }),
    )
  },
}
