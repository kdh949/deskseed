import { useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent, fn } from 'storybook/test'
import {
  ConsentDocumentEditor,
  ConsentDocumentView,
} from './CustomerConsentDocumentFields'
import type { ConsentBlock } from './customerConsentApi'

const blocks: ConsentBlock[] = [
  { type: 'heading', level: 2, text: '합성 정책 제목' },
  { type: 'heading', level: 3, text: '합성 정책 소제목' },
  { type: 'paragraph', text: '정책 편집 검증을 위한 합성 문서입니다.' },
  { type: 'list', ordered: false, items: ['합성 첫 항목', '합성 둘째 항목'] },
  { type: 'callout', text: '합성 안내' },
  { type: 'quote', text: '합성 인용' },
  { type: 'divider' },
  { type: 'link', text: '합성 문서', url: 'https://example.test/policy' },
]
const meta = {
  title: '06 Admin/Customer Consent Document Fields',
  component: ConsentDocumentEditor,
  tags: ['autodocs'],
  args: { blocks, change: fn() },
  parameters: {
    docs: {
      description: {
        component:
          '동의 정책 전용 canonical 7개 block 편집/안전한 조회 구성입니다. 신규 문구를 제공하지 않으며 raw HTML이나 code/attachment block을 허용하지 않습니다. 실제 정책은 담당자가 검토하여 작성합니다.',
      },
    },
  },
} satisfies Meta<typeof ConsentDocumentEditor>
export default meta
type Story = StoryObj<typeof meta>

export const Editable: Story = {
  render: function Render(args) {
    const [current, setCurrent] = useState(args.blocks)
    return (
      <div className="admin-surface">
        <ConsentDocumentEditor
          blocks={current}
          change={(next) => {
            setCurrent(next)
            args.change(next)
          }}
        />
      </div>
    )
  },
  play: async ({ canvas, args }) => {
    const content = canvas.getByLabelText('항목 1 내용')
    await userEvent.click(content)
    await userEvent.keyboard('{End} 개정')
    await expect(args.change).toHaveBeenLastCalledWith(
      expect.arrayContaining([
        { type: 'heading', level: 2, text: '합성 정책 제목 개정' },
      ]),
    )
    await userEvent.tab()
    await expect(canvas.getAllByLabelText('제목 단계')[0]).toHaveFocus()
    await userEvent.click(
      canvas.getByRole('button', { name: '문서 항목 추가' }),
    )
    await expect(canvas.getByLabelText('항목 9 내용')).toHaveValue('')
    await userEvent.click(canvas.getByRole('button', { name: '항목 9 삭제' }))
    await expect(canvas.queryByLabelText('항목 9 내용')).not.toBeInTheDocument()
  },
}
export const ReadOnlyDocument: Story = {
  render: () => (
    <main className="admin-surface">
      <h1>고객 동의 정책</h1>
      <h2>합성 정책</h2>
      <h3>발행 이력</h3>
      <ConsentDocumentView blocks={blocks} />
    </main>
  ),
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { level: 4, name: '합성 정책 제목' }),
    ).toBeVisible()
    await expect(canvas.getByRole('list')).toBeVisible()
    await expect(
      canvas.getByRole('link', { name: '합성 문서' }),
    ).toHaveAttribute('href', 'https://example.test/policy')
    await expect(canvas.queryByRole('textbox')).not.toBeInTheDocument()
  },
}
