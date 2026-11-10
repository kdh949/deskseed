import type { Meta, StoryObj } from '@storybook/react-vite'
import { Route, Routes, useSearchParams } from 'react-router'
import { expect, fn, userEvent, within } from 'storybook/test'
import { CustomerSiteLayout } from './CustomerSiteLayout'

function Destinations() {
  const [search] = useSearchParams()
  return (
    <div className="customer-page">
      <Routes>
        <Route path="/requests/lookup" element={<h1>문의 번호로 조회</h1>} />
        <Route path="/categories" element={<h1>모든 문서</h1>} />
        <Route
          path="/search"
          element={
            <>
              <h1>도움말 검색 결과</h1>
              <p>검색어: {search.get('q')}</p>
            </>
          }
        />
        <Route path="*" element={<h1>고객 지원</h1>} />
      </Routes>
    </div>
  )
}

const meta = {
  title: 'Customer Design System/Site Layout',
  component: CustomerSiteLayout,
  tags: ['autodocs'],
  args: {
    session: { status: 'anonymous' },
    children: <Destinations />,
    onSignOut: fn(),
  },
  parameters: {
    layout: 'fullscreen',
    docs: {
      description: {
        component:
          '고객 앱 전용 header/main/footer입니다. session.status와 customer 표시 이름으로 로그인/내 문의 메뉴를 구분하며 onSignOut은 호출만 위임합니다. 문의 조회는 모든 고객에게 제공하지만 조회 권한은 route의 ticket-scoped proof가 결정합니다. 모바일에서도 검색과 모든 탐색 링크가 접근 가능합니다.',
      },
    },
  },
} satisfies Meta<typeof CustomerSiteLayout>
export default meta
type Story = StoryObj<typeof meta>

export const AnonymousNavigation: Story = {
  play: async ({ canvas }) => {
    const menu = within(canvas.getByRole('navigation', { name: '고객 메뉴' }))
    await userEvent.click(menu.getByRole('link', { name: '문서 둘러보기' }))
    await expect(
      canvas.getByRole('heading', { name: '모든 문서' }),
    ).toBeVisible()
    await userEvent.click(menu.getByRole('link', { name: '문의 조회' }))
    await expect(
      canvas.getByRole('heading', { name: '문의 번호로 조회' }),
    ).toBeVisible()
    await userEvent.type(
      canvas.getByLabelText('도움말 검색', { exact: true }),
      '  결제 오류  {Enter}',
    )
    await expect(
      canvas.getByRole('heading', { name: '도움말 검색 결과' }),
    ).toBeVisible()
    await expect(canvas.getByText('검색어: 결제 오류')).toBeVisible()
  },
}
export const SignedInNavigation: Story = {
  args: {
    session: {
      status: 'authenticated',
      customer: {
        displayName: '긴 이름을 가진 고객 지원 이용자',
        email: 'synthetic@example.test',
      },
    },
  },
  play: async ({ canvas, args }) => {
    const menu = within(canvas.getByRole('navigation', { name: '고객 메뉴' }))
    await expect(menu.getByRole('link', { name: '내 문의' })).toHaveAttribute(
      'href',
      '/account/requests',
    )
    await expect(menu.getByRole('link', { name: '문의 조회' })).toHaveAttribute(
      'href',
      '/requests/lookup',
    )
    await userEvent.click(menu.getByRole('button', { name: '로그아웃' }))
    await expect(args.onSignOut).toHaveBeenCalledOnce()
  },
}
