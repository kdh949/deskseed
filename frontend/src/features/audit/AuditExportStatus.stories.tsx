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
          'AUD-002 내보내기 작업 상태 화면. worker가 private CSV/JSONL artifact를 만들며, READY download와 terminal FAILED/EXPIRED 상태를 명시적으로 구분한다.',
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
  artifact: {
    state: 'PENDING',
    rowCount: null,
    sizeBytes: null,
    checksumSha256: null,
    expiresAt: null,
    contentType: null,
    failureCode: null,
  },
}

const readyJob: AuditExportJob = {
  ...job,
  status: 'READY',
  artifact: {
    state: 'READY',
    rowCount: 184,
    sizeBytes: 24576,
    checksumSha256:
      'ea3582c0eacf31ba0ad2157f7e8cc8b5c16d21a1c74b4740269f349da1c9d2d2',
    expiresAt: '2026-08-14T10:00:00Z',
    contentType: 'text/csv',
    failureCode: null,
  },
}

const baseArgs = {
  onDownload: fn(),
  onRegenerate: fn(),
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

export const Running: Story = {
  args: {
    ...baseArgs,
    state: {
      status: 'ready',
      job: { ...job, status: 'RUNNING' },
      polling: true,
    },
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('생성 중')).toBeVisible()
    await expect(canvas.getByText('생성 중…')).toBeVisible()
  },
}

export const ReadyForDownload: Story = {
  args: {
    ...baseArgs,
    state: { status: 'ready', job: readyJob, polling: false },
  },
  play: async ({ args, canvas }) => {
    await expect(canvas.getByText('파일이 준비되었습니다.')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '다운로드' }))
    await expect(args.onDownload).toHaveBeenCalled()
  },
}

export const Expired: Story = {
  args: {
    ...baseArgs,
    state: {
      status: 'ready',
      job: {
        ...readyJob,
        status: 'EXPIRED',
        artifact: {
          ...readyJob.artifact,
          state: 'EXPIRED',
          failureCode: 'EXPIRED',
        },
      },
      polling: false,
    },
  },
  play: async ({ args, canvas }) => {
    await expect(
      canvas.getByText('내보내기 파일이 만료되었습니다.'),
    ).toBeVisible()
    await expect(
      canvas.queryByRole('button', { name: '다운로드' }),
    ).not.toBeInTheDocument()
    await userEvent.click(
      canvas.getByRole('button', { name: '새 내보내기 요청' }),
    )
    await expect(args.onRegenerate).toHaveBeenCalled()
  },
}
