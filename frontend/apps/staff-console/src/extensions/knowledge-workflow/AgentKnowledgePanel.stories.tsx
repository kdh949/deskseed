import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse } from 'msw'
import { expect, fn, userEvent, waitFor } from 'storybook/test'
import { AgentKnowledgePanel } from './AgentKnowledgePanel'
import { article, revision } from './fixtures'
const published = {
  ...article,
  lifecycle: 'PUBLISHED',
  currentPublishedRevision: revision,
}
const searchHit = {
  articleSlug: article.slug,
  title: revision.title,
  excerpt: revision.summary,
  audience: 'PUBLIC',
  categoryTitle: '결제',
  sectionTitle: '환불',
}
const base = [
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.post('/api/v1/agent/knowledge/search', () =>
    HttpResponse.json({ items: [searchHit], nextCursor: null }),
  ),
  http.get('/api/v1/agent/knowledge/articles/:slug', () =>
    HttpResponse.json(published),
  ),
]
const meta = {
  title: '07 Screens/Agent Knowledge',
  component: AgentKnowledgePanel,
  args: { mode: 'PUBLIC', onInsert: fn() },
  parameters: { msw: { handlers: base } },
} satisfies Meta<typeof AgentKnowledgePanel>
export default meta
type Story = StoryObj<typeof meta>
export const SearchReadInsert: Story = {
  play: async ({ canvas, args }) => {
    await userEvent.type(canvas.getByLabelText(/지식 검색어/), '환불')
    await userEvent.click(canvas.getByRole('button', { name: '지식 검색' }))
    await userEvent.click(
      await canvas.findByRole('button', { name: '읽기: 환불 처리 안내' }),
    )
    await userEvent.click(
      await canvas.findByRole('button', { name: '현재 답변에 링크 삽입' }),
    )
    await waitFor(() =>
      expect(args.onInsert).toHaveBeenCalledWith(
        '환불 처리 안내',
        `${window.location.origin}/articles/refund-guide`,
      ),
    )
    await expect(
      await canvas.findByText('공개 답변 초안에 문서 링크를 넣었습니다.'),
    ).toBeVisible()
  },
}
export const InternalArticleBlocked: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/knowledge/articles/:slug', () =>
          HttpResponse.json({
            ...published,
            audience: { type: 'STAFF', groupIds: [] },
          }),
        ),
        ...base,
      ],
    },
  },
  play: async ({ canvas, args }) => {
    await userEvent.type(canvas.getByLabelText(/지식 검색어/), '환불')
    await userEvent.click(canvas.getByRole('button', { name: '지식 검색' }))
    await userEvent.click(
      await canvas.findByRole('button', { name: '읽기: 환불 처리 안내' }),
    )
    await expect(
      await canvas.findByRole('button', { name: '현재 답변에 링크 삽입' }),
    ).toBeDisabled()
    await expect(args.onInsert).not.toHaveBeenCalled()
  },
}
export const EmptySearch: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post('/api/v1/agent/knowledge/search', () =>
          HttpResponse.json({ items: [], nextCursor: null }),
        ),
        ...base,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.type(
      canvas.getByLabelText(/지식 검색어/),
      '알 수 없는 오류',
    )
    await userEvent.click(canvas.getByRole('button', { name: '지식 검색' }))
    await expect(
      await canvas.findByText(
        '검색 결과가 없습니다. 다른 검색어로 찾아보세요.',
      ),
    ).toBeVisible()
  },
}
export const DeniedRead: Story = {
  args: { initialSlug: 'refund-guide' },
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/knowledge/articles/:slug', () =>
          HttpResponse.json({ status: 403 }, { status: 403 }),
        ),
        ...base,
      ],
    },
  },
}

let articleReads = 0
export const AccessChangesBeforeInsertion: Story = {
  beforeEach: () => {
    articleReads = 0
  },
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/knowledge/articles/:slug', () =>
          HttpResponse.json({
            ...published,
            audience: {
              type: ++articleReads === 1 ? 'PUBLIC' : 'STAFF',
              groupIds: [],
            },
          }),
        ),
        ...base,
      ],
    },
  },
  play: async ({ canvas, args }) => {
    await userEvent.type(canvas.getByLabelText(/지식 검색어/), '환불')
    await userEvent.click(canvas.getByRole('button', { name: '지식 검색' }))
    await userEvent.click(
      await canvas.findByRole('button', { name: '읽기: 환불 처리 안내' }),
    )
    await userEvent.click(
      await canvas.findByRole('button', { name: '현재 답변에 링크 삽입' }),
    )
    await waitFor(() =>
      expect(canvas.getByRole('status')).toHaveTextContent(
        '직원 전용 문서는 내부 메모에만 삽입할 수 있습니다.',
      ),
    )
    await expect(args.onInsert).not.toHaveBeenCalled()
  },
}
