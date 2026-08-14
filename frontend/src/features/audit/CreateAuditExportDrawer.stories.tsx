import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn, userEvent } from 'storybook/test'
import { CreateAuditExportDrawer } from './CreateAuditExportDrawer'
import type { AuditActivityFilters } from '../../api/types'

const meta = {
  title: '06 Domain & Workspace/CreateAuditExportDrawer',
  component: CreateAuditExportDrawer,
  parameters: {
    docs: {
      description: {
        component:
          'AUD-002 감사 활동 내보내기 요청 폼. 제출은 상위 AuditExplorerPage 컨테이너가 소유하며, 이 컴포넌트는 형식/필드/사유 입력과 검증만 담당한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CreateAuditExportDrawer>

export default meta
type Story = StoryObj<typeof meta>

const filters: AuditActivityFilters = {
  from: '2026-08-07T00:00:00Z',
  limit: 50,
}

const baseArgs = {
  error: null,
  filters,
  onClose: fn(),
  onSubmit: fn(),
  open: true,
  submitting: false,
}

export const Empty: Story = {
  args: baseArgs,
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { name: '감사 활동 내보내기' }),
    ).toBeVisible()
    await expect(canvas.getByLabelText('시각')).toBeChecked()
  },
}

export const MissingReasonValidation: Story = {
  args: baseArgs,
  play: async ({ args, canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: '내보내기 요청' }))
    await expect(
      canvas.getByText('내보내기 사유를 입력해 주세요.'),
    ).toBeVisible()
    await expect(args.onSubmit).not.toHaveBeenCalled()
  },
}

export const SubmitsWithSelectedFields: Story = {
  args: baseArgs,
  play: async ({ args, canvas }) => {
    await userEvent.click(canvas.getByLabelText('레저'))
    await userEvent.type(
      canvas.getByLabelText('사유'),
      '월간 컴플라이언스 점검',
    )
    await userEvent.click(canvas.getByRole('button', { name: '내보내기 요청' }))
    await expect(args.onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        format: 'CSV',
        filters,
        reason: '월간 컴플라이언스 점검',
      }),
    )
  },
}

export const Submitting: Story = {
  args: { ...baseArgs, submitting: true },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('button', { name: '요청 중…' }),
    ).toBeDisabled()
  },
}

export const ServerError: Story = {
  args: {
    ...baseArgs,
    error: { message: '내보내기를 요청할 권한이 없습니다.' },
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByText('내보내기를 요청할 권한이 없습니다.'),
    ).toBeVisible()
  },
}

export const Closed: Story = {
  args: { ...baseArgs, open: false },
}
