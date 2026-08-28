import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import { CustomerSiteLayout } from '../../design-system'
import { CustomerHomePage } from './CustomerHomePage'

const meta = {
  title: '06 Customer/Customer Home Page',
  component: CustomerHomePage,
  beforeEach: () => {
    window.sessionStorage.clear()
  },
  parameters: {
    docs: {
      description: {
        component:
          'Deskseed 고객 지원의 production root 작업입니다. 문의 번호와 브라우저에 이미 보관된 ticket-scoped access proof만 사용하며 capability token 입력은 제공하지 않습니다.',
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerHomePage>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  render: () => (
    <CustomerSiteLayout
      presentation="workspace"
      session={{ status: 'anonymous' }}
    >
      <CustomerHomePage />
    </CustomerSiteLayout>
  ),
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', {
        level: 1,
        name: '문의 번호로 빠르게 확인하세요',
      }),
    ).toBeVisible()
    await expect(
      canvas.getByRole('navigation', { name: '고객 메뉴' }),
    ).toBeVisible()
    await expect(
      canvas.queryByText('DESKSEED 고객 지원'),
    ).not.toBeInTheDocument()
    await expect(canvas.queryByText('⌘ K')).not.toBeInTheDocument()
    await expect(
      canvas.queryByLabelText(/토큰|조회 키/),
    ).not.toBeInTheDocument()
  },
}

export const InvalidNumber: Story = {
  ...Default,
  play: async ({ canvas }) => {
    await userEvent.type(canvas.getByLabelText('문의 번호'), 'abc')
    await userEvent.click(canvas.getByRole('button', { name: '문의 열기' }))
    await expect(canvas.getByText('문의 번호를 확인해 주세요.')).toBeVisible()
  },
}

export const MissingEmailLink: Story = {
  ...Default,
  play: async ({ canvas }) => {
    await userEvent.type(canvas.getByLabelText('문의 번호'), '1042')
    await userEvent.click(canvas.getByRole('button', { name: '문의 열기' }))
    await expect(
      canvas.getByText('이메일로 받은 문의 링크를 다시 열어 주세요.'),
    ).toBeVisible()
    await expect(
      canvas.queryByText(/이 브라우저|보안을 위해/),
    ).not.toBeInTheDocument()
  },
}
