import { useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, fn } from 'storybook/test'
import { ReplyComposer } from './ReplyComposer'
import type { ComposerMode } from './ticketWorkspaceFixture'

const meta = {
  title: '06 Domain & Workspace/ReplyComposer',
  component: ReplyComposer,
  parameters: {
    docs: {
      description: {
        component:
          'PUBLIC reply와 INTERNAL note를 명시적으로 전환하고 별도 draft를 보존하는 workspace composer다. 현재 production Workspace는 read-only이며 mutation 연결은 ADR 0039의 후속 vertical slice다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof ReplyComposer>

export default meta
type Story = StoryObj<typeof meta>

const baseArgs = {
  draft: '',
  mode: 'public' as const,
  onDraftChange: () => undefined,
  onModeChange: () => undefined,
  onSubmit: () => undefined,
  savedMessage: '',
}

export const Public: Story = {
  args: baseArgs,
  render: () => <ComposerExample initialMode="public" />,
}

export const Internal: Story = {
  args: baseArgs,
  render: () => <ComposerExample initialMode="internal" />,
}

export const Disabled: Story = {
  args: baseArgs,
  render: () => (
    <ComposerExample
      initialMode="public"
      submitDisabledReason="현재 Workspace는 읽기 전용입니다."
    />
  ),
}

export const SeparateDrafts: Story = {
  args: baseArgs,
  render: () => <ComposerExample initialMode="internal" />,
  play: async ({ canvas, userEvent }) => {
    const internalInput = canvas.getByRole('textbox', {
      name: '내부 메모 내용',
    })
    await userEvent.type(internalInput, 'PG 승인 로그 확인')
    await userEvent.click(
      canvas.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    )
    const publicInput = canvas.getByRole('textbox', { name: '공개 답변 내용' })
    await userEvent.type(publicInput, '확인 후 안내드리겠습니다.')
    await userEvent.click(
      canvas.getByRole('tab', { name: '내부 메모 작성 모드로 전환' }),
    )
    await expect(
      canvas.getByRole('textbox', { name: '내부 메모 내용' }),
    ).toHaveValue('PG 승인 로그 확인')
    await userEvent.click(
      canvas.getByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    )
    await expect(publicInput).toHaveValue('확인 후 안내드리겠습니다.')
  },
}

function ComposerExample({
  initialMode,
  submitDisabledReason,
}: {
  initialMode: ComposerMode
  submitDisabledReason?: string
}) {
  const [mode, setMode] = useState(initialMode)
  const [drafts, setDrafts] = useState<Record<ComposerMode, string>>({
    internal: '',
    public: '',
  })

  return (
    <ReplyComposer
      draft={drafts[mode]}
      mode={mode}
      onDraftChange={(draft) =>
        setDrafts((current) => ({ ...current, [mode]: draft }))
      }
      onModeChange={setMode}
      onSubmit={fn()}
      savedMessage=""
      submitDisabledReason={submitDisabledReason}
    />
  )
}
