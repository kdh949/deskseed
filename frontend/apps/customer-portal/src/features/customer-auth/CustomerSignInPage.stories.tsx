import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { Navigate, Route, Routes } from 'react-router'
import { expect, userEvent } from 'storybook/test'
import { CustomerCheckEmailPage } from './CustomerCheckEmailPage'
import { CustomerSignInPage } from './CustomerSignInPage'

function SignInFlowStory() {
  return (
    <Routes>
      <Route element={<CustomerSignInPage />} path="/customer/sign-in" />
      <Route
        element={<CustomerCheckEmailPage />}
        path="/customer/sign-in/check-email"
      />
      <Route element={<Navigate replace to="/customer/sign-in" />} path="*" />
    </Routes>
  )
}

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
      handlers: {
        magicLinkRequest: http.post(
          '/api/v1/customer/auth/magic-link-requests',
          () => HttpResponse.json({ accepted: true }, { status: 202 }),
        ),
      },
    },
  },
  render: () => <SignInFlowStory />,
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerSignInPage>

export default meta
type Story = StoryObj<typeof meta>

export const RequestLink: Story = {
  play: async ({ canvas }) => {
    await userEvent.type(
      canvas.getByRole('textbox', { name: /이메일 주소/ }),
      'mina@example.test',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '로그인 링크 보내기' }),
    )
    await expect(
      await canvas.findByRole('heading', {
        name: '받은 편지함을 확인해 주세요',
      }),
    ).toBeVisible()
    await expect(canvas.getByText('mina@example.test')).toBeVisible()
  },
}

export const ServiceUnavailable: Story = {
  parameters: {
    msw: {
      handlers: {
        magicLinkRequest: http.post(
          '/api/v1/customer/auth/magic-link-requests',
          () => new HttpResponse(null, { status: 503 }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      canvas.getByRole('textbox', { name: /이메일 주소/ }),
      'mina@example.test',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '로그인 링크 보내기' }),
    )
    await expect(
      await canvas.findByText('로그인 요청을 완료할 수 없습니다.'),
    ).toBeVisible()
  },
}
