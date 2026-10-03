import type { Meta, StoryObj } from '@storybook/react-vite'
import { useEffect } from 'react'
import { Route, Routes, useNavigate } from 'react-router'
import { http, HttpResponse } from 'msw'
import { useCustomerSession } from '../customer-auth/CustomerSessionContext'
import {
  storeRequestAccessToken,
  requestAccessTokenStorageKey,
} from '../customer-portal/customerAccessToken'
import { expect } from 'storybook/test'
import { StoryRoute } from '../../../.storybook/StoryRoute'
import { CustomerSiteLayout } from '../../design-system'
import { CustomerRequestSuccessPage } from './CustomerRequestSuccessPage'

const meta = {
  title: 'Customer Portal/Request Success Page',
  component: CustomerRequestSuccessPage,
  parameters: {
    docs: {
      description: {
        component:
          '문의 접수 직후 계약으로 확인된 문의 번호와 상태, 구현된 다음 행동을 안내하는 고객 전용 완료 화면입니다.',
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerRequestSuccessPage>

export default meta
type Story = StoryObj<typeof meta>

function ConfirmedReceipt() {
  const navigate = useNavigate()
  useEffect(() => {
    void navigate('/requests/submitted/1288', {
      replace: true,
      state: {
        submitted: {
          ticketNumber: 1288,
          status: 'NEW',
          createdAt: '2026-09-01T00:00:00Z',
        },
      },
    })
  }, [navigate])
  return (
    <Routes>
      <Route
        path="/requests/submitted/:ticketNumber"
        element={<CustomerRequestSuccessPage />}
      />
    </Routes>
  )
}
export const Submitted: Story = {
  beforeEach: () => {
    storeRequestAccessToken(sessionStorage, 1288, 'a'.repeat(43))
    return () => sessionStorage.removeItem(requestAccessTokenStorageKey(1288))
  },
  render: () => (
    <CustomerSiteLayout
      session={{
        status: 'authenticated',
        customer: {
          id: 'customer-1',
          email: 'olivia@example.test',
          displayName: 'Olivia Carter',
        },
      }}
    >
      <ConfirmedReceipt />
    </CustomerSiteLayout>
  ),
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { name: '문의 접수가 완료되었습니다' }),
    ).toBeVisible()
    await expect(canvas.getAllByText('#DS-1288')[0]).toBeVisible()
    await expect(
      canvas.queryByText(/예상 첫 답변|4시간 이내/),
    ).not.toBeInTheDocument()
    await expect(
      canvas.queryByRole('heading', { name: '추천 문서' }),
    ).not.toBeInTheDocument()
  },
}

export const Unconfirmed: Story = {
  render: () => (
    <StoryRoute
      path="/requests/submitted/:ticketNumber"
      to="/requests/submitted/1288"
    >
      <CustomerRequestSuccessPage />
    </StoryRoute>
  ),
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('접수 결과를 확인해 주세요.'),
    ).toBeVisible()
    await expect(
      canvas.queryByText('문의 접수가 완료되었습니다'),
    ).not.toBeInTheDocument()
  },
}
export const InvalidNumber: Story = {
  render: () => (
    <StoryRoute
      path="/requests/submitted/:ticketNumber"
      to="/requests/submitted/not-a-number"
    >
      <CustomerRequestSuccessPage />
    </StoryRoute>
  ),
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('문의 번호를 확인해 주세요.'),
    ).toBeVisible()
    await expect(canvas.queryByText(/DS-NaN/)).not.toBeInTheDocument()
  },
}

function AuthenticatedReceipt() {
  return useCustomerSession().status === 'authenticated' ? (
    <ConfirmedReceipt />
  ) : null
}
export const AnonymousReceiptAfterSignIn: Story = {
  ...Submitted,
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/customer/me', () =>
          HttpResponse.json({
            id: '11111111-1111-4111-8111-111111111111',
            email: 'other@example.test',
            displayName: '다른 고객',
            companyName: '',
            verifiedAt: '2026-09-01T00:00:00Z',
            credentialState: 'PASSWORD',
            registrationState: 'COMPLETE',
            availableAuthenticationMethods: ['PASSWORD'],
          }),
        ),
      ],
    },
  },
  render: () => <AuthenticatedReceipt />,
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('link', { name: '문의 보기' }),
    ).toHaveAttribute('href', '/requests/1288')
  },
}
