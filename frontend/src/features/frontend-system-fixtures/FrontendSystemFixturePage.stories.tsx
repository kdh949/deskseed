import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import { FrontendSystemFixturePage } from './FrontendSystemFixturePage'

const meta = {
  title: 'Product/Agent Workspace/Canonical Fixtures',
  component: FrontendSystemFixturePage,
  parameters: { layout: 'fullscreen' },
  tags: ['autodocs'],
} satisfies Meta<typeof FrontendSystemFixturePage>

export default meta
type Story = StoryObj<typeof meta>

export const Queue: Story = {
  args: { fixtureName: 'view-queue' },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('heading', { name: '내 티켓' })).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '필터 열기' }))
    await expect(
      canvas.getByRole('region', { name: '내 티켓 필터' }),
    ).toBeVisible()
  },
}

export const QueueLoading: Story = {
  args: { fixtureName: 'view-queue-loading' },
}

export const QueueEmpty: Story = {
  args: { fixtureName: 'view-queue-empty' },
}

export const QueueError: Story = {
  args: { fixtureName: 'view-queue-error' },
}

export const QueueDenied: Story = {
  args: { fixtureName: 'view-queue-denied' },
}

export const Workspace: Story = {
  args: { fixtureName: 'workspace' },
  play: async ({ canvas }) => {
    await userEvent.click(
      canvas.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    )
    await userEvent.type(
      canvas.getByRole('textbox', { name: '공개 답변 내용' }),
      '고객에게 보낼 공개 초안',
    )
    await userEvent.click(
      canvas.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )
    await expect(
      canvas.getByRole('textbox', { name: '내부 메모 내용' }),
    ).not.toHaveValue('고객에게 보낼 공개 초안')
    await userEvent.click(
      canvas.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    )
    await expect(
      canvas.getByRole('textbox', { name: '공개 답변 내용' }),
    ).toHaveValue('고객에게 보낼 공개 초안')
  },
}

export const WorkspaceConflict: Story = {
  args: { fixtureName: 'workspace-conflict' },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('region', { name: '담당자 저장 충돌' }),
    ).toBeVisible()
  },
}

export const WorkspaceLoading: Story = {
  args: { fixtureName: 'workspace-loading' },
}

export const WorkspaceEmpty: Story = {
  args: { fixtureName: 'workspace-empty' },
}

export const WorkspaceError: Story = {
  args: { fixtureName: 'workspace-error' },
}

export const WorkspaceDenied: Story = {
  args: { fixtureName: 'workspace-denied' },
}
