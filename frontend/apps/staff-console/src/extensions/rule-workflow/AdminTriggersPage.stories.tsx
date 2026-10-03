import type { Meta, StoryObj } from '@storybook/react-vite'
import { delay, http, HttpResponse } from 'msw'
import { expect, waitFor } from 'storybook/test'
import { AdminTriggersPage } from './AdminTriggersPage'
import type { Trigger } from './api'
const rule: Trigger = {
  id: '11111111-1111-4111-8111-111111111111',
  name: '재답변 분류',
  position: 10,
  currentVersion: 2,
  activeVersion: 1,
  aggregateVersion: 3,
  conditions: [
    { group: 'ALL', field: 'EVENT', operator: 'IS', value: 'CUSTOMER_REPLIED' },
  ],
  actions: [{ type: 'SET_PRIORITY', priority: 'HIGH' }],
  createdAt: '2026-09-08T01:00:00Z',
  updatedAt: '2026-09-08T02:00:00Z',
}
const groupId = '22222222-2222-4222-8222-222222222222'
let rules = [rule]
const common = [
  http.get('/api/v1/admin/triggers', () => HttpResponse.json(rules)),
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.get('/api/v1/agent/assignment-options', () =>
    HttpResponse.json({
      groups: [
        {
          id: groupId,
          name: '고객 지원',
          members: [
            {
              id: '33333333-3333-4333-8333-333333333333',
              displayName: '김상담',
            },
          ],
        },
      ],
    }),
  ),
  http.get('/api/v1/agent/ticket-configuration/filter-catalog', () =>
    HttpResponse.json({
      fields: [],
      tags: [{ id: '44444444-4444-4444-8444-444444444444', label: '환불' }],
      forms: [],
      statuses: [],
    }),
  ),
  http.get('/api/v1/admin/triggers/:id/versions/:version', ({ params }) =>
    HttpResponse.json({
      ...(rules.find((item) => item.id === params.id) ?? rule),
      actions: [
        {
          type: 'SET_PRIORITY',
          priority: Number(params.version) === 1 ? 'LOW' : 'HIGH',
        },
      ],
    }),
  ),
  http.get('/api/v1/admin/triggers/:id/history', () =>
    HttpResponse.json({
      versions: [
        {
          version: 2,
          name: rule.name,
          createdByDisplay: '관리자',
          createdAt: rule.createdAt,
        },
        {
          version: 1,
          name: '초기 분류',
          createdByDisplay: '관리자',
          createdAt: rule.createdAt,
        },
      ],
      activations: [
        {
          version: 1,
          state: 'ACTIVE',
          actorDisplay: '관리자',
          occurredAt: rule.updatedAt,
        },
      ],
      executions: [],
      jobs: [
        {
          id: groupId,
          ticketNumber: 1042,
          eventType: 'CUSTOMER_REPLIED',
          status: 'RETRY_SCHEDULED',
          attemptCount: 2,
          lastErrorCode: 'TICKET_ASSIGNMENT_INVALID',
          createdAt: rule.updatedAt,
        },
      ],
    }),
  ),
  http.post(
    '/api/v1/admin/triggers/:id/versions/:version/dry-run',
    async ({ params, request }) => {
      const body = (await request.json()) as {
        ticketNumber: number
        eventType: string
      }
      return HttpResponse.json({
        ticketNumber: body.ticketNumber,
        triggerId: params.id,
        triggerVersion: Number(params.version),
        matched: true,
        matchedConditions: [0, 1],
        unmatchedConditions: [],
        proposedActions: [
          'SET_PRIORITY',
          'SET_GROUP',
          'NOTIFY_UNASSIGNED_GROUP',
        ],
        invariantFailures: [],
      })
    },
  ),
  http.put(
    '/api/v1/admin/triggers/:id/activation',
    async ({ params, request }) => {
      const body = (await request.json()) as { version: number }
      const current = rules.find((item) => item.id === params.id) ?? rule
      await expect(request.headers.get('If-Match')).toBe(
        `"${current.aggregateVersion}"`,
      )
      const activated = {
        ...current,
        activeVersion: body.version,
        aggregateVersion: current.aggregateVersion + 1,
      }
      rules = rules.map((item) => (item.id === activated.id ? activated : item))
      return HttpResponse.json(activated)
    },
  ),
]
const meta = {
  title: '07 Screens/Admin Triggers',
  component: AdminTriggersPage,
  beforeEach: () => {
    rules = [structuredClone(rule)]
  },
  parameters: { msw: { handlers: common } },
} satisfies Meta<typeof AdminTriggersPage>
export default meta
type Story = StoryObj<typeof meta>
export const CreatePreviewActivate: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post('/api/v1/admin/triggers', async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          await expect(body).toMatchObject({
            name: '미배정 재답변 안내',
            position: 20,
            conditions: [
              {
                group: 'ALL',
                field: 'EVENT',
                operator: 'IS',
                value: 'CUSTOMER_REPLIED',
              },
              { group: 'ALL', field: 'ASSIGNEE', operator: 'NOT_PRESENT' },
            ],
            actions: [
              { type: 'SET_PRIORITY', priority: 'HIGH' },
              { type: 'SET_GROUP', groupId },
              { type: 'NOTIFY_UNASSIGNED_GROUP' },
            ],
          })
          const created = {
            ...rule,
            ...body,
            id: '55555555-5555-4555-8555-555555555555',
            currentVersion: 1,
            activeVersion: null,
            aggregateVersion: 1,
          } as Trigger
          rules = [...rules, created]
          return HttpResponse.json(created, { status: 201 })
        }),
        ...common,
      ],
    },
  },
  play: async ({ canvas, userEvent }) => {
    await canvas.findByText(rule.name)
    await userEvent.click(canvas.getByRole('button', { name: '트리거 만들기' }))
    await userEvent.type(
      canvas.getByLabelText(/트리거 이름/),
      '미배정 재답변 안내',
    )
    await userEvent.selectOptions(
      canvas.getByLabelText('조건 1 값'),
      'CUSTOMER_REPLIED',
    )
    await userEvent.click(canvas.getByRole('button', { name: '조건 추가' }))
    await userEvent.click(canvas.getByRole('button', { name: '액션 추가' }))
    await userEvent.selectOptions(canvas.getByLabelText('액션 2 그룹'), groupId)
    await userEvent.click(canvas.getByRole('button', { name: '액션 추가' }))
    await userEvent.selectOptions(
      canvas.getByLabelText('액션 3 종류'),
      'NOTIFY_UNASSIGNED_GROUP',
    )
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await expect(
      await canvas.findByText('버전 1을 저장했습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '이 버전 활성화' }),
    ).toBeDisabled()
    await userEvent.type(canvas.getByLabelText('샘플 티켓 번호'), '1042')
    await userEvent.selectOptions(
      canvas.getByLabelText('평가할 이벤트'),
      'CUSTOMER_REPLIED',
    )
    await userEvent.click(canvas.getByRole('button', { name: '미리보기' }))
    await expect(
      await canvas.findByText('샘플 티켓이 조건에 맞습니다.'),
    ).toBeVisible()
    await userEvent.click(
      canvas.getByRole('button', { name: '이 버전 활성화' }),
    )
    await expect(
      await canvas.findByText('버전 1을 활성화했습니다.'),
    ).toBeVisible()
  },
}
export const ConflictPreservesDraft: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post('/api/v1/admin/triggers/:id/versions', () =>
          HttpResponse.json({ title: 'Concurrent change' }, { status: 412 }),
        ),
        ...common,
      ],
    },
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: `관리: ${rule.name}` }),
    )
    await userEvent.clear(canvas.getByLabelText(/트리거 이름/))
    await userEvent.type(
      canvas.getByLabelText(/트리거 이름/),
      '수정 중인 재답변 규칙',
    )
    await expect(
      canvas.getByRole('button', { name: '미리보기' }),
    ).toBeDisabled()
    await userEvent.click(canvas.getByRole('button', { name: '새 버전 저장' }))
    await expect(
      await canvas.findByText('다른 변경과 충돌했습니다.'),
    ).toBeVisible()
    await expect(canvas.getByLabelText(/트리거 이름/)).toHaveValue(
      '수정 중인 재답변 규칙',
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 상태 확인 (입력 유지)' }),
    )
    await expect(
      await canvas.findByText(
        '최신 상태를 확인했습니다. 현재 입력과 버전을 비교하세요.',
      ),
    ).toBeVisible()
    await expect(canvas.getByLabelText(/트리거 이름/)).toHaveValue(
      '수정 중인 재답변 규칙',
    )
  },
}
export const InvariantBlocksActivation: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post('/api/v1/admin/triggers/:id/versions/:version/dry-run', () =>
          HttpResponse.json({
            ticketNumber: 1042,
            triggerId: rule.id,
            triggerVersion: 2,
            matched: true,
            matchedConditions: [0],
            unmatchedConditions: [],
            proposedActions: ['SET_PRIORITY'],
            invariantFailures: ['TARGET_ASSIGNEE_NOT_ACTIVE_MEMBER'],
          }),
        ),
        ...common,
      ],
    },
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: `관리: ${rule.name}` }),
    )
    await userEvent.type(canvas.getByLabelText('샘플 티켓 번호'), '1042')
    await userEvent.click(canvas.getByRole('button', { name: '미리보기' }))
    await expect(
      await canvas.findByText('담당자가 최종 그룹의 활성 구성원이 아닙니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '이 버전 활성화' }),
    ).toBeDisabled()
    await expect(canvas.getByText(/재시도 예정/)).toBeVisible()
  },
}
export const VersionReviewAndKeyboard: Story = {
  play: async ({ canvas, userEvent }) => {
    const trigger = await canvas.findByRole('button', {
      name: `관리: ${rule.name}`,
    })
    await userEvent.click(trigger)
    await userEvent.click(
      await canvas.findByRole('button', { name: '버전 1 불러오기' }),
    )
    await waitFor(() =>
      expect(canvas.getByLabelText('액션 1 우선순위')).toHaveValue('LOW'),
    )
    await expect(
      canvas.getByRole('heading', { name: '버전 1 검증' }),
    ).toBeVisible()
    await userEvent.keyboard('{Escape}')
    await expect(canvas.queryByRole('dialog')).not.toBeInTheDocument()
    await expect(trigger).toHaveFocus()
  },
}
export const Empty: Story = {
  beforeEach: () => {
    rules = []
  },
}
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/triggers', async () => {
          await delay('infinite')
          return HttpResponse.json([])
        }),
        ...common,
      ],
    },
  },
}
export const Denied: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/triggers', () =>
          HttpResponse.json({ title: 'Forbidden' }, { status: 403 }),
        ),
        ...common,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('규칙을 관리할 권한이 없습니다.'),
    ).toBeVisible()
  },
}
export const Error: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/triggers', () =>
          HttpResponse.json({ title: 'Unavailable' }, { status: 503 }),
        ),
        ...common,
      ],
    },
  },
}
