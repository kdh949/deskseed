import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import { WorkspaceNavigationRail } from './WorkspaceNavigationRail'

const items = [
  { icon: 'home' as const, id: 'home', label: '홈', to: '/' },
  { icon: 'search' as const, id: 'lookup', label: '문의 조회', to: '/lookup' },
  { icon: 'pencil' as const, id: 'new', label: '새 문의', to: '/new' },
]

const meta = {
  title: '05 Shells & Layouts/Workspace Navigation Rail',
  component: WorkspaceNavigationRail,
  args: {
    activeItemId: 'lookup',
    ariaLabel: '고객 지원 메뉴',
    brandLabel: 'Deskseed 고객 지원 홈',
    brandTo: '/',
    items,
  },
  parameters: {
    docs: {
      description: {
        component:
          'Deskseed 작업 공간의 고정 전역 rail입니다. 상담사와 고객 지원 shell이 동일한 키보드·활성 상태 계약을 공유하며, 화면별 탐색 항목만 전달합니다.',
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof WorkspaceNavigationRail>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('navigation', { name: '고객 지원 메뉴' }),
    ).toBeVisible()
    await userEvent.tab()
    await expect(
      canvas.getByRole('link', { name: 'Deskseed 고객 지원 홈' }),
    ).toHaveFocus()
    await userEvent.tab()
    await expect(canvas.getByRole('link', { name: '홈' })).toHaveFocus()
  },
}

export const Inverse: Story = {
  args: { tone: 'inverse' },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('link', { name: '문의 조회' })).toHaveClass(
      'is-active',
    )
  },
}
