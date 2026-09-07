import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent, waitFor } from 'storybook/test'
import { http, HttpResponse, delay } from 'msw'
import { ApiError } from '../../api/client'
import type { RequestConfiguration } from './requestConfiguration'
import { CustomerRequestForm } from './CustomerRequestForm'

const submittedRequest = {
  replayed: false,
  accessToken: 'a'.repeat(43),
  createdAt: '2026-08-15T00:00:00Z',
  status: 'NEW' as const,
  ticketNumber: 1042,
}

const meta = {
  title: '06 Customer/Customer Request Form',
  component: CustomerRequestForm,
  args: {
    onSubmitted: fn(),
    loadConfiguration: async (): Promise<RequestConfiguration> => ({
      form: null,
      policies: [],
    }),
    submit: async () => submittedRequest,
  },
  parameters: {
    docs: {
      description: {
        component:
          '고객 지원 문의를 접수하는 폼입니다. 상위 route가 현재 access mode와 고객 세션에 맞는 production submit 함수를 제공하며, 이 컴포넌트는 유효성 검사·입력 보존·명시적인 rate-limit 복구만 담당합니다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerRequestForm>

export default meta
type Story = StoryObj<typeof meta>

async function fillValidRequest(
  canvas: Parameters<NonNullable<Story['play']>>[0]['canvas'],
) {
  await userEvent.type(canvas.getByLabelText('이름'), '김민아')
  await userEvent.type(canvas.getByLabelText('이메일'), 'mina@example.test')
  await userEvent.type(canvas.getByLabelText('제목'), '결제 확인 요청')
  await userEvent.type(
    canvas.getByLabelText('문의 내용'),
    '결제 승인 내역을 확인해 주세요.',
  )
}

export const ReadyToSubmit: Story = {
  play: async ({ args, canvas }) => {
    await fillValidRequest(canvas)
    await userEvent.click(canvas.getByRole('button', { name: '문의 접수' }))
    await waitFor(() =>
      expect(args.onSubmitted).toHaveBeenCalledWith(submittedRequest),
    )
  },
}

export const RateLimited: Story = {
  args: {
    onSubmitted: fn(),
    submit: async () => {
      throw new ApiError('요청이 많습니다.', 429, undefined, 'req-rate-1', '60')
    },
  },
  play: async ({ canvas }) => {
    await fillValidRequest(canvas)
    await userEvent.click(canvas.getByRole('button', { name: '문의 접수' }))
    await expect(await canvas.findByText(/60초 후 다시 시도/)).toBeVisible()
    await expect(canvas.getByLabelText('문의 내용')).toHaveValue(
      '결제 승인 내역을 확인해 주세요.',
    )
  },
}

const formId = '22222222-2222-4222-8222-222222222222'
const refund = {
  field: {
    id: '33333333-3333-4333-8333-333333333333',
    machineKey: 'request.refund',
    type: 'CHECKBOX' as const,
    label: '환불 요청',
    validation: {},
  },
  visible: true,
  editable: true,
  required: true,
  options: [],
}
const order = {
  field: {
    id: '44444444-4444-4444-8444-444444444444',
    machineKey: 'order.reference',
    type: 'SHORT_TEXT' as const,
    label: '환불을 요청하는 주문의 주문번호',
    description: '주문 내역의 주문번호를 입력해 주세요.',
    validation: { maxLength: 80 },
  },
  visible: true,
  editable: true,
  required: true,
  options: [],
}
const form = { formId, formVersion: 1, fields: [refund, order] }
const policy = {
  policyKey: 'inquiry-processing',
  version: 1,
  title: '문의 처리를 위한 개인정보 수집 및 이용',
  required: true,
  paragraphs: ['문의 답변을 위해 이름과 이메일을 사용합니다.'],
}
const conditionalHandlers = [
  http.post('/api/v1/customer/ticket-form-projections', async ({ request }) => {
    const input = (await request.json()) as {
      fieldValues: Record<string, { booleanValue?: boolean }>
    }
    return HttpResponse.json({
      ...form,
      fields:
        input.fieldValues['request.refund']?.booleanValue === false
          ? [refund]
          : [refund, order],
    })
  }),
]

export const ConditionalRefundAndConsent: Story = {
  args: {
    loadConfiguration: async () => ({ form, policies: [policy] }),
    submit: fn(async () => submittedRequest),
  },
  parameters: { msw: { handlers: conditionalHandlers } },
  play: async ({ canvas, args }) => {
    await fillValidRequest(canvas)
    await userEvent.selectOptions(
      await canvas.findByLabelText('환불 요청 (필수)'),
      'true',
    )
    await userEvent.type(
      canvas.getByLabelText('환불을 요청하는 주문의 주문번호 (필수)'),
      'ORD-1042',
    )
    await userEvent.click(canvas.getByText(`${policy.title} 내용 보기`))
    await expect(canvas.getByText(policy.paragraphs[0]!)).toBeVisible()
    await userEvent.click(
      canvas.getByRole('checkbox', {
        name: `${policy.title}에 동의합니다. (필수)`,
      }),
    )
    await waitFor(() =>
      expect(canvas.getByRole('button', { name: '문의 접수' })).toBeEnabled(),
    )
    await userEvent.click(canvas.getByRole('button', { name: '문의 접수' }))
    await waitFor(() =>
      expect(args.submit).toHaveBeenCalledWith(
        expect.objectContaining({
          fieldValues: {
            'request.refund': { booleanValue: true },
            'order.reference': { shortTextValue: 'ORD-1042' },
          },
          acceptedPolicies: [{ policyKey: policy.policyKey, version: 1 }],
        }),
      ),
    )
  },
}
export const HiddenFieldExcluded: Story = {
  args: {
    loadConfiguration: async () => ({ form, policies: [] }),
    submit: fn(async () => submittedRequest),
  },
  parameters: { msw: { handlers: conditionalHandlers } },
  play: async ({ canvas, args }) => {
    await fillValidRequest(canvas)
    await userEvent.type(
      await canvas.findByLabelText('환불을 요청하는 주문의 주문번호 (필수)'),
      'not-sent',
    )
    await userEvent.selectOptions(
      canvas.getByLabelText('환불 요청 (필수)'),
      'false',
    )
    await waitFor(() =>
      expect(
        canvas.queryByLabelText('환불을 요청하는 주문의 주문번호 (필수)'),
      ).not.toBeInTheDocument(),
    )
    await userEvent.click(canvas.getByRole('button', { name: '문의 접수' }))
    await waitFor(() =>
      expect(args.submit).toHaveBeenCalledWith(
        expect.objectContaining({
          fieldValues: { 'request.refund': { booleanValue: false } },
        }),
      ),
    )
  },
}
export const AmbiguousRetryKeepsPayload: Story = {
  args: { submit: fn() },
  beforeEach: ({ args }) => {
    ;(args.submit as ReturnType<typeof fn>)
      .mockReset()
      .mockRejectedValueOnce(new TypeError('transport'))
      .mockResolvedValue(submittedRequest)
  },
  play: async ({ canvas, args }) => {
    await fillValidRequest(canvas)
    await userEvent.click(canvas.getByRole('button', { name: '문의 접수' }))
    const retry = await canvas.findByRole('button', {
      name: '같은 내용으로 접수 확인',
    })
    await expect(canvas.getByLabelText('문의 내용')).toBeDisabled()
    await userEvent.click(retry)
    await waitFor(() => expect(args.onSubmitted).toHaveBeenCalled())
    const calls = (args.submit as ReturnType<typeof fn>).mock.calls
    await expect(calls[0]![0]).toEqual(calls[1]![0])
  },
}
export const SignedInRequester: Story = {
  args: {
    customer: { name: '김민아', email: 'mina@example.test' },
    submit: fn(async () => submittedRequest),
  },
  play: async ({ canvas, args }) => {
    await userEvent.type(canvas.getByLabelText('제목'), '주문 확인')
    await userEvent.type(
      canvas.getByLabelText('문의 내용'),
      '주문을 확인해 주세요.',
    )
    await userEvent.click(canvas.getByRole('button', { name: '문의 접수' }))
    await waitFor(() => expect(args.submit).toHaveBeenCalled())
    await expect(
      (args.submit as ReturnType<typeof fn>).mock.calls[0]![0],
    ).not.toHaveProperty('requester')
    await expect(canvas.queryByLabelText('이메일')).not.toBeInTheDocument()
  },
}
export const ConfigurationUnavailable: Story = {
  args: {
    loadConfiguration: async () => {
      throw new Error('unavailable')
    },
  },
}
export const LoadingConfiguration: Story = {
  args: {
    loadConfiguration: async () => {
      await delay('infinite')
      return { form: null, policies: [] }
    },
  },
}
export const FormVersionChanged: Story = {
  args: {
    submit: fn(async () => {
      throw new ApiError('changed', 409)
    }),
  },
  play: async ({ canvas }) => {
    await fillValidRequest(canvas)
    await userEvent.click(canvas.getByRole('button', { name: '문의 접수' }))
    await userEvent.click(
      await canvas.findByRole('button', { name: '양식과 동의 내용 다시 확인' }),
    )
    await expect(canvas.getByLabelText('문의 내용')).toHaveValue(
      '결제 승인 내역을 확인해 주세요.',
    )
  },
}
