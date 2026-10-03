import type { Meta, StoryObj } from '@storybook/react-vite'
import { Link, Route, Routes } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { http, HttpResponse, delay } from 'msw'
import { expect, userEvent, waitFor } from 'storybook/test'
import { setConfirmedStaffActor } from '../../api/client'
import { AdminCustomerConsentPage } from './AdminCustomerConsentPage'
import type {
  ConsentDraft,
  ConsentPolicy,
  ConsentVersion,
} from './customerConsentApi'

const root = '/api/v1/admin/customer-consent-policies'
const actor = '22222222-2222-4222-8222-222222222222'
const timestamp = '2026-09-01T00:00:00Z'
const initial = (): ConsentPolicy => ({
  id: '11111111-1111-4111-8111-111111111111',
  policyKey: 'synthetic-terms',
  context: 'REGISTRATION',
  lifecycle: 'DRAFT',
  aggregateVersion: 1,
  createdAt: timestamp,
  updatedAt: timestamp,
  draft: {
    title: '합성 검증 정책',
    required: true,
    displayOrder: 1,
    document: {
      schemaVersion: 1,
      blocks: [{ type: 'paragraph', text: '합성 테스트 문서' }],
    },
    draftVersion: 1,
    updatedAt: timestamp,
  },
  publishedVersion: null,
  versions: [],
})
let policy = initial()
let exists = false
const handlers = [
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.get(root, () =>
    HttpResponse.json({
      items: exists
        ? [
            {
              ...policy,
              publishedVersion: policy.publishedVersion?.version ?? null,
              required: policy.draft.required,
              displayOrder: policy.draft.displayOrder,
            },
          ]
        : [],
      page: 0,
      size: 20,
      totalCount: exists ? 1 : 0,
      totalPages: exists ? 1 : 0,
    }),
  ),
  http.get(`${root}/:id`, () => HttpResponse.json(policy)),
  http.post(root, async ({ request }) => {
    expect(request.headers.get('If-None-Match')).toBe('*')
    expect(request.headers.get('X-Deskseed-Expected-Staff-Id')).toBe(actor)
    expect(request.headers.get('X-CSRF-TOKEN')).toBe('synthetic-csrf')
    const body = (await request.json()) as ConsentDraft &
      Pick<ConsentPolicy, 'policyKey' | 'context'>
    policy = {
      ...initial(),
      policyKey: body.policyKey,
      context: body.context,
      draft: {
        title: body.title,
        document: body.document,
        required: body.required,
        displayOrder: body.displayOrder,
        draftVersion: 1,
        updatedAt: timestamp,
      },
    }
    exists = true
    return HttpResponse.json(policy, { status: 201 })
  }),
  http.put(`${root}/:id`, async ({ request }) => {
    expect(request.headers.get('If-Match')).toBe(`"${policy.aggregateVersion}"`)
    const body = (await request.json()) as ConsentDraft
    policy = {
      ...policy,
      aggregateVersion: policy.aggregateVersion + 1,
      draft: {
        ...body,
        draftVersion: policy.draft.draftVersion + 1,
        updatedAt: timestamp,
      },
    }
    return HttpResponse.json(policy)
  }),
  http.post(`${root}/:id/publish`, ({ request }) => {
    expect(request.headers.get('If-Match')).toBe(`"${policy.aggregateVersion}"`)
    const version: ConsentVersion = {
      ...policy.draft,
      policyId: policy.id,
      policyKey: policy.policyKey,
      version: policy.versions.length + 1,
      plainText: '합성 테스트 문서',
      checksumSha256: 'a'.repeat(64),
      effectiveAt: timestamp,
      publishedAt: timestamp,
      publishedByStaffId: actor,
      publishedByDisplayName: '합성 관리자',
    }
    policy = {
      ...policy,
      lifecycle: 'PUBLISHED',
      aggregateVersion: policy.aggregateVersion + 1,
      publishedVersion: version,
      versions: [...policy.versions, version],
    }
    return HttpResponse.json(policy)
  }),
  http.post(`${root}/:id/archive`, ({ request }) => {
    expect(request.headers.get('If-Match')).toBe(`"${policy.aggregateVersion}"`)
    policy = {
      ...policy,
      lifecycle: 'ARCHIVED',
      aggregateVersion: policy.aggregateVersion + 1,
      publishedVersion: null,
    }
    return HttpResponse.json(policy)
  }),
]
const meta = {
  title: '06 Admin/Admin Customer Consent Page',
  component: AdminCustomerConsentPage,
  tags: ['autodocs'],
  parameters: {
    msw: { handlers },
    docs: {
      description: {
        component:
          'REQ-CONSENT-001의 기존 FROZEN API를 사용하는 ADMIN 정책 관리입니다. 빈 초안에서 시작하며 합성 story 문서는 배포/seed되지 않습니다. 발행/보관은 명시적 확인, stale/불확실 응답은 최신 상태 비교 후 선택을 요구합니다.',
      },
    },
  },
  beforeEach: () => {
    policy = initial()
    exists = false
    setConfirmedStaffActor(actor)
    return () => setConfirmedStaffActor(null)
  },
} satisfies Meta<typeof AdminCustomerConsentPage>
export default meta
type Story = StoryObj<typeof meta>

