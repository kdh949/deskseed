import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect, userEvent } from 'storybook/test'
import { CustomerSignInPage } from './CustomerSignInPage'

const meta = {
  title: '06 Customer/Customer Sign-in Page',
  component: CustomerSignInPage,
  parameters: {
    docs: {
      description: {
        component:
          '고객 이메일 매직 링크 요청 화면입니다. 계정 존재 여부를 드러내지 않는 202 accepted 안내만 보여 주고, magic-link token은 이 화면에서 다루지 않습니다.',
      },
    },
    msw: {
      handlers: [
        http.post('/api/v1/customer/auth/magic-link-requests', () =>
          HttpResponse.json({ accepted: true }, { status: 202 }),
        ),
      ],
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerSignInPage>

export default meta
type Story = StoryObj<typeof meta>

export const RequestLink: Story = {
  play: async ({ canvas }) => {
    await userEvent.type(canvas.getByLabelText('이메일'), 'mina@example.test')
    await userEvent.click(
      canvas.getByRole('button', { name: '로그인 링크 보내기' }),
    )
    await expect(
      canvas.getByText(
        '입력한 이메일 주소가 유효하면 로그인 링크를 보냈습니다.',
      ),
    ).toBeVisible()
  },
}

export const ServiceUnavailable: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post(
          '/api/v1/customer/auth/magic-link-requests',
          () => new HttpResponse(null, { status: 503 }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(canvas.getByLabelText('이메일'), 'mina@example.test')
    await userEvent.click(
      canvas.getByRole('button', { name: '로그인 링크 보내기' }),
    )
    await expect(
      canvas.getByText('로그인 링크를 요청할 수 없습니다.'),
    ).toBeVisible()
  },
}
