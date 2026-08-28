import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect } from 'storybook/test'
import { StoryRoute } from '../../../.storybook/StoryRoute'
import { CustomerSiteLayout } from '../../design-system'
import {
  HelpArticlePage,
  HelpCenterHomePage,
  HelpSearchPage,
} from './HelpCenterPages'

const meta = {
  title: 'Customer Portal/Help Center Pages',
  component: HelpCenterHomePage,
  parameters: {
    docs: {
      description: {
        component:
          'DeskSeed 고객 포털의 도움말 홈, 검색 결과, 문서 읽기 화면입니다. 공개 Help Center 계약만 사용합니다.',
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof HelpCenterHomePage>

export default meta
type Story = StoryObj<typeof meta>

const categories = [
  {
    id: 'cat-orders',
    slug: 'orders',
    title: '주문',
    description: '주문 상태, 배송, 반품과 교환',
  },
  {
    id: 'cat-billing',
    slug: 'billing',
    title: '결제',
    description: '청구서, 결제 수단과 환불',
  },
  {
    id: 'cat-technical',
    slug: 'technical',
    title: '기술 문제',
    description: '오류와 연결 문제 해결',
  },
  {
    id: 'cat-account',
    slug: 'account',
    title: '계정',
    description: '프로필, 보안과 설정',
  },
  {
    id: 'cat-feedback',
    slug: 'feedback',
    title: '제품 의견',
    description: '아이디어와 개선 제안',
  },
]

const announcements = {
  id: 'section-announcements',
  categoryId: 'cat-announcements',
  slug: 'announcements',
  title: '공지사항',
  description: 'DeskSeed 서비스와 고객 지원 업데이트',
  articles: [
    {
      slug: 'customer-portal-update',
      title: '고객 포털 업데이트 안내',
      summary: '더 빠른 도움말 검색과 문의 조회 기능을 확인해 보세요.',
      audience: 'PUBLIC',
    },
    {
      slug: 'support-hours-update',
      title: '고객 지원 운영 시간 안내',
      summary: '고객 지원 운영 시간 변경 내용을 안내합니다.',
      audience: 'PUBLIC',
    },
  ],
}

const homeHandlers = {
  helpCategories: http.get('/api/v1/help/categories', () =>
    HttpResponse.json(categories),
  ),
  helpAnnouncements: http.get('/api/v1/help/sections/announcements', () =>
    HttpResponse.json(announcements),
  ),
}

function AnonymousChrome({ children }: { children: React.ReactNode }) {
  return (
    <CustomerSiteLayout session={{ status: 'anonymous' }}>
      {children}
    </CustomerSiteLayout>
  )
}

export const Home: Story = {
  parameters: {
    msw: {
      handlers: homeHandlers,
    },
  },
  render: () => (
    <AnonymousChrome>
      <HelpCenterHomePage />
    </AnonymousChrome>
  ),
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { name: /무엇을 도와드릴까요/ }),
    ).toBeVisible()
    await expect(
      canvas.getByRole('navigation', { name: '빠른 작업' }),
    ).toBeVisible()
    await expect(
      await canvas.findByRole('link', { name: '고객 포털 업데이트 안내' }),
    ).toHaveAttribute('href', '/articles/customer-portal-update')
  },
}

export const HomeWithoutAnnouncements: Story = {
  ...Home,
  parameters: {
    msw: {
      handlers: {
        ...homeHandlers,
        helpAnnouncements: http.get('/api/v1/help/sections/announcements', () =>
          HttpResponse.json({ ...announcements, articles: [] }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('등록된 공지사항이 없습니다.'),
    ).toBeVisible()
  },
}

export const HomeWithAnnouncementsUnavailable: Story = {
  ...Home,
  parameters: {
    msw: {
      handlers: {
        ...homeHandlers,
        helpAnnouncements: http.get(
          '/api/v1/help/sections/announcements',
          () => new HttpResponse(null, { status: 503 }),
        ),
      },
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('공지사항을 불러올 수 없습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '다시 시도' }),
    ).toBeVisible()
  },
}

export const SearchResults: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post('/api/v1/help/search', () =>
          HttpResponse.json({
            items: [
              {
                articleSlug: 'export-data',
                title: '보고서 데이터 내보내기',
                excerpt: 'CSV와 Excel로 내보내는 방법을 단계별로 확인하세요.',
                categoryTitle: '보고서',
                sectionTitle: '시작하기',
              },
              {
                articleSlug: 'export-troubleshooting',
                title: '내보내기 오류 해결',
                excerpt: '권한, 데이터 범위와 연결 상태를 확인합니다.',
                categoryTitle: '기술 문제',
                sectionTitle: '문제 해결',
              },
            ],
          }),
        ),
      ],
    },
  },
  render: () => (
    <AnonymousChrome>
      <StoryRoute path="/search" to="/search?q=내보내기">
        <HelpSearchPage />
      </StoryRoute>
    </AnonymousChrome>
  ),
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '보고서 데이터 내보내기' }),
    ).toBeVisible()
  },
}

export const Article: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/help/articles/:slug', ({ params }) =>
          HttpResponse.json({
            slug: params.slug,
            currentPublishedRevision: {
              title: '결제 정보 변경 방법',
              summary:
                '결제 수단과 청구 정보를 안전하게 변경하는 방법을 안내합니다.',
              createdAt: '2026-08-27T00:00:00Z',
              document: {
                blocks: [
                  {
                    type: 'paragraph',
                    text: '계정 설정에서 결제 메뉴를 연 뒤 변경할 항목을 선택하세요.',
                  },
                ],
              },
            },
          }),
        ),
        http.post(
          '/api/v1/help/articles/:slug/feedback',
          () => new HttpResponse(null, { status: 204 }),
        ),
      ],
    },
  },
  render: () => (
    <AnonymousChrome>
      <StoryRoute path="/articles/:articleSlug" to="/articles/update-billing">
        <HelpArticlePage />
      </StoryRoute>
    </AnonymousChrome>
  ),
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('heading', { name: '결제 정보 변경 방법' }),
    ).toBeVisible()
  },
}
