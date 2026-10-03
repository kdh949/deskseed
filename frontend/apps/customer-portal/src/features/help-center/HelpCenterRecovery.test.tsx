import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Link } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import {
  HelpArticlePage,
  HelpCategoriesPage,
  HelpCategoryPage,
  HelpSectionPage,
  HelpSearchPage,
} from './HelpCenterPages'
import { parseHelpBlocks } from './helpDocumentCodec'
import { HelpDocument } from './HelpDocument'
afterEach(() => vi.unstubAllGlobals())
function app(path: string) {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <Link to="/articles/second">다음 문서</Link>
        <Routes>
          <Route path="/categories" element={<HelpCategoriesPage />} />
          <Route
            path="/categories/:categorySlug"
            element={<HelpCategoryPage />}
          />
          <Route path="/sections/:sectionSlug" element={<HelpSectionPage />} />
          <Route path="/articles/:articleSlug" element={<HelpArticlePage />} />
          <Route path="/search" element={<HelpSearchPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
it('preserves canonical headings lists code links and escapes HTML', () => {
  const blocks = parseHelpBlocks({
    schemaVersion: 1,
    blocks: [
      { type: 'heading', level: 2, text: '절차' },
      { type: 'list', ordered: true, items: ['첫 단계', '두 번째'] },
      { type: 'code', text: '<script>alert(1)</script>' },
      { type: 'link', text: '설정 안내', url: 'https://example.test/help' },
      { type: 'quote', text: '인용' },
      { type: 'divider' },
    ],
  })
  const { container } = render(<HelpDocument blocks={blocks} />)
  expect(screen.getByRole('heading', { level: 2, name: '절차' })).toBeVisible()
  expect(screen.getAllByRole('listitem')).toHaveLength(2)
  expect(container.querySelector('ol')).not.toBeNull()
  expect(container.querySelector('pre code')).toHaveTextContent('<script>')
  expect(container.querySelector('script')).toBeNull()
  expect(screen.getByRole('link', { name: '설정 안내' })).toHaveAttribute(
    'href',
    'https://example.test/help',
  )
  for (const url of [
    'javascript:alert(1)',
    'http://example.test',
    'https://user:password@example.test',
  ])
    expect(() =>
      parseHelpBlocks({
        schemaVersion: 1,
        blocks: [{ type: 'link', text: 'link', url }],
      }),
    ).toThrow()
})
it('navigates by category slug and loads every section page including notices beyond the first two', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.endsWith('/categories'))
        return Response.json(
          Array.from({ length: 6 }, (_, index) => ({
            id: `category-${index}`,
            slug: `topic-${index}`,
            title: `주제 ${index}`,
          })),
        )
      if (url.includes('/categories/topic-5'))
        return Response.json({
          id: 'category-5',
          slug: 'topic-5',
          title: '주제 5',
          sections: [{ slug: 'announcements', title: '공지사항' }],
        })
      const more = url.includes('cursor=next')
      return Response.json({
        slug: 'announcements',
        title: '공지사항',
        articles: [
          {
            slug: more ? 'later' : 'first',
            title: more ? '후속 공지' : '첫 공지',
            summary: '',
          },
        ],
        hasMore: !more,
        nextCursor: more ? null : 'next',
      })
    }),
  )
  app('/categories')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('link', { name: /주제 5/ }))
  await user.click(await screen.findByRole('link', { name: '공지사항' }))
  await expect(
    await screen.findByRole('link', { name: '첫 공지' }),
  ).toBeVisible()
  await user.click(screen.getByRole('button', { name: '문서 더 보기' }))
  expect(await screen.findByRole('link', { name: '후속 공지' })).toBeVisible()
  expect(
    screen.queryByRole('button', { name: '문서 더 보기' }),
  ).not.toBeInTheDocument()
})
it('retains search results and uses the returned cursor without losing the query', async () => {
  const calls: unknown[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/categories')) return Response.json([])
      const body = JSON.parse(init!.body as string)
      calls.push(body)
      return Response.json({
        items: [
          {
            articleSlug: body.cursor ? 'second' : 'first',
            title: body.cursor ? '추가 검색 결과' : '첫 검색 결과',
            excerpt: '안내',
          },
        ],
        hasMore: !body.cursor,
        nextCursor: body.cursor ? null : 'opaque-cursor',
      })
    }),
  )
  app('/search?q=결제')
  const user = userEvent.setup()
  await screen.findByRole('heading', { name: '첫 검색 결과' })
  await user.click(screen.getByRole('button', { name: '검색 결과 더 보기' }))
  expect(
    await screen.findByRole('heading', { name: '추가 검색 결과' }),
  ).toBeVisible()
  expect(screen.getByRole('heading', { name: '첫 검색 결과' })).toBeVisible()
  expect(calls).toEqual([
    { query: '결제', limit: 20 },
    { query: '결제', limit: 20, cursor: 'opaque-cursor' },
  ])
})
it('waits for persisted feedback, exposes retry and resets feedback for another article', async () => {
  let finish!: (response: Response) => void
  let attempts = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.endsWith('/feedback')) {
        attempts++
        return attempts === 1
          ? new Response(null, { status: 503 })
          : new Promise<Response>((resolve) => {
              finish = resolve
            })
      }
      return Response.json({
        slug: url.split('/').at(-1),
        currentPublishedRevision: {
          title: '문서',
          document: {
            schemaVersion: 1,
            blocks: [{ type: 'paragraph', text: '안내 본문' }],
          },
        },
      })
    }),
  )
  app('/articles/first')
  const user = userEvent.setup()
  await user.click(
    await screen.findByRole('button', { name: '네, 도움이 됐어요' }),
  )
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '저장하지 못했습니다',
  )
  await user.click(screen.getByRole('button', { name: '네, 도움이 됐어요' }))
  expect(
    screen.queryByText('의견을 보내주셔서 감사합니다.'),
  ).not.toBeInTheDocument()
  await act(async () => finish(new Response(null, { status: 204 })))
  expect(await screen.findByText('의견을 보내주셔서 감사합니다.')).toBeVisible()
  await user.click(screen.getByRole('link', { name: '다음 문서' }))
  expect(
    await screen.findByRole('button', { name: '네, 도움이 됐어요' }),
  ).toBeEnabled()
})
it.each([404, 503])(
  'classifies article HTTP %s without misreporting a server failure as missing',
  async (status) => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status })),
    )
    app('/articles/first')
    expect(
      await screen.findByText(
        status === 404
          ? '문서를 찾을 수 없습니다.'
          : '문서를 불러올 수 없습니다.',
      ),
    ).toBeVisible()
  },
)
