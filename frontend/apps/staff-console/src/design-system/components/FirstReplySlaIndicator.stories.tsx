import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { FirstReplySlaIndicator } from './FirstReplySlaIndicator'

const meta = {
  title: '03 Components/FirstReplySlaIndicator',
  component: FirstReplySlaIndicator,
  parameters: {
    docs: {
      description: {
        component:
          '서버가 계산한 최초 답변 SLA 상태와 절대 기한을 텍스트와 아이콘으로 표시한다. 브라우저에서 업무시간 잔여 시간을 계산하지 않는다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof FirstReplySlaIndicator>

export default meta
type Story = StoryObj<typeof meta>

export const AtRisk: Story = {
  args: {
    sla: {
      metric: 'FIRST_REPLY',
      state: 'AT_RISK',
      dueAt: '2026-08-17T04:30:00Z',
      targetMinutes: 60,
      policyVersion: 3,
      scheduleVersion: 7,
    },
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByLabelText('최초 답변 SLA 위험')).toHaveTextContent(
      '기한',
    )
  },
}

export const WorkspaceDetail: Story = {
  args: {
    detail: true,
    sla: {
      metric: 'FIRST_REPLY',
      state: 'BREACHED',
      dueAt: '2026-08-17T01:00:00Z',
      targetMinutes: 90,
      policyVersion: 3,
      scheduleVersion: 7,
    },
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('목표 1시간 30분')).toBeVisible()
    await expect(canvas.getByText('정책 v3')).toBeVisible()
    await expect(canvas.getByText('일정 v7')).toBeVisible()
  },
}

export const NoPolicy: Story = {
  args: {
    sla: {
      metric: 'FIRST_REPLY',
      state: 'NO_POLICY',
      dueAt: null,
      targetMinutes: null,
      policyVersion: null,
      scheduleVersion: null,
    },
  },
}
