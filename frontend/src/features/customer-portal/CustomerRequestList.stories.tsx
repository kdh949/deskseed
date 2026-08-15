import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { CustomerRequestList } from './CustomerRequestList'

const items = [
  {
    ticketNumber: 1042,
    subject: '결제 승인 내역 확인 요청',
    status: 'OPEN' as const,
    createdAt: '2026-08-15T00:00:00Z',
    updatedAt: '2026-08-15T01:00:00Z',
  },
  {
    ticketNumber: 1041,
    subject: '배송 상태 확인 요청',
    status: 'PENDING' as const,
    createdAt: '2026-08-14T00:00:00Z',
    updatedAt: '2026-08-14T03:00:00Z',
  },
]

const meta = {
  title: '06 Customer/Customer Request List',
  component: CustomerRequestList,
  args: {
    items,
    loadingMore: false,
    nextCursor: null,
    onLoadMore: fn(),
  },
  parameters: {
    docs: {
      description: {
        component:
          '인증 customer의 `CustomerRequestPage` allowlist projection을 표시하는 목록입니다. 각 항목은 실제 `updatedAt`을 최근 업데이트 시간으로 표시하며, staff·internal·audit 필드를 받거나 렌더링하지 않습니다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerRequestList>

export default meta
type Story = StoryObj<typeof meta>

export const WithRequests: Story = {
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('link', { name: /#1042 결제 승인 내역 확인 요청/ }),
    ).toBeVisible()
    await expect(canvas.getByText('처리 중')).toBeVisible()
  },
}

export const Empty: Story = {
  args: {
    items: [],
    loadingMore: false,
    nextCursor: null,
    onLoadMore: fn(),
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { name: '표시할 문의가 없습니다.' }),
    ).toBeVisible()
  },
}

export const MoreAvailable: Story = {
  args: {
    items,
    loadingMore: false,
    nextCursor: 'opaque-next-cursor',
    onLoadMore: fn(),
  },
  play: async ({ args, canvas, userEvent }) => {
    await userEvent.click(canvas.getByRole('button', { name: '문의 더 보기' }))
    await expect(args.onLoadMore).toHaveBeenCalled()
  },
}
