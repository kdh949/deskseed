import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent } from 'storybook/test'
import { CustomerRequestLookupPanel } from '../../patterns/CustomerRequestLookupPanel'
import { CustomerSiteLayout } from './CustomerSiteLayout'

const customer = {
  displayName: '김민아',
}

const meta = {
  title: '05 Shells & Layouts/Customer Site Layout',
  component: CustomerSiteLayout,
  parameters: {
    docs: {
      description: {
        component:
          '고객 접수·조회·계정 화면의 Deskseed 레이아웃입니다. 기존 site presentation을 유지하면서 고객지원 홈에는 rail과 업무 내비게이션을 갖춘 workspace presentation을 제공합니다. 세션과 도메인 상태는 상위 route가 소유합니다.',
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

export const WorkspaceAnonymous: Story = {
  args: {
    children: (
      <CustomerRequestLookupPanel
        onSubmit={fn()}
        onTicketNumberChange={fn()}
        result={null}
        ticketNumber=""
      />
    ),
    presentation: 'workspace',
    session: { status: 'anonymous' },
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('navigation', { name: '고객 지원 메뉴' }),
    ).toBeVisible()
    await expect(canvas.getByRole('main', { name: '문의 조회' })).toBeVisible()
    await expect(
      canvas.getByRole('complementary', { name: '새 문의 접수' }),
    ).toBeVisible()
  },
}

export const WorkspaceAuthenticated: Story = {
  args: {
    ...WorkspaceAnonymous.args,
    onSignOut: fn(),
    session: { customer, status: 'authenticated' },
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('김민아')).toBeVisible()
    await expect(canvas.getAllByRole('link', { name: '내 문의' })).toHaveLength(
      3,
    )
  },
}
