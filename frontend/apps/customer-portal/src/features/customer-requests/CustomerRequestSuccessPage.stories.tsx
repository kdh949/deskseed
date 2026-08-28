import type { Meta, StoryObj } from '@storybook/react-vite'
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

export const Submitted: Story = {
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
      <StoryRoute
        path="/requests/submitted/:ticketNumber"
        to="/requests/submitted/1288"
      >
        <CustomerRequestSuccessPage />
      </StoryRoute>
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
