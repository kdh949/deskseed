import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect, userEvent } from 'storybook/test'
import { Navigate, Route, Routes } from 'react-router'
import { CustomerSiteLayout } from '../../design-system'
import { CustomerCheckEmailPage } from './CustomerCheckEmailPage'
import { CustomerRegisterPage } from './CustomerRegisterPage'

const meta = {
  title: 'Customer Portal/Onboarding Pages',
  component: CustomerRegisterPage,
  parameters: {
    docs: {
      description: {
        component:
          '확정된 고객 가입 및 매직 링크 계약에 맞춘 회원가입과 이메일 확인 화면입니다.',
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerRegisterPage>

export default meta
type Story = StoryObj<typeof meta>

export const Registration: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/customer/consent-policies', () =>
          HttpResponse.json({
            context: 'REGISTRATION',
            policies: [
              {
                policyKey: 'terms',
                version: 3,
                title: '이용약관',
                required: true,
                document: {
                  schemaVersion: 1,
                  blocks: [
                    {
                      type: 'paragraph',
                      text: '가입 동의 내용을 확인해 주세요.',
                    },
                  ],
                },
              },
              {
                policyKey: 'privacy',
                version: 2,
                title: '개인정보 처리방침',
                required: true,
                document: {
                  schemaVersion: 1,
                  blocks: [
                    {
                      type: 'paragraph',
                      text: '가입 동의 내용을 확인해 주세요.',
                    },
                  ],
                },
              },
            ],
          }),
        ),
      ],
    },
  },
  render: () => (
    <CustomerSiteLayout session={{ status: 'anonymous' }}>
      <CustomerRegisterPage />
    </CustomerSiteLayout>
  ),
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: 'DeskSeed 계정 만들기' }),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '계정 만들기' }),
    ).toBeDisabled()
    await userEvent.click(canvas.getByText('이용약관 내용 보기'))
    await expect(
      canvas.getAllByText('가입 동의 내용을 확인해 주세요.')[0],
    ).toBeVisible()
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '이용약관에 동의합니다. (필수)' }),
    )
    await expect(
      canvas.getByRole('button', { name: '계정 만들기' }),
    ).toBeDisabled()
    await userEvent.click(
      canvas.getByRole('checkbox', {
        name: '개인정보 처리방침에 동의합니다. (필수)',
      }),
    )
    await expect(
      canvas.getByRole('button', { name: '계정 만들기' }),
    ).toBeEnabled()
  },
}

export const CheckEmail: Story = {
  render: () => (
    <CustomerSiteLayout session={{ status: 'anonymous' }}>
      <CustomerCheckEmailPage />
    </CustomerSiteLayout>
  ),
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { name: '받은 편지함을 확인해 주세요' }),
    ).toBeVisible()
  },
}

export const RegistrationEmail: Story = {
  render: () => (
    <Routes>
      <Route path="/registration-email" element={<CustomerCheckEmailPage />} />
      <Route
        path="*"
        element={
          <Navigate
            replace
            to="/registration-email"
            state={{ email: 'customer@example.test', purpose: 'registration' }}
          />
        }
      />
    </Routes>
  ),
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText(/가입을 요청한 브라우저/),
    ).toBeVisible()
    await expect(
      canvas.queryByRole('button', { name: '링크 다시 보내기' }),
    ).not.toBeInTheDocument()
    await expect(
      canvas.getByRole('link', { name: '가입 정보 다시 입력하기' }),
    ).toHaveAttribute('href', '/customer/register')
  },
}
