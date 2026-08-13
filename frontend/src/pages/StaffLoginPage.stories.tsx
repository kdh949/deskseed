import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { StaffSessionProvider } from '../features/staff-auth/StaffSessionContext'
import { StaffLoginPage } from './StaffLoginPage'

const meta = {
  title: '07 Screens/Staff Login',
  component: StaffLoginPage,
  decorators: [
    (Story) => (
      <StaffSessionProvider>
        <Story />
      </StaffSessionProvider>
    ),
  ],
  parameters: {
    docs: {
      description: {
        component:
          '현재 staff surface의 최소 로그인 화면이다. 인증 결과는 server session이 소유하며 Story는 anonymous loading 완료와 form 접근성만 검증한다.',
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof StaffLoginPage>

export default meta
type Story = StoryObj<typeof meta>

export const Anonymous: Story = {
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '직원 로그인' }),
    ).toBeVisible()
    await expect(canvas.getByLabelText('이메일')).toBeEnabled()
    await expect(canvas.getByLabelText('비밀번호')).toBeEnabled()
    await expect(canvas.getByRole('button', { name: '로그인' })).toBeEnabled()
  },
}
