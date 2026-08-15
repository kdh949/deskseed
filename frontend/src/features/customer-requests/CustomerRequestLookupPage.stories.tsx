import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import { CustomerRequestLookupPage } from './CustomerRequestLookupPage'

const meta = {
  title: '06 Customer/Customer Request Lookup Page',
  component: CustomerRequestLookupPage,
  parameters: {
    docs: {
      description: {
        component:
          '이 브라우저의 ticket-scoped sessionStorage에 이미 안전하게 보관된 요청 access proof가 있을 때만 문의를 다시 여는 화면입니다. capability token 입력란을 제공하지 않습니다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerRequestLookupPage>

export default meta
type Story = StoryObj<typeof meta>

export const NoSavedEmailLink: Story = {
  play: async ({ canvas }) => {
    await userEvent.type(canvas.getByLabelText('문의 번호'), '1042')
    await userEvent.click(canvas.getByRole('button', { name: '문의 열기' }))
    await expect(
      canvas.getByText('이메일 문의 링크가 필요합니다.'),
    ).toBeVisible()
  },
}
