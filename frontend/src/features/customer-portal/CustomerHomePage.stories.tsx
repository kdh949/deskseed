import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { CustomerHomePage } from './CustomerHomePage'

const meta = {
  title: '06 Customer/Customer Home Page',
  component: CustomerHomePage,
  parameters: {
    docs: {
      description: {
        component:
          'Deskseed 고객 지원의 production root 화면입니다. 새 문의, 이메일 링크 기반 문의 조회, customer magic-link 로그인이라는 실제 고객 경로만 안내합니다.',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerHomePage>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { name: '문의부터 답변 확인까지 한곳에서' }),
    ).toBeVisible()
    await expect(
      canvas.getByRole('link', { name: /새 문의 접수/ }),
    ).toBeVisible()
  },
}
