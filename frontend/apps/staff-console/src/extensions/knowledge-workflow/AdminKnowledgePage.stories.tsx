import type { Meta, StoryObj } from '@storybook/react-vite'
import { delay, http, HttpResponse } from 'msw'
import { expect, userEvent, waitFor } from 'storybook/test'
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
    HttpResponse.json({ items: [article], nextCursor: null }),
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
    await canvas.findByRole('button', { name: '열기: refund-guide' })
    await userEvent.click(canvas.getByRole('button', { name: '문서 만들기' }))
    await userEvent.selectOptions(
      canvas.getByLabelText(/문서 섹션/),
      section.id,
    )
    await userEvent.type(canvas.getByLabelText(/문서 제목/), '환불 처리 안내')
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
      await canvas.findByRole('button', { name: '열기: refund-guide' }),
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
      await canvas.findByRole('button', { name: '열기: refund-guide' }),
    )
    await userEvent.type(await canvas.findByLabelText(/문서 제목/), ' 수정')
    await expect(
      canvas.getByRole('button', { name: '검토 요청' }),
    ).toBeDisabled()
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await expect(
      await canvas.findByText('최신 내용을 확인하세요.'),
    ).toBeVisible()
    await expect(canvas.getByLabelText(/문서 제목/)).toHaveValue(
      '환불 처리 안내 수정',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 내용 확인' }),
    )
    await waitFor(() =>
      expect(canvas.getByRole('button', { name: '초안 저장' })).toBeEnabled(),
    )
    await expect(canvas.getByLabelText(/문서 제목/)).toHaveValue(
      '환불 처리 안내 수정',
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
