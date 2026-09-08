import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse, delay } from 'msw'
import { expect, userEvent, waitFor, type within } from 'storybook/test'
import { AgentTicketConfigurationPanel } from './AgentTicketConfigurationPanel'
import type { AgentConfiguration, FieldValue } from './runtime-api'
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
const numericConfig: AgentConfiguration = {
  ...config,
  form: {
    ...config.form!,
    fields: [
      ...config.form!.fields,
      ...['수량', '측정값'].map((label, index) => ({
        ...config.form!.fields[0]!,
        id: `44444444-4444-4444-8444-44444444444${index}`,
        machineKey: index === 0 ? 'amount' : 'measurement',
        type: 'NUMBER' as const,
        label,
      })),
    ],
  },
  fieldValues: {
    ...config.fieldValues,
    amount: { numberValue: '9007199254740993' },
    measurement: { numberValue: '123456789012345678.123456789012' },
  },
}
let numericCurrent = structuredClone(numericConfig)
let projections: { fieldValues: Record<string, FieldValue> }[] = []
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
const numericHandlers = [
  http.get('/api/v1/agent/tickets/1042/configuration', () =>
    HttpResponse.json(numericCurrent),
  ),
  http.post(
    '/api/v1/agent/tickets/1042/configuration/projection',
    async ({ request }) => {
      projections.push(
        (await request.json()) as { fieldValues: Record<string, FieldValue> },
      )
      return HttpResponse.json(numericCurrent)
    },
  ),
  http.put('/api/v1/agent/tickets/1042/configuration', async ({ request }) => {
    const body = (await request.json()) as {
      fieldValues: Record<string, FieldValue>
    }
    writes.push({ body, version: request.headers.get('If-Match') })
    numericCurrent = {
      ...numericCurrent,
      version: numericCurrent.version + 1,
      fieldValues: { ...numericCurrent.fieldValues, ...body.fieldValues },
    }
    return HttpResponse.json({
      version: numericCurrent.version,
      replayed: false,
    })
  }),
  ...baseHandlers,
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
    projections = []
    numericCurrent = structuredClone(numericConfig)
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

export const TagOnlyPreservesNumericPrecision: Story = {
  parameters: { msw: { handlers: numericHandlers } },
  play: async ({ canvas }) => {
    const body = await open(canvas)
    await expect(body.getByLabelText(/수량 \(필수\)/)).toHaveValue(
      '9007199254740993',
    )
    await expect(body.getByLabelText(/측정값 \(필수\)/)).toHaveValue(
      '123456789012345678.123456789012',
    )
    await userEvent.click(body.getByLabelText('환불 요청'))
    await userEvent.click(body.getByRole('button', { name: '추가 정보 저장' }))
    await canvas.findByText('추가 정보를 저장했습니다.')
    expect(writes[0]?.body).toMatchObject({ fieldValues: {}, addTagIds: [tag] })
    expect(projections.length).toBeGreaterThan(0)
    expect(
      projections.every(
        ({ fieldValues }) => Object.keys(fieldValues).length === 0,
      ),
    ).toBe(true)
    await open(canvas)
    await expect(canvas.getByLabelText(/수량 \(필수\)/)).toHaveValue(
      '9007199254740993',
    )
  },
}
export const EditExactNumbers: Story = {
  parameters: { msw: { handlers: numericHandlers } },
  play: async ({ canvas }) => {
    const body = await open(canvas)
    await userEvent.clear(body.getByLabelText(/수량 \(필수\)/))
    await userEvent.type(
      body.getByLabelText(/수량 \(필수\)/),
      '123456789012345678.123456789012',
    )
    await userEvent.clear(body.getByLabelText(/측정값 \(필수\)/))
    await userEvent.type(
      body.getByLabelText(/측정값 \(필수\)/),
      '-0.000000000001',
    )
    await waitFor(() =>
      expect(
        body.getByRole('button', { name: '추가 정보 저장' }),
      ).toBeEnabled(),
    )
    const fieldValues = {
      amount: { numberValue: '123456789012345678.123456789012' },
      measurement: { numberValue: '-0.000000000001' },
    }
    expect(projections.at(-1)?.fieldValues).toEqual(fieldValues)
    await userEvent.click(body.getByRole('button', { name: '추가 정보 저장' }))
    await canvas.findByText('추가 정보를 저장했습니다.')
    expect(writes[0]?.body).toMatchObject({ fieldValues })
    await open(canvas)
    await expect(canvas.getByLabelText(/수량 \(필수\)/)).toHaveValue(
      fieldValues.amount.numberValue,
    )
    await expect(canvas.getByLabelText(/측정값 \(필수\)/)).toHaveValue(
      fieldValues.measurement.numberValue,
    )
  },
}
export const InvalidNumericTextCannotSave: Story = {
  parameters: { msw: { handlers: numericHandlers } },
  play: async ({ canvas }) => {
    const body = await open(canvas)
    await userEvent.clear(body.getByLabelText(/수량 \(필수\)/))
    await userEvent.type(body.getByLabelText(/수량 \(필수\)/), '1e100')
    await expect(body.getByLabelText(/수량 \(필수\)/)).toBeInvalid()
    await body.findByText('숫자와 소수점으로 입력해 주세요.')
    await waitFor(() =>
      expect(
        body.getByRole('button', { name: '추가 정보 저장' }),
      ).toBeEnabled(),
    )
    await userEvent.click(body.getByRole('button', { name: '추가 정보 저장' }))
    expect(writes).toHaveLength(0)
    expect(
      projections.every(
        ({ fieldValues }) =>
          !fieldValues.amount || !fieldValues.amount.numberValue?.includes('e'),
      ),
    ).toBe(true)
  },
}
export const RefreshKeepsOnlyEditedFields: Story = {
  parameters: {
    msw: {
      handlers: [
        http.put('/api/v1/agent/tickets/1042/configuration', () => {
          numericCurrent = {
            ...numericCurrent,
            version: 4,
            fieldValues: {
              ...numericCurrent.fieldValues,
              amount: { numberValue: '9007199254740995' },
            },
          }
          return HttpResponse.json({ status: 412 }, { status: 412 })
        }),
        ...numericHandlers,
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
    await userEvent.click(
      body.getByRole('button', { name: '최신 설정 불러오기' }),
    )
    await waitFor(() =>
      expect(body.getByLabelText(/수량 \(필수\)/)).toHaveValue(
        '9007199254740995',
      ),
    )
    await expect(body.getByLabelText(/주문번호 \(필수\)/)).toHaveValue(
      'ORD-1042-확인',
    )
    await waitFor(() =>
      expect(projections.at(-1)?.fieldValues).toEqual({
        'order.reference': { shortTextValue: 'ORD-1042-확인' },
      }),
    )
  },
}
