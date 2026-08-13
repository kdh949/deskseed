import { useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { DsTabs } from './DeskseedControls'

type TabId = 'public' | 'internal'

const meta = {
  title: '02 Primitives/DsTabs',
  component: DsTabs,
  parameters: {
    docs: {
      description: {
        component:
          '한 region 안에서 상호 배타적인 panel을 전환한다. active tab만 tab order에 두고 ArrowLeft/ArrowRight/Home/End로 focus와 selection을 함께 이동한다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof DsTabs>

export default meta
type Story = StoryObj<typeof meta>

const baseArgs = {
  activeId: 'public',
  ariaLabel: '답변 공개 범위',
  items: [
    { id: 'public', label: 'Public reply', panelId: 'tabs-public' },
    { id: 'internal', label: 'Internal note', panelId: 'tabs-internal' },
  ],
  onChange: () => undefined,
}

export const Default: Story = {
  args: baseArgs,
  render: () => <TabsExample initialTab="public" />,
}

export const SecondTabSelected: Story = {
  args: baseArgs,
  render: () => <TabsExample initialTab="internal" />,
}

export const KeyboardNavigation: Story = {
  args: baseArgs,
  render: () => <TabsExample initialTab="public" />,
  play: async ({ canvas, userEvent }) => {
    const publicTab = canvas.getByRole('tab', { name: 'Public reply' })
    const internalTab = canvas.getByRole('tab', { name: 'Internal note' })
    publicTab.focus()
    await userEvent.keyboard('{ArrowRight}')
    await expect(internalTab).toHaveFocus()
    await expect(internalTab).toHaveAttribute('aria-selected', 'true')
    await expect(
      canvas.getByRole('tabpanel', { name: 'Internal note' }),
    ).toBeVisible()
    await userEvent.keyboard('{Home}')
    await expect(publicTab).toHaveFocus()
  },
}

function TabsExample({ initialTab }: { initialTab: TabId }) {
  const [activeId, setActiveId] = useState<TabId>(initialTab)
  const label = activeId === 'public' ? 'Public reply' : 'Internal note'

  return (
    <section aria-label="Composer mode example">
      <DsTabs
        activeId={activeId}
        ariaLabel="답변 공개 범위"
        items={[
          { id: 'public', label: 'Public reply', panelId: 'tabs-public' },
          { id: 'internal', label: 'Internal note', panelId: 'tabs-internal' },
        ]}
        onChange={setActiveId}
      />
      <div
        aria-labelledby={`tabs-${activeId}-tab`}
        id={`tabs-${activeId}`}
        role="tabpanel"
      >
        {label} panel
      </div>
    </section>
  )
}
