import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse, delay } from 'msw'
import { expect, userEvent, waitFor, type within } from 'storybook/test'
import { AgentTicketConfigurationPanel } from './AgentTicketConfigurationPanel'
import type { AgentConfiguration } from './runtime-api'
const order = '11111111-1111-4111-8111-111111111111'
const tag = '22222222-2222-4222-8222-222222222222'
const status = '33333333-3333-4333-8333-333333333333'
const config: AgentConfiguration = {
  ticketNumber: 1042,
  version: 3,
  writable: true,
  form: {
    formId: order,
    formVersion: 1,
    fields: [
      {
        id: order,
        machineKey: 'order.reference',
        type: 'SHORT_TEXT',
        label: '주문번호',
        description: null,
        validation: {},
        visible: true,
        editable: true,
        required: true,
        options: [],
      },
    ],
  },
  fieldValues: { 'order.reference': { shortTextValue: 'ORD-1042' } },
  tags: [],
  statusCategory: 'OPEN',
  customStatus: null,
  availableTags: [{ id: tag, label: '환불 요청' }],
  availableStatuses: [{ id: status, label: '고객 확인 대기' }],
}
let writes: { body: unknown; version: string | null }[] = []
const baseHandlers = [
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'story-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.get('/api/v1/agent/tickets/1042/configuration', () =>
    HttpResponse.json(config),
  ),
  http.post(
    '/api/v1/agent/tickets/1042/configuration/projection',
    async ({ request }) => {
      const body = (await request.json()) as { customStatusId?: string }
      return HttpResponse.json(
        body.customStatusId
          ? {
              ...config,
              form: {
                ...config.form,
                fields: config.form!.fields.map((f) => ({
                  ...f,
                  visible: false,
                  editable: false,
                  required: false,
                })),
              },
            }
          : config,
      )
    },
  ),
  http.put('/api/v1/agent/tickets/1042/configuration', async ({ request }) => {
    writes.push({
      body: await request.json(),
      version: request.headers.get('If-Match'),
    })
    return HttpResponse.json({ version: 4, replayed: false })
  }),
]
const open = async (canvas: ReturnType<typeof within>) => {
  await userEvent.click(
    canvas.getByRole('button', { name: '필드·태그·상태 편집' }),
  )
  const body = canvas
  await body.findByLabelText(/주문번호 \(필수\)/)
  await waitFor(() =>
    expect(body.getByRole('button', { name: '추가 정보 저장' })).toBeEnabled(),
  )
  return body
}
const meta = {
  title: '07 Screens/Agent Ticket Configuration',
  component: AgentTicketConfigurationPanel,
  args: { ticketNumber: 1042 },
  beforeEach: () => {
    writes = []
  },
  parameters: { msw: { handlers: baseHandlers } },
} satisfies Meta<typeof AgentTicketConfigurationPanel>
export default meta
type Story = StoryObj<typeof meta>
export const EditValuesAndTags: Story = {
  play: async ({ canvas }) => {
    const body = await open(canvas)
    await userEvent.clear(body.getByLabelText(/주문번호 \(필수\)/))
    await userEvent.type(body.getByLabelText(/주문번호 \(필수\)/), 'ORD-2026')
    await userEvent.click(body.getByLabelText(/환불 요청/))
    await waitFor(() =>
      expect(
        body.getByRole('button', { name: '추가 정보 저장' }),
      ).toBeEnabled(),
    )
    await userEvent.click(body.getByRole('button', { name: '추가 정보 저장' }))
    await canvas.findByText('추가 정보를 저장했습니다.')
    expect(writes).toEqual([
      {
        version: '"3"',
        body: {
          formId: order,
          formVersion: 1,
          fieldValues: { 'order.reference': { shortTextValue: 'ORD-2026' } },
          addTagIds: [tag],
          removeTagIds: [],
          clientCommandId: expect.any(String),
        },
      },
    ])
  },
}
export const HiddenValuesAreExcluded: Story = {
  play: async ({ canvas }) => {
    const body = await open(canvas)
    await userEvent.selectOptions(body.getByLabelText(/업무 상태/), status)
    await waitFor(() =>
      expect(
        body.queryByLabelText(/주문번호 \(필수\)/),
      ).not.toBeInTheDocument(),
    )
    await userEvent.click(body.getByRole('button', { name: '추가 정보 저장' }))
    await canvas.findByText('추가 정보를 저장했습니다.')
    expect(writes[0]?.body).toMatchObject({
      fieldValues: {},
      customStatusId: status,
    })
  },
}
export const RetryKeepsExactCommand: Story = {
  parameters: {
    msw: {
      handlers: [
        http.put(
          '/api/v1/agent/tickets/1042/configuration',
          async ({ request }) => {
            writes.push({
              body: await request.json(),
              version: request.headers.get('If-Match'),
            })
            return writes.length === 1
              ? HttpResponse.error()
              : HttpResponse.json({ version: 4, replayed: true })
          },
        ),
        ...baseHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    const body = await open(canvas)
    await userEvent.click(body.getByRole('button', { name: '추가 정보 저장' }))
    const retry = await body.findByRole('button', {
      name: '같은 내용으로 저장 확인',
    })
    await expect(body.getByLabelText(/주문번호 \(필수\)/)).toBeDisabled()
    await userEvent.click(retry)
    await canvas.findByText('추가 정보를 저장했습니다.')
    expect(writes).toHaveLength(2)
    expect(writes[0]).toEqual(writes[1])
  },
}
export const ConflictPreservesInput: Story = {
  parameters: {
    msw: {
      handlers: [
        http.put('/api/v1/agent/tickets/1042/configuration', () =>
          HttpResponse.json({ status: 412 }, { status: 412 }),
        ),
        ...baseHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    const body = await open(canvas)
    await userEvent.type(body.getByLabelText(/주문번호 \(필수\)/), '-확인')
    await waitFor(() =>
      expect(
        body.getByRole('button', { name: '추가 정보 저장' }),
      ).toBeEnabled(),
    )
    await userEvent.click(body.getByRole('button', { name: '추가 정보 저장' }))
    await body.findByText(/티켓 또는 폼이 변경/)
    await expect(body.getByLabelText(/주문번호 \(필수\)/)).toHaveValue(
      'ORD-1042-확인',
    )
    await expect(
      body.getByRole('button', { name: '추가 정보 저장' }),
    ).toBeDisabled()
  },
}
export const KeyboardRestoresFocus: Story = {
  play: async ({ canvas }) => {
    await open(canvas)
    await userEvent.keyboard('{Escape}')
    await expect(
      canvas.getByRole('button', { name: '필드·태그·상태 편집' }),
    ).toHaveFocus()
  },
}
export const ReadOnly: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/tickets/1042/configuration', () =>
          HttpResponse.json({ ...config, writable: false }),
        ),
        ...baseHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '필드·태그·상태 편집' }),
    )
    const body = canvas
    await body.findByText('이 티켓은 읽기만 가능합니다.')
    await expect(
      body.getByRole('button', { name: '추가 정보 저장' }),
    ).toBeDisabled()
  },
}
export const Empty: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/tickets/1042/configuration', () =>
          HttpResponse.json({
            ...config,
            form: null,
            availableTags: [],
            availableStatuses: [],
          }),
        ),
        http.post('/api/v1/agent/tickets/1042/configuration/projection', () =>
          HttpResponse.json({
            ...config,
            form: null,
            availableTags: [],
            availableStatuses: [],
          }),
        ),
        ...baseHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '필드·태그·상태 편집' }),
    )
    await canvas.findByText('현재 설정된 추가 문의 항목이 없습니다.')
  },
}
export const Denied: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/tickets/1042/configuration', () =>
          HttpResponse.json({ status: 403 }, { status: 403 }),
        ),
        ...baseHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '필드·태그·상태 편집' }),
    )
    await canvas.findByText('이 티켓 설정을 볼 권한이 없습니다.')
  },
}
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/tickets/1042/configuration', async () => {
          await delay('infinite')
          return HttpResponse.json(config)
        }),
        ...baseHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '필드·태그·상태 편집' }),
    )
    await canvas.findByText('설정을 불러오는 중…')
  },
}
export const ProjectionUnavailable: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post('/api/v1/agent/tickets/1042/configuration/projection', () =>
          HttpResponse.json({ status: 503 }, { status: 503 }),
        ),
        ...baseHandlers,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('button', { name: '필드·태그·상태 편집' }),
    )
    const body = canvas
    await body.findByText(/추가 항목의 조건을 확인하지/)
    await expect(
      body.getByRole('button', { name: '추가 정보 저장' }),
    ).toBeDisabled()
  },
}
