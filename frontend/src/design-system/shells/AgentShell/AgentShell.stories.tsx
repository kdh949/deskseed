import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { AgentShell } from './AgentShell'

const meta = {
  title: 'Design System/Shells/AgentShell',
  component: AgentShell,
  parameters: { layout: 'fullscreen' },
  tags: ['autodocs'],
} satisfies Meta<typeof AgentShell>

export default meta
type Story = StoryObj<typeof meta>

export const Queue: Story = {
  args: {
    activeNavigationItem: 'views',
    children: (
      <main aria-label="티켓 큐" className="agent-queue-workspace">
        <section className="agent-queue">
          <h1>내 티켓</h1>
        </section>
      </main>
    ),
    displayName: 'Mina Park',
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('navigation', { name: '상담사 전역 탐색' }),
    ).toBeVisible()
    await expect(canvas.getByRole('link', { name: 'Views' })).toBeVisible()
    await expect(canvas.queryByRole('searchbox')).not.toBeInTheDocument()
    await expect(
      canvas.queryByRole('link', { name: '관리자 설정' }),
    ).not.toBeInTheDocument()
  },
}
