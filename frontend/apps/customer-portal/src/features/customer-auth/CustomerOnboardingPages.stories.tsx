import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect } from 'storybook/test'
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
              },
              {
                policyKey: 'privacy',
                version: 2,
                title: '개인정보 처리방침',
                required: true,
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
