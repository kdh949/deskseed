import type { Meta, StoryObj } from '@storybook/react-vite'
import { http, HttpResponse, delay } from 'msw'
import { expect, userEvent, waitFor } from 'storybook/test'
import { MacroManagementPage } from './MacroManagementPage'
const macro = {
  id: '11111111-1111-4111-8111-111111111111',
  name: '접수 안내',
  scope: 'PERSONAL',
  ownerStaffId: '22222222-2222-4222-8222-222222222222',
  currentVersion: 2,
  activeVersion: 1,
  aggregateVersion: 3,
  actions: [
    {
      type: 'COMMENT',
      visibility: 'PUBLIC',
      template: '문의 내용을 확인하고 있습니다.',
    },
  ],
  createdAt: '2026-09-08T01:00:00Z',
  updatedAt: '2026-09-08T02:00:00Z',
}
const csrf = http.get('/api/v1/agent/csrf', () =>
  HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
)
const listing = http.get('/api/v1/agent/personal-macros', () =>
  HttpResponse.json([macro]),
)
const meta = {
  title: '07 Screens/Macro Management',
  component: MacroManagementPage,
  args: { scope: 'PERSONAL' },
  parameters: { msw: { handlers: [listing, csrf] } },
} satisfies Meta<typeof MacroManagementPage>
export default meta
type Story = StoryObj<typeof meta>
export const CreateAndReview: Story = {
  parameters: {
    msw: {
      handlers: [
        listing,
        csrf,
        http.post('/api/v1/agent/personal-macros', async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          await expect(body).toEqual({
            name: '환불 안내',
            actions: [
              { type: 'PRIORITY', priority: 'HIGH' },
              {
                type: 'COMMENT',
                visibility: 'INTERNAL',
                template: '주문 내역 확인 후 안내',
              },
            ],
          })
          await expect(request.headers.get('X-CSRF-TOKEN')).toBe(
            'storybook-csrf',
          )
          return HttpResponse.json(
            {
              ...macro,
              ...body,
              currentVersion: 1,
              activeVersion: null,
              aggregateVersion: 1,
            },
            { status: 201 },
          )
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await canvas.findByText('접수 안내')
    await userEvent.click(canvas.getByRole('button', { name: '매크로 만들기' }))
    await userEvent.type(canvas.getByLabelText(/매크로 이름/), '환불 안내')
    await userEvent.selectOptions(
      canvas.getByLabelText('답변 공개 범위'),
      'INTERNAL',
    )
    await userEvent.type(
      canvas.getByLabelText('답변 문구'),
      '주문 내역 확인 후 안내',
    )
    await userEvent.selectOptions(
      canvas.getByLabelText('변경할 우선순위'),
      'HIGH',
    )
    await expect(
      canvas.getByRole('region', { name: '저장 내용 미리보기' }),
    ).toHaveTextContent('내부 메모 · 상태 유지 · 우선순위 높음')
    await userEvent.click(canvas.getByRole('button', { name: '매크로 저장' }))
    await expect(await canvas.findByRole('status')).toHaveTextContent(
      '버전 1을 저장했습니다.',
    )
  },
}
const originalSharedActions = [
  { type: 'GROUP', groupId: 'group-a' },
  ...macro.actions,
  { type: 'PRIORITY', priority: 'NORMAL' },
]
const latestSharedActions = [
  { type: 'PRIORITY', priority: 'HIGH' },
  { type: 'GROUP', groupId: 'group-b' },
  ...macro.actions,
]
let conflictRequests = 0
export const ConflictPreservesDraft: Story = {
  beforeEach: () => {
    conflictRequests = 0
  },
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/personal-macros', () =>
          HttpResponse.json([
            conflictRequests === 0
              ? { ...macro, actions: originalSharedActions }
              : {
                  ...macro,
                  name: '최신 접수 안내',
                  actions: latestSharedActions,
                  currentVersion: 3,
                  aggregateVersion: 5,
                },
          ]),
        ),
        csrf,
        http.post(
          '/api/v1/agent/personal-macros/:id/versions',
          async ({ request }) => {
            conflictRequests++
            if (conflictRequests === 1) {
              expect(request.headers.get('If-Match')).toBe('"3"')
              return HttpResponse.json({ status: 412 }, { status: 412 })
            }
            expect(request.headers.get('If-Match')).toBe('"5"')
            expect(await request.json()).toEqual({
              name: '최신 접수 안내',
              actions: [
                latestSharedActions[0],
                latestSharedActions[1],
                {
                  type: 'COMMENT',
                  visibility: 'PUBLIC',
                  template: macro.actions[0]!.template + ' 추가 문구',
                },
              ],
            })
            return HttpResponse.json({
              ...macro,
              currentVersion: 4,
              aggregateVersion: 6,
            })
          },
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '편집: 접수 안내' }),
    )
    await userEvent.type(canvas.getByLabelText('답변 문구'), ' 추가 문구')
    await userEvent.click(canvas.getByRole('button', { name: '새 버전 저장' }))
    await expect(
      await canvas.findByText('최신 내용을 확인한 뒤 다시 저장하세요.'),
    ).toBeVisible()
    await expect(canvas.getByLabelText('답변 문구')).toHaveValue(
      '문의 내용을 확인하고 있습니다. 추가 문구',
    )
    await expect(
      canvas.getByRole('button', { name: '새 버전 저장' }),
    ).toBeDisabled()
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 목록 확인' }),
    )
    await waitFor(() =>
      expect(
        canvas.getByRole('button', { name: '새 버전 저장' }),
      ).toBeEnabled(),
    )
    await expect(canvas.getByLabelText('변경할 우선순위')).toHaveValue('HIGH')
    await expect(canvas.getByLabelText(/매크로 이름/)).toHaveValue(
      '최신 접수 안내',
    )
    await userEvent.click(canvas.getByRole('button', { name: '새 버전 저장' }))
    await expect(await canvas.findByRole('status')).toHaveTextContent(
      '버전 4을 저장했습니다.',
    )
  },
}
export const SharedActivationHistory: Story = {
  args: { scope: 'SHARED' },
  parameters: {
    msw: {
      handlers: [
        csrf,
        http.get('/api/v1/admin/shared-macros', () =>
          HttpResponse.json([
            { ...macro, scope: 'SHARED', ownerStaffId: null },
          ]),
        ),
        http.put(
          '/api/v1/admin/shared-macros/:id/activation',
          async ({ request }) => {
            await expect(await request.json()).toEqual({ version: 2 })
            await expect(request.headers.get('If-Match')).toBe('"3"')
            return HttpResponse.json({
              ...macro,
              scope: 'SHARED',
              ownerStaffId: null,
              activeVersion: 2,
              aggregateVersion: 4,
            })
          },
        ),
        http.get('/api/v1/admin/shared-macros/:id/history', () =>
          HttpResponse.json({
            versions: [
              {
                version: 2,
                name: '접수 안내',
                createdByDisplay: '김상담',
                createdAt: macro.updatedAt,
              },
            ],
            activations: [
              {
                version: 2,
                state: 'ACTIVE',
                actorDisplay: '이운영',
                occurredAt: macro.updatedAt,
              },
            ],
          }),
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', {
        name: '최신 버전 활성화: 접수 안내',
      }),
    )
    await expect(await canvas.findByRole('status')).toHaveTextContent(
      '버전 2이 활성화되었습니다.',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '이력: 접수 안내' }),
    )
    await expect(
      await canvas.findByText(/버전 2 활성화 · 이운영/),
    ).toBeVisible()
  },
}
export const Empty: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/personal-macros', () => HttpResponse.json([])),
      ],
    },
  },
}
export const Denied: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/personal-macros', () =>
          HttpResponse.json({ status: 403 }, { status: 403 }),
        ),
      ],
    },
  },
}
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/agent/personal-macros', async () => {
          await delay('infinite')
          return HttpResponse.json([])
        }),
      ],
    },
  },
}

