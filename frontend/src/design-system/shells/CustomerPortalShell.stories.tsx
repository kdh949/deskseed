import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { CustomerPortalShell } from './CustomerPortalShell'

const meta = {
  component: CustomerPortalShell,
  tags: ['ai-generated'],
} satisfies Meta<typeof CustomerPortalShell>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {
    children: (
      <section aria-labelledby="story-request-title">
        <p className="eyebrow">새 문의</p>
        <h1 id="story-request-title">무엇을 도와드릴까요?</h1>
        <p>지원팀이 확인한 뒤 이메일로 안내해 드립니다.</p>
      </section>
    ),
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('main')).toHaveAttribute(
      'id',
      'main-content',
    )
  },
}
