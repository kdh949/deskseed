import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import { CustomerFollowUpForm } from './CustomerFollowUpForm'

const meta = {
  title: '06 Customer/Customer Follow-up Form',
  component: CustomerFollowUpForm,
  parameters: {
    docs: {
      description: {
        component:
          '익명 access token 또는 인증 customer session으로 PUBLIC 답변을 추가하는 폼입니다. 상위 route가 production command를 제공하며, 모호한 오류에서는 동일 clientCommandId를 보존하고 확정 오류에서는 새 명령으로 재시도하게 합니다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerFollowUpForm>

export default meta
type Story = StoryObj<typeof meta>

export const Ready: Story = {
  args: { onSubmit: async () => undefined },
  play: async ({ canvas }) => {
    await userEvent.type(
      canvas.getByLabelText('추가 답변'),
      '추가 확인 자료를 전달합니다.',
    )
    await userEvent.click(canvas.getByRole('button', { name: '답변 보내기' }))
    await expect(canvas.getByText('답변이 저장되었습니다.')).toBeVisible()
  },
}

export const AmbiguousRetry: Story = {
  args: {
    onSubmit: async () => {
      throw new Error('network unavailable')
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      canvas.getByLabelText('추가 답변'),
      '네트워크 재시도 초안',
    )
    await userEvent.click(canvas.getByRole('button', { name: '답변 보내기' }))
    await expect(
      canvas.getByText('답변 전송 결과를 확인할 수 없습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByText(
        '입력한 내용은 그대로 남아 있습니다. 다시 보내도 같은 답변이 두 번 등록되지 않습니다.',
      ),
    ).toBeVisible()
  },
}

export const WithCleanAttachment: Story = {
  args: {
    onSubmit: async () => undefined,
    uploadAttachment: async (file) => ({
      id: '11111111-1111-4111-8111-111111111111',
      fileName: file.name,
      sizeBytes: file.size,
      contentType: file.type || 'application/octet-stream',
      scanStatus: 'CLEAN',
      expiresAt: '2099-08-17T05:00:00Z',
    }),
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      canvas.getByLabelText('추가 답변'),
      '첨부 자료를 전달합니다.',
    )
    await userEvent.upload(
      canvas.getByLabelText('첨부 파일'),
      new File(['safe'], 'evidence.png', { type: 'image/png' }),
    )
    await expect(await canvas.findByText(/^첨부 가능/)).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '답변 보내기' }))
    await expect(canvas.getByText('답변이 저장되었습니다.')).toBeVisible()
  },
}
