import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent } from 'storybook/test'
import { AdminShell } from './AdminShell'

const meta = {
  title: '05 Shells & Layouts/AdminShell',
  component: AdminShell,
  parameters: {
    docs: {
      description: {
        component:
          'ADMIN 전용 운영 화면의 Deskseed shell입니다. 실제 세션 식별과 로그아웃 action을 표시하고, 열거된 production 관리자 route만 탐색으로 노출합니다.',
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AdminShell>

export default meta
type Story = StoryObj<typeof meta>

export const MailOperations: Story = {
  args: {
    children: (
      <main className="admin-page" aria-label="메일 운영">
        <h1>메일 운영</h1>
      </main>
    ),
    displayName: '운영 관리자',
    onSignOut: fn(),
  },
  play: async ({ args, canvas }) => {
    await expect(
      canvas.getByRole('navigation', { name: '관리자 설정 메뉴' }),
    ).toBeVisible()
    await expect(canvas.getByRole('link', { name: '메일 운영' })).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '로그아웃' }))
    await expect(args.onSignOut).toHaveBeenCalled()
  },
}