let sameFieldRequests = 0
export const SameFieldConflictRequiresChoice: Story = {
  beforeEach: () => {
    sameFieldRequests = 0
  },
  parameters: {
    msw: {
      handlers: [
        csrf,
        http.get('/api/v1/agent/personal-macros', async () => {
          if (sameFieldRequests > 0) await delay(150)
          return HttpResponse.json([
            sameFieldRequests === 0
              ? macro
              : {
                  ...macro,
                  currentVersion: 3,
                  aggregateVersion: 5,
                  actions: [
                    {
                      type: 'COMMENT',
                      visibility: 'PUBLIC',
                      template: '다른 관리자의 문구',
                    },
                  ],
                },
          ])
        }),
        http.post('/api/v1/agent/personal-macros/:id/versions', () => {
          sameFieldRequests++
          return HttpResponse.json({ status: 412 }, { status: 412 })
        }),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '편집: 접수 안내' }),
    )
    await userEvent.type(canvas.getByLabelText('답변 문구'), ' 추가 문구')
    await userEvent.click(canvas.getByRole('button', { name: '새 버전 저장' }))
    await canvas.findByText('최신 내용을 확인한 뒤 다시 저장하세요.')
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 목록 확인' }),
    )
    await expect(
      await canvas.findByText('같은 항목에 다른 변경이 있습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '새 버전 저장' }),
    ).toBeDisabled()
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 목록 확인' }),
    )
    await expect(
      canvas.getByRole('button', { name: '새 버전 저장' }),
    ).toBeDisabled()
    await expect(
      canvas.getByRole('button', { name: '충돌 항목의 최신 값 사용' }),
    ).toBeDisabled()
    await expect(
      canvas.getByRole('button', { name: '내 편집값 유지' }),
    ).toBeDisabled()
    await waitFor(() =>
      expect(
        canvas.getByRole('button', { name: '충돌 항목의 최신 값 사용' }),
      ).toBeEnabled(),
    )
    await expect(
      canvas.getByRole('button', { name: '새 버전 저장' }),
    ).toBeDisabled()
    await userEvent.click(
      canvas.getByRole('button', { name: '충돌 항목의 최신 값 사용' }),
    )
    await expect(canvas.getByLabelText('답변 문구')).toHaveValue(
      '다른 관리자의 문구',
    )
    await waitFor(() =>
      expect(
        canvas.getByRole('button', { name: '새 버전 저장' }),
      ).toBeEnabled(),
    )
  },
}

