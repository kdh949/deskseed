import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
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
      sort: 'updatedAt:desc,ticketNumber:desc' as const,
    })),
    onSave: fn(async () => undefined),
  },
  play: async ({ args, canvas, userEvent }) => {
    await expect(canvas.getByRole('alert')).toHaveTextContent(
      '보기 이름을 입력하세요.',
    )
    await userEvent.type(canvas.getByLabelText('보기 이름'), '결제 문의')
    await userEvent.click(canvas.getByRole('button', { name: 'Preview' }))
    await expect(canvas.getByText('Preview: 정확히 3개')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '보기 만들기' }))
    await expect(args.onSave).toHaveBeenCalled()
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
      sort: 'updatedAt:desc,ticketNumber:desc' as const,
    })),
    onSave: fn(async () => undefined),
  },
}
