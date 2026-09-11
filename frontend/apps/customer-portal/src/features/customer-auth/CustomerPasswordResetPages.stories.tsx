import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect, userEvent } from 'storybook/test'
import {
  CustomerPasswordResetPage,
  CustomerPasswordResetRequestPage,
} from './CustomerPasswordResetPages'
const meta = {
  title: 'Customer Portal/Password Reset',
  component: CustomerPasswordResetPage,
  tags: ['autodocs'],
  parameters: {
    msw: {
      handlers: {
        reset: http.post(
          '/api/v1/customer/auth/password-resets',
          () => new HttpResponse(null, { status: 204 }),
        ),
        resetRequest: http.post(
          '/api/v1/customer/auth/password-reset-requests',
          () => HttpResponse.json({ accepted: true }, { status: 202 }),
        ),
      },
    },
  },
  beforeEach: () => {
    const url = window.location.pathname + window.location.search
    window.history.replaceState(
      window.history.state,
      '',
      url + '#token=synthetic-reset-proof-0000000000000000',
    )
    return () => window.history.replaceState(window.history.state, '', url)
  },
} satisfies Meta<typeof CustomerPasswordResetPage>
export default meta
type Story = StoryObj<typeof meta>
export const Ready: Story = {
  play: async ({ canvas }) => {
    await expect(await canvas.findByLabelText('새 비밀번호')).toBeVisible()
    await expect(window.location.hash).toBe('')
  },
}
export const Complete: Story = {
  play: async ({ canvas }) => {
    await userEvent.type(
      await canvas.findByLabelText('새 비밀번호'),
      'synthetic-password-123',
    )
    await userEvent.click(canvas.getByRole('button', { name: '비밀번호 변경' }))
    await expect(
      await canvas.findByText('비밀번호를 변경했습니다.'),
    ).toBeVisible()
  },
}
export const Expired: Story = {
  parameters: {
    msw: {
      handlers: {
        reset: http.post(
          '/api/v1/customer/auth/password-resets',
          () => new HttpResponse(null, { status: 401 }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      await canvas.findByLabelText('새 비밀번호'),
      'synthetic-password-123',
    )
    await userEvent.click(canvas.getByRole('button', { name: '비밀번호 변경' }))
    await expect(
      await canvas.findByText('재설정 링크를 사용할 수 없습니다.'),
    ).toBeVisible()
  },
}
export const Unavailable: Story = {
  parameters: {
    msw: {
      handlers: {
        reset: http.post(
          '/api/v1/customer/auth/password-resets',
          () => new HttpResponse(null, { status: 503 }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      await canvas.findByLabelText('새 비밀번호'),
      'synthetic-password-123',
    )
    await userEvent.click(canvas.getByRole('button', { name: '비밀번호 변경' }))
    await expect(await canvas.findByRole('alert')).toBeVisible()
    await expect(canvas.getByLabelText('새 비밀번호')).toHaveValue(
      'synthetic-password-123',
    )
  },
}
export const RequestMail: Story = {
  render: () => <CustomerPasswordResetRequestPage />,
  play: async ({ canvas }) => {
    await userEvent.type(
      canvas.getByLabelText('이메일 주소'),
      'customer@example.test',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '재설정 링크 요청' }),
    )
    await expect(
      await canvas.findByText(/재설정 가능한 계정이면/),
    ).toBeVisible()
  },
}
export const RateLimited: Story = {
  render: () => <CustomerPasswordResetRequestPage />,
  parameters: {
    msw: {
      handlers: {
        resetRequest: http.post(
          '/api/v1/customer/auth/password-reset-requests',
          () => new HttpResponse(null, { status: 429 }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      canvas.getByLabelText('이메일 주소'),
      'customer@example.test',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '재설정 링크 요청' }),
    )
    await expect(await canvas.findByText(/요청이 많습니다/)).toBeVisible()
  },
}
