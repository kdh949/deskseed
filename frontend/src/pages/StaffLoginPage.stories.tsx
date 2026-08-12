import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { StaffSessionProvider } from '../features/staff-auth/StaffSessionContext'
import { StaffLoginPage } from './StaffLoginPage'

const meta = {
  title: 'Product/Supporting/StaffLoginPage',
  component: StaffLoginPage,
  decorators: [
    (Story) => (
      <StaffSessionProvider>
        <Story />
      </StaffSessionProvider>
    ),
  ],
  parameters: { layout: 'fullscreen' },
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
