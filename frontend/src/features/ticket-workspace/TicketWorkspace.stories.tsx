import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { TicketWorkspace } from './TicketWorkspace'

const meta = {
  title: '06 Domain & Workspace/TicketWorkspace',
  component: TicketWorkspace,
  parameters: {
    docs: {
      description: {
        component:
          'properties, conversation/composer, context를 결합하는 three-panel workspace contract다. current product route는 read-only이고, fixture stories가 mutation UI의 draft/conflict contract만 보존한다.',
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof TicketWorkspace>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {}

export const ReadOnly: Story = {
  args: { submitDisabledReason: '현재 Workspace는 읽기 전용입니다.' },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('button', { name: '내부 메모 추가' }),
    ).toBeDisabled()
  },
}

export const Conflict: Story = { args: { initialState: 'conflict' } }
export const Loading: Story = { args: { initialState: 'loading' } }
export const Empty: Story = { args: { initialState: 'empty' } }
export const Error: Story = { args: { initialState: 'error' } }
export const Denied: Story = { args: { initialState: 'denied' } }
