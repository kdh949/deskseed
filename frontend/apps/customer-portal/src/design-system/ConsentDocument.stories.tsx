import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { ConsentDocument } from './ConsentDocument'
const meta = {
  title: 'Customer Design System/Consent Document',
  component: ConsentDocument,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          '검증된 동의 문서의 blocks를 읽기 전용으로 표시합니다. heading은 level 2/3, list는 ordered와 items, link는 text와 검증된 HTTPS url을 받습니다. HTML·첨부·편집 기능은 제공하지 않습니다.',
      },
    },
  },
  args: {
    blocks: [
      { type: 'heading', level: 2, text: '이용 조건' },
      { type: 'paragraph', text: '각 조항과 안내를 확인해 주세요.' },
      { type: 'heading', level: 3, text: '동의 절차' },
      { type: 'list', ordered: true, items: ['내용 확인', '동의 선택'] },
      {
        type: 'list',
        ordered: false,
        items: ['선택 동의는 거부할 수 있습니다.'],
      },
      { type: 'quote', text: '고객에게 안내된 문서를 기준으로 합니다.' },
      { type: 'callout', text: '변경된 내용은 다시 확인해 주세요.' },
      { type: 'divider' },
      { type: 'link', text: '정책 원문', url: 'https://example.test/policy' },
    ],
  },
} satisfies Meta<typeof ConsentDocument>
export default meta
type Story = StoryObj<typeof meta>
export const StructuredPolicy: Story = {
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { name: '이용 조건', level: 2 }),
    ).toBeVisible()
    await expect(canvas.getAllByRole('list')[0]!.tagName).toBe('OL')
    await expect(
      canvas.getByRole('link', { name: '정책 원문' }),
    ).toHaveAttribute('href', 'https://example.test/policy')
  },
}
