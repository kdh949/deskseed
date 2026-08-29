import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect, userEvent } from 'storybook/test'
import { StoryRoute } from '../../../.storybook/StoryRoute'
import { CustomerRequestDetailPage } from './CustomerRequestDetailPage'

const detail = {
  ticketNumber: 1042,
  subject: '결제 확인 요청',
  status: 'OPEN',
  createdAt: '2026-08-15T00:00:00Z',
  updatedAt: '2026-08-15T01:00:00Z',
  comments: [
    {
      id: 'comment-public-1',
      authorDisplayName: '김민아',
      body: '결제 승인 내역을 확인해 주세요.',
      content: {
        format: 'PLAIN_TEXT',
        text: '결제 승인 내역을 확인해 주세요.',
      },
      createdAt: '2026-08-15T00:00:00Z',
      attachments: [],
    },
  ],
}

const meta = {
  title: '07 Screens/Customer Request Detail Page',
  component: CustomerRequestDetailPage,
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
        requestDetail: http.get('/api/v1/customer/requests/1042', () =>
          HttpResponse.json(detail),
        ),
      },
    },
  },
  render: () => (
    <StoryRoute
      path="/account/requests/:ticketNumber"
      to="/account/requests/1042"
    >
      <CustomerRequestDetailPage />
    </StoryRoute>
  ),
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerRequestDetailPage>

export default meta
type Story = StoryObj<typeof meta>

export const PublicConversation: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole(
        'heading',
        { name: '#1042 결제 확인 요청' },
        { timeout: 5000 },
      ),
    ).toBeVisible()
    await userEvent.type(
      canvas.getByLabelText('추가 답변'),
      '결제 수단을 다시 확인했습니다.',
    )
    await expect(
      canvas.getByRole('button', { name: '답변 보내기' }),
    ).toBeEnabled()
  },
}
