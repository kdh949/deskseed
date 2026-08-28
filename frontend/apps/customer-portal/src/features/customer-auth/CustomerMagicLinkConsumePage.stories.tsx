import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect } from 'storybook/test'
import { CustomerMagicLinkConsumePage } from './CustomerMagicLinkConsumePage'
import { CustomerSessionProvider } from './CustomerSessionContext'

const meta = {
  title: '06 Customer/Customer Magic-link Consume Page',
  component: CustomerMagicLinkConsumePage,
  decorators: [
    (Story) => (
      <CustomerSessionProvider>
        <Story />
      </CustomerSessionProvider>
    ),
  ],
  parameters: {
    docs: {
      description: {
        component:
          'magic-link fragment를 history에서 먼저 제거한 뒤에만 customer session을 생성하는 route page입니다. 이 story는 token이 없는 안전한 recovery state를 보여 줍니다.',
      },
    },
    msw: {
      handlers: [
        http.get('/api/v1/customer/me', () =>
          HttpResponse.json(
            { title: 'Unauthorized', status: 401 },
            { status: 401 },
          ),
        ),
      ],
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerMagicLinkConsumePage>

export default meta
type Story = StoryObj<typeof meta>

export const MissingFragment: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', {
        name: '로그인 링크를 찾을 수 없습니다.',
      }),
    ).toBeVisible()
    await expect(canvas.queryByText(/fragment/i)).not.toBeInTheDocument()
  },
}