export const CreatePublishArchive: Story = {
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '정책 만들기' }),
    )
    await userEvent.type(
      canvas.getByLabelText('정책 키', { exact: false }),
      'synthetic-terms',
    )
    await userEvent.type(canvas.getByLabelText('정책 제목'), '합성 검증 정책')
    await userEvent.type(
      canvas.getByLabelText('항목 1 내용'),
      '합성 테스트 문서',
    )
    await userEvent.click(canvas.getByLabelText('필수 동의'))
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await expect(await canvas.findByText(/초안을 저장했습니다/)).toBeVisible()
    await expect(
      canvas.getByLabelText('정책 키', { exact: false }),
    ).toHaveAttribute('readonly')
    await userEvent.click(canvas.getByRole('button', { name: '정책 발행' }))
    await expect(canvas.getByText('이 초안을 지금 발행할까요?')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '확인 후 발행' }))
    await expect(await canvas.findByText(/정책을 발행했습니다/)).toBeVisible()
    await userEvent.click(
      canvas.getByText('버전 1 · 합성 검증 정책 · 현재 적용'),
    )
    await expect(canvas.getByText('합성 테스트 문서')).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '정책 보관' }))
    await userEvent.click(canvas.getByRole('button', { name: '확인 후 보관' }))
    await expect(await canvas.findByText(/정책을 보관했습니다/)).toBeVisible()
    await expect(canvas.getByLabelText('정책 제목')).toBeDisabled()
    await expect(canvas.getByText('버전 1 · 합성 검증 정책')).toBeVisible()
  },
}
export const ConflictPreservesDraft: Story = {
  beforeEach: () => {
    exists = true
  },
  parameters: {
    msw: {
      handlers: [
        ...handlers.filter((handler) => !handler.info.header.startsWith('PUT')),
        http.put(`${root}/:id`, async ({ request }) => {
          if (policy.aggregateVersion === 1) {
            policy = {
              ...policy,
              aggregateVersion: 2,
              draft: { ...policy.draft, title: '다른 관리자 수정' },
            }
            return HttpResponse.json(
              { status: 412, requestId: 'req-stale', currentVersion: 2 },
              { status: 412 },
            )
          }
          expect(request.headers.get('If-Match')).toBe('"2"')
          const body = (await request.json()) as ConsentDraft
          policy = {
            ...policy,
            aggregateVersion: 3,
            draft: { ...body, draftVersion: 3, updatedAt: timestamp },
          }
          return HttpResponse.json(policy)
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: 'synthetic-terms 열기' }),
    )
    const title = await canvas.findByLabelText('정책 제목')
    await userEvent.clear(title)
    await userEvent.type(title, '내가 수정한 초안')
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await expect(
      await canvas.findByText(/작성 내용은 유지했습니다/),
    ).toBeVisible()
    await expect(title).toHaveValue('내가 수정한 초안')
    await expect(
      canvas.getByRole('button', { name: '초안 저장' }),
    ).toBeDisabled()
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 정책과 비교' }),
    )
    await expect(await canvas.findByText(/다른 관리자 수정/)).toBeVisible()
    await expect(title).toHaveValue('내가 수정한 초안')
    await expect(
      canvas.getByRole('button', { name: '초안 저장' }),
    ).toBeDisabled()
    await userEvent.click(
      canvas.getByRole('button', { name: '현재 작성안 유지' }),
    )
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await expect(await canvas.findByText(/초안을 저장했습니다/)).toBeVisible()
    await expect(title).toHaveValue('내가 수정한 초안')
  },
}
export const UncertainSave: Story = {
  beforeEach: () => {
    exists = true
  },
  parameters: {
    msw: {
      handlers: [
        ...handlers.filter((handler) => !handler.info.header.startsWith('PUT')),
        http.put(`${root}/:id`, () => new HttpResponse(null, { status: 503 })),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: 'synthetic-terms 열기' }),
    )
    const title = await canvas.findByLabelText('정책 제목')
    await userEvent.type(title, ' 개정')
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await expect(
      await canvas.findByText(/작성 내용은 유지했습니다/),
    ).toBeVisible()
    await expect(title).toHaveValue('합성 검증 정책 개정')
    await expect(
      canvas.getByRole('button', { name: '정책 발행' }),
    ).toBeDisabled()
    await expect(
      canvas.getByRole('button', { name: '초안 저장' }),
    ).toBeDisabled()
  },
}
export const Empty: Story = {}
export const Denied: Story = {
  parameters: {
    msw: {
      handlers: [http.get(root, () => new HttpResponse(null, { status: 403 }))],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('고객 동의 정책 관리 권한이 필요합니다.'),
    ).toBeVisible()
    await expect(
      canvas.queryByRole('button', { name: '정책 만들기' }),
    ).not.toBeInTheDocument()
  },
}
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get(root, async () => {
          await delay('infinite')
          return HttpResponse.json({})
        }),
      ],
    },
  },
}
export const DocumentBlocks: Story = {
  beforeEach: () => {
    exists = true
    policy.draft.document.blocks = [
      { type: 'heading', level: 2, text: '합성 제목' },
      { type: 'paragraph', text: '합성 문단' },
      { type: 'list', ordered: true, items: ['첫 항목', '둘째 항목'] },
      { type: 'callout', text: '합성 안내' },
      { type: 'quote', text: '합성 인용' },
      { type: 'divider' },
      { type: 'link', text: '합성 문서', url: 'https://example.test/policy' },
    ]
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: 'synthetic-terms 열기' }),
    )
    await expect(await canvas.findByLabelText('항목 7 링크 주소')).toHaveValue(
      'https://example.test/policy',
    )
    await userEvent.click(canvas.getByRole('button', { name: '항목 6 삭제' }))
    await waitFor(() =>
      expect(canvas.getByLabelText('항목 6 링크 주소')).toHaveValue(
        'https://example.test/policy',
      ),
    )
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await expect(await canvas.findByText(/초안을 저장했습니다/)).toBeVisible()
  },
}

