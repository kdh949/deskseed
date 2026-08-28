import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent } from 'storybook/test'
import { ApiError } from '../../api/client'
import { CustomerRequestForm } from './CustomerRequestForm'

const submittedRequest = {
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
    await expect(args.onSubmitted).toHaveBeenCalledWith(submittedRequest)
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
    await expect(canvas.getByText(/60초 후 다시 시도/)).toBeVisible()
    await expect(canvas.getByLabelText('문의 내용')).toHaveValue(
      '결제 승인 내역을 확인해 주세요.',
    )
  },
}
