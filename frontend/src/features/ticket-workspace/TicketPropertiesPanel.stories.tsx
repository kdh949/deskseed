import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { TicketPropertiesPanel } from './TicketPropertiesPanel'
import { ticketFixtures } from './ticketWorkspaceFixture'

const meta = {
  title: '06 Domain & Workspace/TicketPropertiesPanel',
  component: TicketPropertiesPanel,
  parameters: {
    docs: {
      description: {
        component:
          'ticket status, priority, ownership, requester, tags를 한 곳에서 확인하는 왼쪽 workspace panel이다. read-only와 conflict를 명확히 표시하고 conflict draft를 조용히 폐기하지 않는다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof TicketPropertiesPanel>

export default meta
type Story = StoryObj<typeof meta>

const baseArgs = {
  collapsed: false,
  onCollapse: fn(),
  onResolveConflict: fn(),
  showConflict: false,
  ticket: ticketFixtures[0]!,
}

export const Editable: Story = { args: baseArgs }

export const ReadOnly: Story = {
  args: { ...baseArgs, readOnly: true },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('combobox', { name: 'Status' }),
    ).toBeDisabled()
  },
}

export const Conflict: Story = {
  args: { ...baseArgs, showConflict: true },
  play: async ({ args, canvas, userEvent }) => {
    await expect(
      canvas.getByRole('region', { name: '담당자 저장 충돌' }),
    ).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '서버 값 적용' }))
    await expect(args.onResolveConflict).toHaveBeenCalledOnce()
  },
}

export const Collapsed: Story = {
  args: { ...baseArgs, collapsed: true },
}

export const ManyTags: Story = {
  args: {
    ...baseArgs,
    ticket: {
      ...ticketFixtures[0]!,
      tags: ['결제 오류', '카드 결제', '긴급', 'Chrome', 'Windows 11', '구독'],
    },
  },
}
