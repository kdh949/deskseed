import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect, userEvent } from 'storybook/test'
import { HelpBrowseList } from './HelpBrowseList'
const meta = {
  title: 'Customer Design System/Help Browse List',
  component: HelpBrowseList,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          '도움말 주제·섹션에서 다음 섹션/문서를 고르는 설명 포함 링크 목록입니다. items는 to/title/선택적 description, ariaLabel은 목록 목적입니다. 호출자가 서버 상태·권한·slug 인코딩을 소유합니다. 빈 상태는 호출자가 ScreenState로 표현합니다.',
      },
    },
  },
  args: {
    ariaLabel: '섹션 목록',
    items: [
      {
        to: '/sections/payments',
        title: '결제와 청구',
        description: '결제 수단과 청구서를 확인하고 수정하는 방법',
      },
      {
        to: '/sections/account',
        title: '계정 관리',
        description: '이름과 로그인 정보를 관리하는 방법',
      },
    ],
  },
} satisfies Meta<typeof HelpBrowseList>
export default meta
type Story = StoryObj<typeof meta>
export const SectionLinks: Story = {
  play: async ({ canvas }) => {
    const first = canvas.getByRole('link', { name: /결제와 청구/ })
    first.focus()
    await expect(first).toHaveFocus()
    await userEvent.tab()
    await expect(canvas.getByRole('link', { name: /계정 관리/ })).toHaveFocus()
    await expect(first).toHaveAttribute('href', '/sections/payments')
  },
}
export const LongArticle: Story = {
  args: {
    ariaLabel: '문서 목록',
    items: [
      {
        to: '/articles/long',
        title:
          '긴 문서 제목에서도 전체 링크를 읽고 키보드로 선택할 수 있는 도움말 목록',
        description:
          '결제 수단을 변경한 뒤 새로운 청구서에 반영되는 시점과 변경 내용 확인 방법을 차례로 살펴보세요. '.repeat(
            3,
          ),
      },
      { to: '/articles/short', title: '설명 없는 문서' },
    ],
  },
}
