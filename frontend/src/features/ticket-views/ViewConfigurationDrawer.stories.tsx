import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { ApiError } from '../../api/client'
import { ViewConfigurationDrawer } from './ViewConfigurationDrawer'

const meta = {
  title: '06 Domain & Workspace/ViewConfigurationDrawer',
  component: ViewConfigurationDrawer,
  parameters: {
    docs: {
      description: {
        component:
          '서버 저장형 PERSONAL/SHARED view의 all/any 조건, 표시 컬럼, 정렬, preview, versioned 저장을 구성하는 drawer다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof ViewConfigurationDrawer>

export default meta
type Story = StoryObj<typeof meta>

export const Create: Story = {
  args: {
    editor: { mode: 'create' },
    onClose: fn(),
    onPreview: fn(async () => ({
      items: [],
      ticketCount: 3,
      ticketCountAsOf: '2026-08-18T03:04:05Z',
      sort: 'updatedAt:desc,ticketNumber:desc' as const,
    })),
    onSave: fn(async () => undefined),
  },
  play: async ({ args, canvas, userEvent }) => {
    await expect(canvas.getByRole('alert')).toHaveTextContent(
      '보기 이름을 입력하세요.',
    )
    await userEvent.type(canvas.getByLabelText('보기 이름'), '결제 문의')
    await userEvent.type(canvas.getByLabelText('설명'), '결제 문의 검토용')
    await userEvent.click(canvas.getByRole('button', { name: 'Preview' }))
    await expect(canvas.getByText('Preview: 정확히 3개')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '보기 만들기' }))
    await expect(args.onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        definition: expect.objectContaining({
          description: '결제 문의 검토용',
        }),
      }),
    )
  },
}

export const Edit: Story = {
  args: {
    editor: {
      mode: 'edit',
      view: {
        id: '11111111-1111-4111-8111-111111111111',
        key: 'personal-risk',
        name: '내 위험 SLA',
        description: '최초 답변 SLA 위험 티켓을 모읍니다.',
        scope: 'PERSONAL',
        ownerStaffId: '22222222-2222-4222-8222-222222222222',
        active: true,
        definitionVersion: 4,
        orderVersion: 2,
        categoryPath: ['내 보기'],
        conditions: {
          version: 1,
          all: [
            {
              field: 'FIRST_REPLY_SLA_STATE',
              operator: 'IN',
              values: ['AT_RISK', 'BREACHED'],
            },
          ],
          any: [],
        },
        columns: ['TICKET_NUMBER', 'SUBJECT', 'FIRST_REPLY_SLA'],
        sort: 'updatedAt:desc,ticketNumber:desc',
        ticketCount: 3,
        ticketCountState: 'EXACT',
        ticketCountAsOf: '2026-08-18T03:04:05Z',
        readScope: 'ALL_TICKETS',
        createdAt: '2026-08-17T00:00:00Z',
        updatedAt: '2026-08-17T01:00:00Z',
      },
    },
    onClose: fn(),
    onMove: fn(),
    onPreview: fn(async () => ({
      items: [],
      ticketCount: 3,
      ticketCountAsOf: '2026-08-18T03:04:05Z',
      sort: 'updatedAt:desc,ticketNumber:desc' as const,
    })),
    onSave: fn(async () => undefined),
    position: { index: 1, total: 3 },
  },
}

export const Closed: Story = {
  args: {
    editor: null,
    onClose: fn(),
    onPreview: fn(async () => ({
      items: [],
      ticketCount: 0,
      ticketCountAsOf: '2026-08-18T03:04:05Z',
      sort: 'updatedAt:desc,ticketNumber:desc' as const,
    })),
    onSave: fn(async () => undefined),
  },
}

export const ConflictRecovery: Story = {
  args: {
    ...Edit.args,
    onReload: fn(async () => undefined),
    onSave: fn(async () => {
      throw new ApiError('conflict', 409)
    }),
  },
  play: async ({ args, canvas, userEvent }) => {
    const description = canvas.getByLabelText('설명')
    await userEvent.clear(description)
    await userEvent.type(description, '저장 전 초안')
    await userEvent.click(canvas.getByRole('button', { name: '변경 저장' }))
    await expect(canvas.getByText('보기 버전 충돌')).toBeVisible()
    await expect(description).toHaveValue('저장 전 초안')
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 버전 다시 불러오기' }),
    )
    await expect(args.onReload).toHaveBeenCalled()
  },
}
