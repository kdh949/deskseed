import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { HelpArticlePage, HelpCenterHomePage } from './HelpCenterPages'

function renderWithQuery(element: React.ReactElement, path = '/') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/" element={element} />
          <Route path="/articles/:articleSlug" element={element} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('Help Center pages', () => {
  it('does not replace an empty category response with invented topics or articles', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input)
        if (url.endsWith('/api/v1/help/categories')) {
          return Promise.resolve(Response.json([]))
        }
        if (url.endsWith('/api/v1/help/sections/announcements')) {
          return Promise.resolve(
            Response.json({
              slug: 'announcements',
              title: '공지사항',
              description: '',
              articles: [],
            }),
          )
        }
        return Promise.resolve(new Response(null, { status: 404 }))
      }),
    )

    renderWithQuery(<HelpCenterHomePage />)

    expect(
      await screen.findByText('등록된 도움말 주제가 없습니다.'),
    ).toBeVisible()
    expect(screen.queryByText('주문 상태, 배송, 반품과 교환')).toBeNull()
    expect(screen.queryByRole('heading', { name: '추천 문서' })).toBeNull()
    expect(screen.queryByText('DeskSeed 시작하기')).toBeNull()
  })

  it('shows an actionable empty state when an article has no published body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        Response.json({
          slug: 'empty-article',
          currentPublishedRevision: {
            title: '비어 있는 문서',
            createdAt: '2026-08-27T00:00:00Z',
            document: { blocks: [] },
          },
        }),
      ),
    )

    renderWithQuery(<HelpArticlePage />, '/articles/empty-article')

    expect(
      await screen.findByRole('heading', { name: '비어 있는 문서' }),
    ).toBeVisible()
    expect(screen.getByText('이 문서는 아직 내용이 없습니다.')).toBeVisible()
    expect(
      screen.getByRole('link', { name: '지원팀에 문의하기' }),
    ).toHaveAttribute('href', '/requests/new')
    expect(screen.queryByText(/설정 메뉴|프로필 메뉴/)).toBeNull()
    expect(screen.queryByRole('heading', { name: '관련 문서' })).toBeNull()
  })
})
