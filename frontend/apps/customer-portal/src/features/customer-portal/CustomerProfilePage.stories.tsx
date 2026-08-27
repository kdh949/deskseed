import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect } from 'storybook/test'
import { CustomerSiteLayout } from '../../design-system'
import { CustomerProfilePage } from './CustomerProfilePage'

const customer = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'olivia.carter@example.test',
  displayName: 'Olivia Carter',
  companyName: 'Acme Inc.',
  verifiedAt: '2026-08-20T00:00:00Z',
  credentialState: 'PASSWORD' as const,
  registrationState: 'COMPLETE' as const,
  availableAuthenticationMethods: ['MAGIC_LINK', 'PASSWORD'] as const,
}

const meta = {
  title: 'Customer Portal/Profile Page',
  component: CustomerProfilePage,
  parameters: {
    docs: {
      description: {
        component:
          '현재 확정된 본인 정보 조회 계약만 사용하는 고객 계정 화면입니다. 저장 API가 없으므로 필드는 명시적으로 읽기 전용입니다.',
      },
    },
    layout: 'fullscreen',
    msw: {
      handlers: [
        http.get('/api/v1/customer/me', () => HttpResponse.json(customer)),
      ],
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerProfilePage>

export default meta
type Story = StoryObj<typeof meta>

export const ReadOnly: Story = {
  render: () => (
    <CustomerSiteLayout session={{ status: 'authenticated', customer }}>
      <CustomerProfilePage />
    </CustomerSiteLayout>
  ),
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '계정 설정' }),
    ).toBeVisible()
    await expect(
      canvas.getByDisplayValue('olivia.carter@example.test'),
    ).toBeDisabled()
  },
}
