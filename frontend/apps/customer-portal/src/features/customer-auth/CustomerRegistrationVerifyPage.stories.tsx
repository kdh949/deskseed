import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect } from 'storybook/test'
import { CustomerRegistrationVerifyPage } from './CustomerRegistrationVerifyPage'
const meta = {
  title: 'Customer Portal/Registration Verification',
  component: CustomerRegistrationVerifyPage,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          '같은 브라우저의 확인 정보와 이메일 링크로 가입을 확정합니다. 로그인은 별도이며 오류 시 가입을 다시 요청합니다.',
      },
    },
    msw: {
      handlers: {
        verify: http.post(
          '/api/v1/customer/registration-verifications',
          () => new HttpResponse(null, { status: 204 }),
        ),
      },
    },
  },
  beforeEach: () => {
    const url = window.location.pathname + window.location.search
    window.history.replaceState(
      window.history.state,
      '',
      url + '#token=synthetic-verification-proof',
    )
    return () => window.history.replaceState(window.history.state, '', url)
  },
} satisfies Meta<typeof CustomerRegistrationVerifyPage>
export default meta
type Story = StoryObj<typeof meta>
export const Verified: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '가입이 완료되었습니다.' }),
    ).toBeVisible()
    await expect(window.location.hash).toBe('')
    await expect(canvas.getByRole('link', { name: '로그인' })).toHaveAttribute(
      'href',
      '/customer/sign-in',
    )
  },
}
export const Invalid: Story = {
  parameters: {
    msw: {
      handlers: {
        verify: http.post(
          '/api/v1/customer/registration-verifications',
          () => new HttpResponse(null, { status: 401 }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('가입 확인 링크를 사용할 수 없습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('link', { name: '가입 다시 요청하기' }),
    ).toBeVisible()
  },
}
export const PoliciesChanged: Story = {
  parameters: {
    msw: {
      handlers: {
        verify: http.post(
          '/api/v1/customer/registration-verifications',
          () => new HttpResponse(null, { status: 409 }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('가입 정보를 다시 확인해 주세요.'),
    ).toBeVisible()
  },
}
export const Unavailable: Story = {
  parameters: {
    msw: {
      handlers: {
        verify: http.post(
          '/api/v1/customer/registration-verifications',
          () => new HttpResponse(null, { status: 503 }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('가입 확인을 완료하지 못했습니다.'),
    ).toBeVisible()
  },
}
