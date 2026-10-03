import type { Meta, StoryObj } from '@storybook/react-vite'
import { delay, http, HttpResponse } from 'msw'
import { expect, userEvent, waitFor, within } from 'storybook/test'
import { AdminKnowledgePage } from './AdminKnowledgePage'
import { article, category, revision, section } from './fixtures'
const base = [
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.get('/api/v1/admin/knowledge/categories', () =>
    HttpResponse.json([category]),
  ),
  http.get('/api/v1/admin/knowledge/sections', () =>
    HttpResponse.json([section]),
  ),
  http.get('/api/v1/admin/knowledge/articles', () =>
    HttpResponse.json({
      items: [
        {
          ...article,
          latestRevision: { title: revision.title, summary: revision.summary },
        },
      ],
      nextCursor: null,
    }),
  ),
  http.get('/api/v1/admin/knowledge/articles/:id', () =>
    HttpResponse.json(article),
  ),
  http.get('/api/v1/admin/knowledge/articles/:id/revisions', () =>
    HttpResponse.json([revision]),
  ),
]
const meta = {
  title: '07 Screens/Admin Knowledge',
  component: AdminKnowledgePage,
  parameters: { msw: { handlers: base } },
} satisfies Meta<typeof AdminKnowledgePage>
export default meta
type Story = StoryObj<typeof meta>
export const CreateDraft: Story = {
  parameters: {
    msw: {
      handlers: [
        ...base,
        http.post('/api/v1/admin/knowledge/articles', async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          await expect(body.title).toBe('환불 처리 안내')
          await expect(body.document).toEqual({
            schemaVersion: 1,
            blocks: [
              { type: 'paragraph', text: '주문 내역에서 환불을 요청하세요.' },
            ],
          })
          await expect(request.headers.get('X-CSRF-TOKEN')).toBe(
            'storybook-csrf',
          )
          return HttpResponse.json(article, { status: 201 })
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await canvas.findByRole('button', { name: '열기: 환불 처리 안내' })
    await userEvent.click(canvas.getByRole('button', { name: '문서 만들기' }))
    await userEvent.selectOptions(
      canvas.getByLabelText(/문서 섹션(?! 필터)/),
      section.id,
    )
    await userEvent.type(
      canvas.getByLabelText(/문서 제목(?! 검색)/),
      '환불 처리 안내',
    )
    await userEvent.type(
      canvas.getByLabelText(/문서 주소 식별자/),
      'refund-guide',
    )
    await userEvent.type(
      canvas.getByLabelText(/블록 1 내용/),
      '주문 내역에서 환불을 요청하세요.',
    )
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await expect(await canvas.findByRole('status')).toHaveTextContent(
      '초안을 저장했습니다.',
    )
    await expect(
      canvas.getByRole('button', { name: '검토 요청' }),
    ).toBeEnabled()
  },
}
export const ReviewAndPublish: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/knowledge/articles/:id', () =>
          HttpResponse.json({ ...article, lifecycle: 'IN_REVIEW', version: 1 }),
        ),
        http.post(
          '/api/v1/admin/knowledge/articles/:id/publish',
          ({ request }) => {
            expect(request.headers.get('If-Match')).toBe('"1"')
            return HttpResponse.json({
              ...article,
              lifecycle: 'PUBLISHED',
              version: 2,
              currentPublishedRevision: revision,
            })
          },
        ),
        ...base,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '열기: 환불 처리 안내' }),
    )
    await userEvent.click(await canvas.findByRole('button', { name: '발행' }))
    await expect(await canvas.findByRole('status')).toHaveTextContent(
      '문서 상태: 발행됨',
    )
  },
}
export const ConflictPreservesDraft: Story = {
  parameters: {
    msw: {
      handlers: [
        ...base,
        http.patch('/api/v1/admin/knowledge/articles/:id', () =>
          HttpResponse.json({ status: 412 }, { status: 412 }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '열기: 환불 처리 안내' }),
    )
    await userEvent.type(
      await canvas.findByLabelText(/문서 제목(?! 검색)/),
      ' 수정',
    )
    await expect(
      canvas.getByRole('button', { name: '검토 요청' }),
    ).toBeDisabled()
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await expect(
      await canvas.findByText('최신 내용을 확인하세요.'),
    ).toBeVisible()
    await expect(canvas.getByLabelText(/문서 제목(?! 검색)/)).toHaveValue(
      '환불 처리 안내 수정',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 내용 확인' }),
    )
    await waitFor(() =>
      expect(canvas.getByRole('button', { name: '초안 저장' })).toBeEnabled(),
    )
    await expect(canvas.getByLabelText(/문서 제목(?! 검색)/)).toHaveValue(
      '환불 처리 안내 수정',
    )
  },
}
let reviewVersion = 1
let latestLifecycle = 'IN_REVIEW'
export const PublishConflictReloadsReview: Story = {
  beforeEach: () => {
    reviewVersion = 1
    latestLifecycle = 'IN_REVIEW'
  },
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/knowledge/articles/:id', () =>
          HttpResponse.json({
            ...article,
            lifecycle:
              reviewVersion === 1
                ? 'IN_REVIEW'
                : reviewVersion === 5
                  ? 'PUBLISHED'
                  : latestLifecycle,
            version: reviewVersion,
          }),
        ),
        http.get('/api/v1/admin/knowledge/articles/:id/revisions', () =>
          HttpResponse.json([
            reviewVersion === 1
              ? revision
              : {
                  ...revision,
                  revisionNumber: 2,
                  document: {
                    schemaVersion: 1,
                    blocks: [
                      {
                        type: 'paragraph',
                        text: '새 검토 내용: 환불 전 주문 상태를 확인하세요.',
                      },
                    ],
                  },
                },
          ]),
        ),
        http.post(
          '/api/v1/admin/knowledge/articles/:id/publish',
          ({ request }) => {
            if (request.headers.get('If-Match') === '"1"') {
              reviewVersion = 4
              return HttpResponse.json({ status: 412 }, { status: 412 })
            }
            expect(request.headers.get('If-Match')).toBe('"4"')
            reviewVersion = 5
            return HttpResponse.json({
              ...article,
              lifecycle: 'PUBLISHED',
              version: 5,
            })
          },
        ),
        ...base,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '열기: 환불 처리 안내' }),
    )
    await expect(
      await canvas.findByText('주문번호를 확인한 뒤 환불을 요청하세요.'),
    ).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '발행' }))
    await expect(
      await canvas.findByText('최신 내용을 확인하세요.'),
    ).toBeVisible()
    await expect(canvas.getByRole('button', { name: '발행' })).toBeDisabled()
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 내용 확인' }),
    )
    await expect(
      await canvas.findByText('새 검토 내용: 환불 전 주문 상태를 확인하세요.'),
    ).toBeVisible()
    await expect(
      canvas.queryByText('주문번호를 확인한 뒤 환불을 요청하세요.'),
    ).not.toBeInTheDocument()
    await userEvent.click(canvas.getByRole('button', { name: '발행' }))
    await waitFor(() =>
      expect(canvas.getByRole('button', { name: '공개 중지' })).toBeEnabled(),
    )
  },
}
export const PublishConflictReloadsReturnedDraft: Story = {
  ...PublishConflictReloadsReview,
  beforeEach: () => {
    reviewVersion = 1
    latestLifecycle = 'DRAFT'
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '열기: 환불 처리 안내' }),
    )
    await userEvent.click(await canvas.findByRole('button', { name: '발행' }))
    await canvas.findByText('최신 내용을 확인하세요.')
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 내용 확인' }),
    )
    await expect(await canvas.findByLabelText(/블록 1 내용/)).toHaveValue(
      '새 검토 내용: 환불 전 주문 상태를 확인하세요.',
    )
    await waitFor(() =>
      expect(canvas.getByRole('button', { name: '검토 요청' })).toBeEnabled(),
    )
  },
}
export const Empty: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/knowledge/articles', () =>
          HttpResponse.json({ items: [], nextCursor: null }),
        ),
        ...base,
      ],
    },
  },
}
export const Denied: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/knowledge/articles', () =>
          HttpResponse.json({ status: 403 }, { status: 403 }),
        ),
        ...base,
      ],
    },
  },
}
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/knowledge/articles', async () => {
          await delay('infinite')
          return HttpResponse.json({ items: [], nextCursor: null })
        }),
        ...base,
      ],
    },
  },
}

