import { useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import type { RichTextDocumentV1 } from '../../api/types'
import { plainTextDocument } from '../../api/types'
import { SeedRichTextContent } from './SeedRichTextContent'
import { SeedRichTextEditor } from './SeedRichTextEditorLazy'

const formattedDocument: RichTextDocumentV1 = {
  type: 'doc',
  content: [
    {
      type: 'heading',
      attrs: { level: 2 },
      content: [{ type: 'text', text: '결제 확인 안내' }],
    },
    {
      type: 'paragraph',
      content: [
        { type: 'text', text: '승인 기록을 ', marks: [{ type: 'bold' }] },
        {
          type: 'text',
          text: '확인하고 있습니다',
          marks: [{ type: 'italic' }],
        },
        { type: 'text', text: '.' },
      ],
    },
    {
      type: 'bulletList',
      content: [
        {
          type: 'listItem',
          content: [
            {
              type: 'paragraph',
              content: [{ type: 'text', text: '브라우저를 새로고침합니다.' }],
            },
          ],
        },
        {
          type: 'listItem',
          content: [
            {
              type: 'paragraph',
              content: [{ type: 'text', text: '결제 수단을 다시 선택합니다.' }],
            },
          ],
        },
      ],
    },
    {
      type: 'blockquote',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'text', text: '문제가 계속되면 바로 알려 주세요.' },
          ],
        },
      ],
    },
    {
      type: 'codeBlock',
      content: [{ type: 'text', text: 'PAYMENT_RETRY_REQUIRED' }],
    },
  ],
}

function StatefulEditor({
  initial = plainTextDocument(''),
  disabled = false,
  error,
  upload = false,
}: {
  initial?: RichTextDocumentV1
  disabled?: boolean
  error?: string
  upload?: boolean
}) {
  const [document, setDocument] = useState(initial)
  return (
    <div style={{ maxWidth: 760 }}>
      <SeedRichTextEditor
        ariaLabel="답변 내용"
        disabled={disabled}
        error={error}
        onChange={setDocument}
        onUploadImage={
          upload
            ? async (file) => ({
                attachmentId: '33333333-3333-4333-8333-333333333333',
                alt: file.name,
              })
            : undefined
        }
        value={document}
      />
    </div>
  )
}

const meta = {
  title: '03 Components/Seed Rich Text',
  component: StatefulEditor,
  parameters: {
    docs: {
      description: {
        component:
          'RICH_TEXT_V1의 allowlisted JSON만 편집하고 렌더링하는 canonical Deskseed 편집기입니다. HTML, remote image URL, 임의 style은 API에 노출하지 않습니다.',
      },
    },
    layout: 'padded',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof StatefulEditor>

export default meta
type Story = StoryObj<typeof meta>

export const EmptyDocument: Story = {
  render: () => <StatefulEditor />,
  play: async ({ canvas }) => {
    const editor = await canvas.findByRole('textbox', { name: '답변 내용' })
    await userEvent.type(editor, '고객에게 보낼 답변입니다.')
    await expect(editor).toHaveTextContent('고객에게 보낼 답변입니다.')
  },
}

export const FullFormatting: Story = {
  render: () => <StatefulEditor initial={formattedDocument} />,
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('toolbar', { name: '서식 도구' }),
    ).toBeVisible()
    const bold = canvas.getByRole('button', { name: '굵게 (⌘B)' })
    bold.focus()
    await userEvent.keyboard('{ArrowRight}')
    await expect(
      canvas.getByRole('button', { name: '기울임 (⌘I)' }),
    ).toHaveFocus()
  },
}

export const AttachmentImageUpload: Story = {
  render: () => <StatefulEditor upload />,
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('button', { name: '첨부 이미지 삽입' }),
    ).toBeEnabled()
  },
}

export const ReadOnly: Story = {
  render: () => <StatefulEditor disabled initial={formattedDocument} />,
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('textbox', { name: '답변 내용' }),
    ).toHaveAttribute('contenteditable', 'false')
  },
}

export const ValidationError: Story = {
  render: () => <StatefulEditor error="답변 내용을 입력해 주세요." />,
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('alert')).toHaveTextContent(
      '답변 내용을 입력해 주세요.',
    )
  },
}

export const SafeTimelineRenderer: Story = {
  render: () => <SeedRichTextContent document={formattedDocument} />,
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { name: '결제 확인 안내' }),
    ).toBeVisible()
    await expect(canvas.getByText('PAYMENT_RETRY_REQUIRED')).toBeVisible()
  },
}
