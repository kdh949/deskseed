import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect } from 'storybook/test'
import { CustomerSessionProvider } from '../customer-auth/CustomerSessionContext'
import { CustomerRequestCreatePage } from './CustomerRequestCreatePage'

const anonymousSessionHandler = http.get('/api/v1/customer/me', () =>
  HttpResponse.json({ title: 'Unauthorized', status: 401 }, { status: 401 }),
)

const availableHandlers = [
  anonymousSessionHandler,
  http.get('/api/v1/customer/access-mode', () =>
    HttpResponse.json({ mode: 'ANONYMOUS_ALLOWED' }),
  ),
]

const meta = {
  title: '06 Customer/Customer Request Create Page',
  component: CustomerRequestCreatePage,
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
          '현재 production 고객 접근 모드와 customer session을 함께 확인한 뒤 문의 접수를 허용하는 route page입니다. `REGISTRATION_REQUIRED` 상태에서는 익명 양식을 만들지 않고 로그인 화면으로만 안내합니다.',
      },
    },
    msw: { handlers: availableHandlers },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerRequestCreatePage>

export default meta
type Story = StoryObj<typeof meta>

export const AnonymousAllowed: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '문의하기' }),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '문의 접수' }),
    ).toBeDisabled()
  },
}

export const RegistrationRequired: Story = {
  parameters: {
    msw: {
      handlers: [
        anonymousSessionHandler,
        http.get('/api/v1/customer/access-mode', () =>
          HttpResponse.json({ mode: 'REGISTRATION_REQUIRED' }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', {
        name: '로그인이 필요한 문의 접수입니다.',
      }),
    ).toBeVisible()
    await expect(
      canvas.getByRole('link', { name: '고객 로그인' }),
    ).toBeVisible()
  },
}
