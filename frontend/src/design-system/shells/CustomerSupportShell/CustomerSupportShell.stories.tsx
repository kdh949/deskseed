import type { Meta, StoryObj } from '@storybook/react-vite'
import { expect } from 'storybook/test'
import { ScreenState } from '../../components/Feedback'
import { ViewNavigation } from '../../patterns/ViewNavigation'
import { WorkspaceNavigationRail } from '../WorkspaceNavigationRail/WorkspaceNavigationRail'
import { CustomerSupportShell } from './CustomerSupportShell'

const globalNavigation = (
  <WorkspaceNavigationRail
    activeItemId="lookup"
    ariaLabel="고객 지원 메뉴"
    brandLabel="Deskseed 고객 지원 홈"
    brandTo="/"
    items={[
      { icon: 'home', id: 'home', label: '홈', to: '/' },
      { icon: 'search', id: 'lookup', label: '문의 조회', to: '/lookup' },
      { icon: 'pencil', id: 'new', label: '새 문의', to: '/new' },
    ]}
    tone="inverse"
  />
)

const workNavigation = (
  <ViewNavigation
    label="고객 지원 탐색"
    sections={[
      {
        id: 'support',
        items: [
          { icon: 'search', key: 'lookup', label: '문의 조회', to: '/lookup' },
          { icon: 'pencil', key: 'new', label: '새 문의', to: '/new' },
          { icon: 'lock', key: 'login', label: '고객 로그인', to: '/login' },
        ],
        label: '지원 메뉴',
      },
    ]}
    title="고객 지원"
  />
)

const meta = {
  title: '05 Shells & Layouts/Customer Support Shell',
  component: CustomerSupportShell,
  args: {
    children: <h1>문의 조회</h1>,
    complementary: (
      <section>
        <h2>새 문의 접수</h2>
        <p>새로운 도움이 필요하면 문의를 남겨 주세요.</p>
      </section>
    ),
    complementaryLabel: '새 문의 접수',
    globalNavigation,
    mainLabel: '문의 조회',
    topBar: (
      <>
        <strong>고객 지원</strong>
        <span>문의 조회</span>
      </>
    ),
    workNavigation,
  },
  parameters: {
    docs: {
      description: {
        component:
          '고객 지원 작업 공간의 rail, 업무 내비게이션, top bar, 주 작업, 보조 영역을 조합하는 shell입니다. route·세션·조회 상태는 소유하지 않고 전달받은 슬롯만 배치합니다.',
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof CustomerSupportShell>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('navigation', { name: '고객 지원 메뉴' }),
    ).toBeVisible()
    await expect(
      canvas.getByRole('navigation', { name: '고객 지원 탐색 목록' }),
    ).toBeVisible()
    await expect(
      canvas.getByRole('complementary', { name: '새 문의 접수' }),
    ).toBeVisible()
  },
}

export const Loading: Story = {
  args: {
    children: (
      <ScreenState
        description="고객 세션을 확인하고 있습니다."
        kind="loading"
        title="지원 화면을 준비하는 중"
      />
    ),
  },
}

export const Error: Story = {
  args: {
    children: (
      <ScreenState
        description="잠시 후 다시 시도해 주세요."
        kind="error"
        title="지원 화면을 불러오지 못했습니다"
      />
    ),
  },
}

export const Denied: Story = {
  args: {
    children: (
      <ScreenState
        description="로그인 링크를 사용해 다시 확인해 주세요."
        kind="denied"
        title="이 문의를 볼 수 없습니다"
      />
    ),
  },
}
