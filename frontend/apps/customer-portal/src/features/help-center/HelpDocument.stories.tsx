import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { HelpDocument } from './HelpDocument'
const meta = {
  title: 'Customer Portal/Help Document',
  component: HelpDocument,
  tags: ['autodocs'],
  args: {
    blocks: [
      { type: 'heading', level: 2, text: '시작하기' },
      { type: 'paragraph', text: '다음 순서로 진행하세요.' },
      { type: 'list', ordered: true, items: ['설정 열기', '새 항목 선택'] },
      { type: 'code', text: 'const message = "hello";\nconsole.log(message)' },
      { type: 'link', text: '추가 안내', url: 'https://example.test/help' },
      { type: 'quote', text: '도움말 인용' },
      { type: 'callout', text: '변경 내용을 저장하세요.' },
      { type: 'divider' },
    ],
  },
} satisfies Meta<typeof HelpDocument>
export default meta
type Story = StoryObj<typeof meta>
export const Structured: Story = {
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { name: '시작하기', level: 2 }),
    ).toBeVisible()
    await expect(canvas.getAllByRole('listitem')).toHaveLength(2)
    await expect(
      canvas.getByRole('link', { name: '추가 안내' }),
    ).toHaveAttribute('href', 'https://example.test/help')
  },
}
