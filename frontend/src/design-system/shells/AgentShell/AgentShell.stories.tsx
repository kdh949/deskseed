import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { AgentShell } from './AgentShell'

const meta = {
  title: '05 Shells & Layouts/AgentShell',
  component: AgentShell,
  parameters: {
    docs: {
      description: {
        component:
          'Agent Queue와 Ticket Workspace가 공유하는 production shell이다. Deskseed global rail, top chrome, 실제 staff 세션 식별 영역의 layout contract를 소유한다.',
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AgentShell>

export default meta
type Story = StoryObj<typeof meta>

export const Queue: Story = {
  args: {
    activeNavigationItem: 'views',
    canCreateTicket: true,
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
    await expect(canvas.getByRole('link', { name: '새 티켓' })).toBeVisible()
    await expect(
      canvas.queryByRole('navigation', { name: '열린 티켓 탭' }),
    ).not.toBeInTheDocument()
    await expect(canvas.queryByRole('searchbox')).not.toBeInTheDocument()
    await expect(
      canvas.queryByRole('link', { name: '관리자 설정' }),
    ).not.toBeInTheDocument()
  },
}

export const AuditOnly: Story = {
  args: {
    children: (
      <main aria-label="감사 탐색기" className="agent-queue-workspace">
        <section className="agent-queue">
          <h1>감사 탐색기</h1>
        </section>
      </main>
    ),
    displayName: 'Security Auditor',
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.queryByRole('link', { name: '새 티켓' }),
    ).not.toBeInTheDocument()
  },
}
