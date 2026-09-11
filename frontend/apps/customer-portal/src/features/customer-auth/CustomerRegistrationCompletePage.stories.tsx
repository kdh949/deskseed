import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { Navigate, Route, Routes } from 'react-router'
import { expect, userEvent } from 'storybook/test'
import { CustomerRegistrationCompletePage } from './CustomerRegistrationCompletePage'
import { CustomerAccountRoute } from './CustomerAccountRoute'
const customer = {
  id: 'customer-completion',
  email: 'customer@example.test',
  displayName: '고객',
  companyName: '회사',
  verifiedAt: '2026-09-01T00:00:00Z',
  credentialState: 'PASSWORDLESS',
  registrationState: 'REGISTRATION_REQUIRED',
  availableAuthenticationMethods: ['MAGIC_LINK'],
}
const meta = {
  title: 'Customer Portal/Registration Completion',
  component: CustomerRegistrationCompletePage,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          '로그인했지만 가입이 미완료인 고객이 비밀번호와 현재 약관을 등록한 뒤 원래 문의로 돌아갑니다.',
      },
    },
    msw: {
      handlers: {
        customerSession: http.get('/api/v1/customer/me', () =>
          HttpResponse.json(customer),
        ),
        consentPolicies: http.get('/api/v1/customer/consent-policies', () =>
          HttpResponse.json({
            context: 'REGISTRATION',
            policies: [
              {
                policyKey: 'terms',
                version: 1,
                title: '가입 약관',
                required: true,
                document: {
                  schemaVersion: 1,
                  blocks: [
                    {
                      type: 'paragraph',
                      text: '합성 가입 약관의 전체 내용입니다.',
                    },
                  ],
                },
              },
            ],
          }),
        ),
        csrf: http.get('/api/v1/customer/csrf', () =>
          HttpResponse.json({
            token: 's'.repeat(32),
            headerName: 'X-CSRF-TOKEN',
          }),
        ),
        completion: http.put('/api/v1/customer/me/registration', () =>
          HttpResponse.json({
            ...customer,
            credentialState: 'PASSWORD',
            registrationState: 'COMPLETE',
            availableAuthenticationMethods: ['PASSWORD'],
          }),
        ),
      },
    },
  },
  render: () => (
    <Routes>
      <Route
        path="/customer/register/complete"
        element={<CustomerRegistrationCompletePage />}
      />
      <Route element={<CustomerAccountRoute />}>
        <Route path="/account/requests/24" element={<h1>문의 24</h1>} />
      </Route>
      <Route
        path="*"
        element={<Navigate replace to="/account/requests/24" />}
      />
    </Routes>
  ),
} satisfies Meta<typeof CustomerRegistrationCompletePage>
export default meta
type Story = StoryObj<typeof meta>
export const CompleteAndReturn: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '가입 마무리' }),
    ).toBeVisible()
    await userEvent.type(
      canvas.getByLabelText(/비밀번호/),
      'synthetic-password-123',
    )
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '가입 약관에 동의합니다. (필수)' }),
    )
    await userEvent.click(canvas.getByRole('button', { name: '가입 완료' }))
    await expect(
      await canvas.findByRole('heading', { name: '문의 24' }),
    ).toBeVisible()
  },
}
export const DeniedPreservesInput: Story = {
  parameters: {
    msw: {
      handlers: {
        completion: http.put(
          '/api/v1/customer/me/registration',
          () => new HttpResponse(null, { status: 403 }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '가입 마무리' }),
    ).toBeVisible()
    await userEvent.type(
      canvas.getByLabelText(/비밀번호/),
      'synthetic-password-123',
    )
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '가입 약관에 동의합니다. (필수)' }),
    )
    await userEvent.click(canvas.getByRole('button', { name: '가입 완료' }))
    await expect(await canvas.findByRole('alert')).toBeVisible()
    await expect(canvas.getByLabelText(/비밀번호/)).toHaveValue(
      'synthetic-password-123',
    )
  },
}