export const TitleSearchAndFilters: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post(
          '/api/v1/admin/knowledge/articles/search',
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(new URL(request.url).search).toBe('')
            expect(body.query).toBe('찾을 제목')
            expect(body.interactionId).toEqual(expect.any(String))
            return HttpResponse.json({
              items:
                body.audience === 'STAFF'
                  ? []
                  : [
                      {
                        ...article,
                        latestRevision: {
                          title: '찾을 제목의 저장된 초안',
                          summary: revision.summary,
                        },
                      },
                    ],
              resultCount: body.audience === 'STAFF' ? 0 : 51,
              hasMore: !body.cursor && body.audience !== 'STAFF',
              nextCursor:
                body.cursor || body.audience === 'STAFF'
                  ? null
                  : 'opaque-cursor',
            })
          },
        ),
        ...base,
      ],
    },
  },
  play: async ({ canvas }) => {
    await canvas.findByRole('button', { name: '열기: 환불 처리 안내' })
    await userEvent.type(canvas.getByLabelText(/문서 제목 검색/), '찾을 제목')
    await userEvent.click(
      canvas.getByRole('button', { name: '검색', exact: true }),
    )
    await expect(
      await canvas.findByText('검색 결과 51개 · 현재 목록 1개'),
    ).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '다음 목록' }))
    await expect(
      await canvas.findByRole('button', { name: '첫 목록' }),
    ).toBeVisible()
    await userEvent.selectOptions(
      canvas.getByLabelText('공개 범위 필터'),
      'STAFF',
    )
    await expect(
      await canvas.findByText('검색 결과 0개 · 현재 목록 0개'),
    ).toBeVisible()
    await expect(
      canvas.queryByRole('button', { name: '첫 목록' }),
    ).not.toBeInTheDocument()
    await expect(canvas.getByText('조건에 맞는 문서가 없습니다.')).toBeVisible()
  },
}

