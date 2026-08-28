import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import { CustomerRequestLookupPage } from './CustomerRequestLookupPage'

const meta = {
  title: '06 Customer/Customer Request Lookup Page',
  component: CustomerRequestLookupPage,
  beforeEach: () => {
    window.sessionStorage.clear()
  },
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

export const Empty: Story = {
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', {
        level: 2,
        name: '조회할 문의를 선택하세요',
      }),
    ).toBeVisible()
  },
}

export const InvalidNumber: Story = {
  play: async ({ canvas }) => {
    await userEvent.type(canvas.getByLabelText('문의 번호'), 'abc')
    await userEvent.click(canvas.getByRole('button', { name: '문의 열기' }))
    await expect(canvas.getByText('문의 번호를 확인해 주세요.')).toBeVisible()
  },
}

export const NoSavedEmailLink: Story = {
  play: async ({ canvas }) => {
    await userEvent.type(canvas.getByLabelText('문의 번호'), '1042')
    await userEvent.click(canvas.getByRole('button', { name: '문의 열기' }))
    await expect(
      canvas.getByText('이메일로 받은 문의 링크를 다시 열어 주세요.'),
    ).toBeVisible()
    await expect(
      canvas.queryByText(/이 브라우저|보안을 위해/),
    ).not.toBeInTheDocument()
  },
}
