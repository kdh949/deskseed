import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { NewRequestPage } from './NewRequestPage'

const meta = {
  component: NewRequestPage,
  tags: ['ai-generated'],
} satisfies Meta<typeof NewRequestPage>

export default meta
type Story = StoryObj<typeof meta>

export const ValidSubmission: Story = {
  play: async ({ canvas, userEvent }) => {
    await userEvent.type(canvas.getByLabelText(/^이름/), '홍길동')
    await userEvent.type(canvas.getByLabelText(/^이메일/), 'hong@example.com')
    await userEvent.type(
      canvas.getByLabelText(/^제목/),
      '결제 영수증 재발급 요청',
    )
    await userEvent.type(
      canvas.getByLabelText(/문의 내용/),
      '결제 영수증을 다시 받을 수 있는지 확인 부탁드립니다.',
    )
    await userEvent.click(canvas.getByRole('button', { name: '문의 접수' }))
    await expect(
      await canvas.findByRole('heading', { name: '문의 #101' }),
    ).toBeVisible()
  },
}
