import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent } from 'storybook/test'
import { CustomerRequestLookupPanel } from './CustomerRequestLookupPanel'

const meta = {
  title: '04 Patterns/Customer Request Lookup Panel',
  component: CustomerRequestLookupPanel,
  args: {
    onSubmit: fn(),
    onTicketNumberChange: fn(),
    result: null,
    ticketNumber: '',
  },
  parameters: {
    docs: {
      description: {
        component:
          '브라우저에 이미 보관된 ticket-scoped access proof로 문의를 다시 여는 controlled 조회 패턴입니다. 번호 입력과 실제 지원되는 invalid·missing 상태만 표현하며 capability token 입력은 제공하지 않습니다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerRequestLookupPanel>

export default meta
type Story = StoryObj<typeof meta>

export const Empty: Story = {
  play: async ({ args, canvas }) => {
    await expect(
      canvas.getByRole('heading', { level: 1, name: '문의 조회' }),
    ).toBeVisible()
    await userEvent.type(canvas.getByLabelText('문의 번호'), '1042')
    await expect(args.onTicketNumberChange).toHaveBeenCalled()
    await userEvent.click(canvas.getByRole('button', { name: '문의 열기' }))
    await expect(args.onSubmit).toHaveBeenCalledOnce()
    await expect(
      canvas.queryByLabelText(/토큰|조회 키/),
    ).not.toBeInTheDocument()
  },
}

export const InvalidNumber: Story = {
  args: { result: 'invalid', ticketNumber: 'abc' },
  play: async ({ canvas }) => {
    await expect(canvas.getByLabelText('문의 번호')).toHaveAttribute(
      'aria-invalid',
      'true',
    )
    await expect(canvas.getByText('문의 번호를 확인해 주세요.')).toBeVisible()
  },
}

export const MissingEmailLink: Story = {
  args: { result: 'missing', ticketNumber: '1042' },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('이메일 문의 링크가 필요합니다.'),
    ).toBeVisible()
    await expect(
      canvas.queryByLabelText(/토큰|조회 키/),
    ).not.toBeInTheDocument()
  },
}