export const NavigationKeepsDraft: Story = {
  render: () => (
    <>
      <Link to="/consent-destination">다른 관리 화면</Link>
      <Routes>
        <Route
          path="/consent-destination"
          element={<h1>다른 관리 화면으로 이동했습니다</h1>}
        />
        <Route path="*" element={<AdminCustomerConsentPage />} />
      </Routes>
    </>
  ),
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '정책 만들기' }),
    )
    await userEvent.type(canvas.getByLabelText('정책 제목'), '보존할 초안')
    await userEvent.click(canvas.getByRole('link', { name: '다른 관리 화면' }))
    await expect(
      await canvas.findByRole('dialog', { name: '정책 작성 화면을 떠날까요?' }),
    ).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '계속 작성' }))
    await expect(canvas.getByLabelText('정책 제목')).toHaveValue('보존할 초안')
    await userEvent.click(canvas.getByRole('link', { name: '다른 관리 화면' }))
    await userEvent.click(
      canvas.getByRole('button', { name: '작성 내용 버리고 이동' }),
    )
    await expect(
      canvas.getByRole('heading', { name: '다른 관리 화면으로 이동했습니다' }),
    ).toBeVisible()
  },
}

export const ListFailureKeepsDraft: Story = {
  beforeEach: () => {
    exists = true
  },
  parameters: {
    msw: {
      handlers: [
        ...handlers.filter((handler) => handler.info.header !== `GET ${root}`),
        (() => {
          let reads = 0
          return http.get(root, () => {
            reads += 1
            if (reads === 2) return new HttpResponse(null, { status: 503 })
            return HttpResponse.json({
              items: [
                {
                  ...policy,
                  publishedVersion: null,
                  required: true,
                  displayOrder: 1,
                },
              ],
              page: 0,
              size: 20,
              totalPages: 1,
              totalCount: 1,
            })
          })
        })(),
      ],
    },
  },
  render: function Render() {
    const client = useQueryClient()
    return (
      <>
        <button
          onClick={() =>
            void client.invalidateQueries({
              queryKey: ['admin-consent-policies'],
            })
          }
        >
          목록 갱신
        </button>
        <AdminCustomerConsentPage />
      </>
    )
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: 'synthetic-terms 열기' }),
    )
    await userEvent.type(await canvas.findByLabelText('정책 제목'), ' 보존')
    await userEvent.click(canvas.getByRole('button', { name: '목록 갱신' }))
    await expect(
      await canvas.findByText('고객 동의 정책을 불러올 수 없습니다.'),
    ).toBeVisible()
    await userEvent.click(canvas.getByRole('button', { name: '다시 확인' }))
    await expect(await canvas.findByLabelText('정책 제목')).toHaveValue(
      '합성 검증 정책 보존',
    )
  },
}
