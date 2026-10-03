import { useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import { SeedTicketTabs } from './SeedTicketTabs'

const meta = {
  title: '04 Patterns/Seed Ticket Tabs',
  component: SeedTicketTabs,
  parameters: {
    docs: {
      description: {
        component:
          '열린 티켓의 route 탐색 패턴이다. items는 id/label/href/active와 선택적 hasDraft를 받는다. hasDraft는 미제출 텍스트와 링크 설명을 표시하며 원격 저장 여부를 뜻하지 않는다. onClose는 목록 제거 요청이며 권한·저장·route blocker는 소비자 소유다. route link와 aria-current를 사용하고 방향키/Home/End는 링크 사이 focus만 이동한다. Enter로 이동한다. 빈 목록은 렌더링하지 않는다.',
      },
    },
  },
} satisfies Meta<typeof SeedTicketTabs>
export default meta
type Story = StoryObj<typeof meta>
export const OpenAndClose: Story = {
  args: {
    items: [
      {
        id: '1042',
        label: '#1042',
        href: '/agent/tickets/1042',
        active: true,
        hasDraft: true,
      },
      {
        id: '1043',
        label: '#1043',
        href: '/agent/tickets/1043',
        active: false,
      },
    ],
    onClose: () => undefined,
  },
  render: function TicketTabsExample(args) {
    const [items, setItems] = useState(args.items)
    return (
      <SeedTicketTabs
        items={items}
        onClose={(id) =>
          setItems((current) => current.filter((item) => item.id !== id))
        }
      />
    )
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('link', { name: '#1042' }),
    ).toHaveAccessibleDescription('미제출')
    await expect(canvas.getByText('미제출')).toBeVisible()
    canvas.getByRole('link', { name: '#1042' }).focus()
    await userEvent.keyboard('{ArrowRight}')
    await expect(canvas.getByRole('link', { name: '#1043' })).toHaveFocus()
    await userEvent.click(canvas.getByRole('button', { name: '#1043 닫기' }))
    await expect(
      canvas.queryByRole('link', { name: '#1043' }),
    ).not.toBeInTheDocument()
    await expect(canvas.getByRole('link', { name: '#1042' })).toHaveFocus()
  },
}