let customStatusRequests = 0
export const NewCustomStatusRequiresLatestChoice: Story = {
  beforeEach: () => {
    customStatusRequests = 0
  },
  parameters: {
    msw: {
      handlers: [
        csrf,
        http.get('/api/v1/agent/personal-macros', () =>
          HttpResponse.json([
            customStatusRequests === 0
              ? macro
              : {
                  ...macro,
                  currentVersion: 3,
                  aggregateVersion: 5,
                  actions: [
                    ...macro.actions,
                    {
                      type: 'CUSTOM_STATUS',
                      customStatusId: '33333333-3333-4333-8333-333333333333',
                    },
                  ],
                },
          ]),
        ),
        http.post(
          '/api/v1/agent/personal-macros/:id/versions',
          async ({ request }) => {
            customStatusRequests++
            if (customStatusRequests === 1)
              return HttpResponse.json({ status: 412 }, { status: 412 })
            const body = (await request.json()) as {
              actions: { type: string }[]
            }
            expect(body.actions.map((a) => a.type)).toEqual([
              'COMMENT',
              'CUSTOM_STATUS',
            ])
            return HttpResponse.json({
              ...macro,
              currentVersion: 4,
              aggregateVersion: 6,
            })
          },
        ),
      ],
    },
  },
  play: async ({ canvas }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: '편집: 접수 안내' }),
    )
    await userEvent.selectOptions(
      canvas.getByLabelText('변경할 상태'),
      'PENDING',
    )
    await userEvent.click(canvas.getByRole('button', { name: '새 버전 저장' }))
    await canvas.findByText('최신 내용을 확인한 뒤 다시 저장하세요.')
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 목록 확인' }),
    )
    await expect(
      await canvas.findByText('같은 항목에 다른 변경이 있습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '내 편집값 유지' }),
    ).toBeDisabled()
    await expect(
      canvas.getByRole('button', { name: '새 버전 저장' }),
    ).toBeDisabled()
    await userEvent.click(
      canvas.getByRole('button', { name: '충돌 항목의 최신 값 사용' }),
    )
    await expect(canvas.getByLabelText('변경할 상태')).toHaveValue('')
    await userEvent.click(canvas.getByRole('button', { name: '새 버전 저장' }))
    await expect(await canvas.findByRole('status')).toHaveTextContent(
      '버전 4을 저장했습니다.',
    )
  },
}
