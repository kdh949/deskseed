import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent } from 'storybook/test'
import { CustomerSiteLayout } from './CustomerSiteLayout'

const customer = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'customer@example.test',
  displayName: '김민아',
  verifiedAt: '2026-08-15T00:00:00Z',
}

const meta = {
  title: '05 Shells & Layouts/Customer Site Layout',
  component: CustomerSiteLayout,
  parameters: {
    docs: {
      description: {
        component:
          '고객 접수·조회·계정 화면에 공통으로 쓰는 Deskseed 고객 사이트 레이아웃입니다. 고객 세션은 상위 route provider가 소유하고, 이 컴포넌트는 현재 로그인 상태에 맞는 탐색과 로그아웃 action만 표현합니다.',
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerSiteLayout>

export default meta
type Story = StoryObj<typeof meta>

export const Anonymous: Story = {
  args: {
    children: <main className="customer-page">고객 지원 내용</main>,
    session: { status: 'anonymous' },
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('navigation', { name: '고객 탐색' }),
    ).toBeVisible()
    await expect(canvas.getByRole('link', { name: '로그인' })).toBeVisible()
  },
}

export const Authenticated: Story = {
  args: {
    children: <main className="customer-page">내 문의</main>,
    onSignOut: fn(),
    session: { customer, status: 'authenticated' },
  },
  play: async ({ args, canvas }) => {
    await expect(canvas.getByText('김민아')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '로그아웃' }))
    await expect(args.onSignOut).toHaveBeenCalled()
  },
}

export const SessionLoading: Story = {
  args: {
    children: <main className="customer-page">고객 지원 내용</main>,
    session: { status: 'loading' },
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('세션 확인 중')).toBeVisible()
  },
}
