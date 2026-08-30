import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect } from 'storybook/test'
import { StoryRoute } from '../../../.storybook/StoryRoute'
import { requestAccessTokenStorageKey } from '../customer-portal/customerAccessToken'
import { AnonymousRequestDetailPage } from './AnonymousRequestDetailPage'

const ticketNumber = 1042
const accessToken = 'a'.repeat(43)

const meta = {
  title: '07 Screens/Anonymous Request Detail Page',
  component: AnonymousRequestDetailPage,
  beforeEach: () => {
    window.sessionStorage.setItem(
      requestAccessTokenStorageKey(ticketNumber),
      accessToken,
    )
    return () =>
      window.sessionStorage.removeItem(
        requestAccessTokenStorageKey(ticketNumber),
      )
  },
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/requests/1042', () =>
          HttpResponse.json({
            ticketNumber,
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
          }),
        ),
      ],
    },
  },
  render: () => (
    <StoryRoute path="/requests/:ticketNumber" to="/requests/1042">
      <AnonymousRequestDetailPage />
    </StoryRoute>
  ),
  tags: ['autodocs'],
} satisfies Meta<typeof AnonymousRequestDetailPage>

export default meta
type Story = StoryObj<typeof meta>

export const EmailLinkConversation: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '#1042 결제 확인 요청' }),
    ).toBeVisible()
    await expect(
      canvas.getByText('결제 승인 내역을 확인해 주세요.'),
    ).toBeVisible()
  },
}