export const DraftExitProtection: Story = {
  play: async ({ canvas, canvasElement }) => {
    const body = within(canvasElement.ownerDocument.body)
    await userEvent.click(
      await canvas.findByRole('button', { name: '열기: 환불 처리 안내' }),
    )
    await waitFor(() =>
      expect(
        canvas.getByRole('heading', { name: '환불 처리 안내', level: 2 }),
      ).toHaveFocus(),
    )
    await userEvent.type(canvas.getByLabelText(/문서 제목(?! 검색)/), ' 수정')
    await userEvent.click(
      canvas.getByRole('button', { name: '카테고리 만들기' }),
    )
    await expect(
      await body.findByRole('dialog', { name: '저장하지 않은 변경 사항' }),
    ).toBeVisible()
    await userEvent.click(body.getByRole('button', { name: '계속 편집' }))
    await expect(canvas.getByLabelText(/문서 제목(?! 검색)/)).toHaveValue(
      '환불 처리 안내 수정',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '카테고리 만들기' }),
    )
    await userEvent.click(
      await body.findByRole('button', { name: '변경 사항 버리고 이동' }),
    )
    await userEvent.type(await canvas.findByLabelText(/분류 이름/), '새 분류')
    await userEvent.click(
      canvas.getByRole('button', { name: '분류 편집 닫기' }),
    )
    await userEvent.click(
      await body.findByRole('button', { name: '계속 편집' }),
    )
    await expect(canvas.getByLabelText(/분류 이름/)).toHaveValue('새 분류')
  },
}

export const PendingSavePreservesInput: Story = {
  parameters: {
    msw: {
      handlers: [
        http.patch('/api/v1/admin/knowledge/articles/:id', async () => {
          await delay(300)
          return HttpResponse.json({ status: 503 }, { status: 503 })
        }),
        ...base,
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '열기: 환불 처리 안내' }),
    )
    await userEvent.type(
      await canvas.findByLabelText(/문서 제목(?! 검색)/),
      ' 유지',
    )
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await expect(
      canvas.getByRole('button', { name: '문서 닫기' }),
    ).toBeDisabled()
    await expect(canvas.getByLabelText(/문서 제목(?! 검색)/)).toBeDisabled()
    await expect(
      await canvas.findByText('최신 내용을 확인하세요.'),
    ).toBeVisible()
    await expect(canvas.getByLabelText(/문서 제목(?! 검색)/)).toHaveValue(
      '환불 처리 안내 유지',
    )
  },
}
