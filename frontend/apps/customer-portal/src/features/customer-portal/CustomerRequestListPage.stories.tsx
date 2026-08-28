import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect } from 'storybook/test'
import { StoryRoute } from '../../../.storybook/StoryRoute'
import { CustomerRequestListPage } from './CustomerRequestListPage'

const meta = {
  title: '07 Screens/Customer Request List Page',
  component: CustomerRequestListPage,
  parameters: {
    msw: {
      handlers: {
        customerSession: http.get('/api/v1/customer/me', () =>
          HttpResponse.json({
            id: '11111111-1111-4111-8111-111111111111',
            email: 'customer@example.test',
            displayName: '고객 A',
            verifiedAt: '2026-08-15T00:00:00Z',
            companyName: null,
            credentialState: 'PASSWORDLESS',
            registrationState: 'COMPLETE',
            availableAuthenticationMethods: ['MAGIC_LINK'],
          }),
        ),
        customerRequests: http.get('/api/v1/customer/requests', () =>
          HttpResponse.json({
            items: [
              {
                ticketNumber: 1042,
                subject: '결제 확인 요청',
                status: 'OPEN',
                createdAt: '2026-08-14T00:00:00Z',
                updatedAt: '2026-08-15T01:00:00Z',
              },
            ],
            nextCursor: null,
          }),
        ),
      },
    },
  },
  render: () => (
    <StoryRoute path="/account/requests" to="/account/requests">
      <CustomerRequestListPage />
    </StoryRoute>
  ),
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerRequestListPage>

export default meta
type Story = StoryObj<typeof meta>

export const Requests: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole(
        'link',
        { name: /#1042 결제 확인 요청/ },
        { timeout: 5000 },
      ),
    ).toBeVisible()
  },
}
