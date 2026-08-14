import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent } from 'storybook/test'
import { AuditExportStatus } from './AuditExportStatus'
import type { AuditExportJob } from '../../api/types'

const meta = {
  title: '06 Domain & Workspace/AuditExportStatus',
  component: AuditExportStatus,
  parameters: {
    docs: {
      description: {
        component:
          'AUD-002 내보내기 작업 상태 화면. 백엔드에 파일 생성 워커가 아직 없어 상태는 생성 직후 계속 REQUESTED로 고정된다. 자동 폴링이 소진되면 수동 새로고침 버튼이 나타난다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AuditExportStatus>

export default meta
type Story = StoryObj<typeof meta>

const job: AuditExportJob = {
  id: '11111111-1111-4111-8111-111111111111',
  status: 'REQUESTED',
  createdAt: '2026-08-14T09:00:00Z',
  format: 'CSV',
  fields: ['occurredAt', 'action', 'actor'],
  artifact: { state: 'NOT_CREATED', generationAvailable: false },
}

const baseArgs = {
  onRefresh: fn(),
  onRetry: fn(),
}

export const Loading: Story = {
  args: { ...baseArgs, state: { status: 'loading' } },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('내보내기 작업 정보를 불러오고 있습니다.'),
    ).toBeVisible()
  },
}

export const NotFound: Story = {
  args: { ...baseArgs, state: { status: 'not-found' } },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('내보내기 작업을 찾을 수 없습니다.'),
    ).toBeVisible()
  },
}

export const Denied: Story = {
  args: { ...baseArgs, state: { status: 'denied' } },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('내보내기 작업에 접근할 수 없습니다.'),
    ).toBeVisible()
  },
}

export const LoadError: Story = {
  args: {
    ...baseArgs,
    state: { status: 'error', requestId: 'req-status-500' },
  },
  play: async ({ args, canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '다시 시도' }))
    await expect(args.onRetry).toHaveBeenCalled()
  },
}

export const Polling: Story = {
  args: { ...baseArgs, state: { status: 'ready', job, polling: true } },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('생성 중…')).toBeVisible()
    await expect(
      canvas.queryByRole('button', { name: '새로고침' }),
    ).not.toBeInTheDocument()
  },
}

export const PollingExhausted: Story = {
  args: { ...baseArgs, state: { status: 'ready', job, polling: false } },
  play: async ({ args, canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '새로고침' }))
    await expect(args.onRefresh).toHaveBeenCalled()
  },
}
