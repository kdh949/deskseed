import type { Meta, StoryObj } from '@storybook/react-vite'
import { delay, http, HttpResponse } from 'msw'
import { expect, waitFor } from 'storybook/test'
import { AdminAutomationsPage } from './AdminAutomationsPage'
import type { Automation } from './automation-api'
const rule: Automation = {
  id: '11111111-1111-4111-8111-111111111111',
  name: '해결 문의 종료',
  position: 10,
  currentVersion: 2,
  activeVersion: 1,
  aggregateVersion: 3,
  solvedAgeMinutes: 1440,
  actionType: 'CLOSE_TICKET',
  createdAt: '2026-09-08T01:00:00Z',
  updatedAt: '2026-09-08T02:00:00Z',
}
let rules = [rule]
const common = [
  http.get('/api/v1/admin/automations', () => HttpResponse.json(rules)),
  http.get('/api/v1/agent/csrf', () =>
    HttpResponse.json({ token: 'storybook-csrf', headerName: 'X-CSRF-TOKEN' }),
  ),
  http.get('/api/v1/admin/automations/:id/versions/:version', ({ params }) =>
    HttpResponse.json({
      ...(rules.find((item) => item.id === params.id) ?? rule),
      solvedAgeMinutes: Number(params.version) === 1 ? 60 : 1440,
    }),
  ),
  http.get('/api/v1/admin/automations/:id/history', () =>
    HttpResponse.json({
      versions: [
        {
          version: 2,
          name: rule.name,
          solvedAgeMinutes: 1440,
          createdByDisplay: '관리자',
          createdAt: rule.createdAt,
        },
        {
          version: 1,
          name: rule.name,
          solvedAgeMinutes: 60,
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
      executions: [
        {
          id: 'closed-1',
          version: 1,
          ticketNumber: 1041,
          outcome: 'CLOSED',
          completedAt: rule.updatedAt,
        },
      ],
      candidates: [
        {
          id: 'candidate-1',
          version: 1,
          ticketNumber: 1042,
          status: 'RETRY_SCHEDULED',
          attemptCount: 2,
          lastErrorCode: 'LEASE_EXPIRED',
          eligibleAt: rule.updatedAt,
          discoveredAt: rule.updatedAt,
        },
      ],
    }),
  ),
  http.post(
    '/api/v1/admin/automations/:id/versions/:version/dry-run',
    async ({ params, request }) => {
      const body = (await request.json()) as { ticketNumber: number }
      return HttpResponse.json({
        ticketNumber: body.ticketNumber,
        automationId: params.id,
        automationVersion: Number(params.version),
        status: 'SOLVED',
        solvedAt: '2026-09-05T00:00:00Z',
        eligibleAt: '2026-09-06T00:00:00Z',
        matched: true,
        proposedAction: 'CLOSE_TICKET',
      })
    },
  ),
  http.put(
    '/api/v1/admin/automations/:id/activation',
    async ({ params, request }) => {
      const current = rules.find((item) => item.id === params.id) ?? rule
      await expect(request.headers.get('If-Match')).toBe(
        `"${current.aggregateVersion}"`,
      )
      const body = (await request.json()) as { version: number }
      const saved = {
        ...current,
        activeVersion: body.version,
        aggregateVersion: current.aggregateVersion + 1,
      }
      rules = rules.map((item) => (item.id === saved.id ? saved : item))
      return HttpResponse.json(saved)
    },
  ),
  http.delete(
    '/api/v1/admin/automations/:id/activation',
    async ({ params, request }) => {
      const current = rules.find((item) => item.id === params.id) ?? rule
      await expect(request.headers.get('If-Match')).toBe(
        `"${current.aggregateVersion}"`,
      )
      const saved = {
        ...current,
        activeVersion: null,
        aggregateVersion: current.aggregateVersion + 1,
      }
      rules = rules.map((item) => (item.id === saved.id ? saved : item))
      return HttpResponse.json(saved)
    },
  ),
]
const meta = {
  title: '07 Screens/Admin Automations',
  component: AdminAutomationsPage,
  beforeEach: () => {
    rules = [structuredClone(rule)]
  },
  parameters: { msw: { handlers: common } },
} satisfies Meta<typeof AdminAutomationsPage>
export default meta
type Story = StoryObj<typeof meta>
export const CreatePreviewActivate: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post('/api/v1/admin/automations', async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          await expect(body).toEqual({
            name: '7일 후 종료',
            position: 20,
            solvedAgeMinutes: 10080,
            actionType: 'CLOSE_TICKET',
          })
          const saved = {
            ...rule,
            ...body,
            id: '22222222-2222-4222-8222-222222222222',
            currentVersion: 1,
            activeVersion: null,
            aggregateVersion: 1,
          } as Automation
          rules = [...rules, saved]
          return HttpResponse.json(saved, { status: 201 })
        }),
        ...common,
      ],
    },
  },
  play: async ({ canvas, userEvent }) => {
    await canvas.findByText(rule.name)
    await userEvent.click(canvas.getByRole('button', { name: '자동화 만들기' }))
    await userEvent.type(
      canvas.getByRole('textbox', { name: '자동화 이름' }),
      '7일 후 종료',
    )
    await userEvent.clear(
      canvas.getByRole('spinbutton', { name: '해결 후 경과 시간 (분)' }),
    )
    await userEvent.type(
      canvas.getByRole('spinbutton', { name: '해결 후 경과 시간 (분)' }),
      '10080',
    )
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await canvas.findByText('새 버전을 저장했습니다. 활성 버전은 유지됩니다.')
    await expect(
      canvas.getByRole('button', { name: '버전 1 활성화' }),
    ).toBeDisabled()
    await waitFor(() =>
      expect(canvas.getByLabelText('샘플 티켓 번호')).toBeEnabled(),
    )
    await userEvent.type(canvas.getByLabelText('샘플 티켓 번호'), '1042')
    await userEvent.click(
      canvas.getByRole('button', { name: '종료 조건 미리보기' }),
    )
    await canvas.findByText('현재 종료 조건에 해당합니다')
    await waitFor(() =>
      expect(
        canvas.getByRole('button', { name: '버전 1 활성화' }),
      ).toBeEnabled(),
    )
    await userEvent.click(canvas.getByRole('button', { name: '버전 1 활성화' }))
    await expect(
      await canvas.findByText('버전 1을 활성화했습니다.'),
    ).toBeVisible()
    await waitFor(() =>
      expect(
        canvas.getByRole('button', { name: '자동화 비활성화' }),
      ).toBeEnabled(),
    )
    await userEvent.click(
      canvas.getByRole('button', { name: '자동화 비활성화' }),
    )
    await expect(
      await canvas.findByText(
        '새 후보 발견을 중단했습니다. 이미 발견된 작업은 계속 처리됩니다.',
      ),
    ).toBeVisible()
  },
}
export const ConflictPreservesDraft: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post('/api/v1/admin/automations/:id/versions', () =>
          HttpResponse.json(
            {
              code: 'AUTOMATION_VERSION_CONFLICT',
              title: 'Conflict',
              status: 412,
            },
            { status: 412 },
          ),
        ),
        ...common,
      ],
    },
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: `${rule.name} 관리` }),
    )
    await userEvent.clear(canvas.getByRole('textbox', { name: '자동화 이름' }))
    await userEvent.type(
      canvas.getByRole('textbox', { name: '자동화 이름' }),
      '검토 중인 해결 후 종료 정책',
    )
    await userEvent.click(canvas.getByRole('button', { name: '새 버전 저장' }))
    await expect(
      await canvas.findByText('다른 변경과 충돌했습니다.'),
    ).toBeVisible()
    await expect(
      canvas.getByRole('textbox', { name: '자동화 이름' }),
    ).toHaveValue('검토 중인 해결 후 종료 정책')
    await expect(
      canvas.getByRole('button', { name: '새 버전 저장' }),
    ).toBeDisabled()
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 상태 확인 (입력 유지)' }),
    )
    await canvas.findByText('최신 상태를 확인했습니다. 현재 입력과 비교하세요.')
    await expect(
      canvas.getByRole('textbox', { name: '자동화 이름' }),
    ).toHaveValue('검토 중인 해결 후 종료 정책')
  },
}
export const UnknownCreateRequiresListReview: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post('/api/v1/admin/automations', () => HttpResponse.error()),
        ...common,
      ],
    },
  },
  play: async ({ canvas, userEvent }) => {
    await canvas.findByText(rule.name)
    await userEvent.click(canvas.getByRole('button', { name: '자동화 만들기' }))
    await userEvent.type(
      canvas.getByRole('textbox', { name: '자동화 이름' }),
      '응답 확인 필요',
    )
    await userEvent.click(canvas.getByRole('button', { name: '초안 저장' }))
    await canvas.findByText('요청 결과를 확인하지 못했습니다.')
    await userEvent.click(
      canvas.getByRole('button', { name: '최신 상태 확인 (입력 유지)' }),
    )
    await canvas.findByText(
      '목록을 새로고침했습니다. 편집기를 닫고 생성 여부를 확인하세요.',
    )
    await expect(
      canvas.getByRole('button', { name: '초안 저장' }),
    ).toBeDisabled()
    await expect(
      canvas.getByRole('textbox', { name: '자동화 이름' }),
    ).toHaveValue('응답 확인 필요')
  },
}
export const ReviewOlderVersionAndKeyboard: Story = {
  play: async ({ canvas, userEvent }) => {
    const opener = await canvas.findByRole('button', {
      name: `${rule.name} 관리`,
    })
    await userEvent.click(opener)
    await userEvent.clear(canvas.getByLabelText('조회할 버전'))
    await userEvent.type(canvas.getByLabelText('조회할 버전'), '1')
    await userEvent.click(
      canvas.getByRole('button', { name: '저장된 버전 불러오기' }),
    )
    await waitFor(() =>
      expect(
        canvas.getByRole('spinbutton', { name: '해결 후 경과 시간 (분)' }),
      ).toHaveValue(60),
    )
    await expect(
      canvas.getByRole('heading', { name: '버전 1 미리보기' }),
    ).toBeVisible()
    await expect(canvas.getByText(/재시도 대기/)).toBeVisible()
    await userEvent.keyboard('{Escape}')
    await expect(canvas.queryByRole('dialog')).not.toBeInTheDocument()
    await expect(opener).toHaveFocus()
  },
}
export const ReopenedTicketDoesNotMatch: Story = {
  parameters: {
    msw: {
      handlers: [
        http.post(
          '/api/v1/admin/automations/:id/versions/:version/dry-run',
          () =>
            HttpResponse.json({
              ticketNumber: 1042,
              automationId: rule.id,
              automationVersion: 2,
              status: 'OPEN',
              matched: false,
              proposedAction: 'CLOSE_TICKET',
            }),
        ),
        ...common,
      ],
    },
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(
      await canvas.findByRole('button', { name: `${rule.name} 관리` }),
    )
    await waitFor(() =>
      expect(canvas.getByLabelText('샘플 티켓 번호')).toBeEnabled(),
    )
    await userEvent.type(canvas.getByLabelText('샘플 티켓 번호'), '1042')
    await userEvent.click(
      canvas.getByRole('button', { name: '종료 조건 미리보기' }),
    )
    await expect(
      await canvas.findByText('현재 종료 조건에 해당하지 않습니다'),
    ).toBeVisible()
    await expect(canvas.getByText('해결 시각: 해당 없음')).toBeVisible()
    await expect(
      canvas.getByRole('button', { name: '버전 2 활성화' }),
    ).toBeEnabled()
    await userEvent.clear(
      canvas.getByRole('spinbutton', { name: '해결 후 경과 시간 (분)' }),
    )
    await userEvent.type(
      canvas.getByRole('spinbutton', { name: '해결 후 경과 시간 (분)' }),
      '60',
    )
    await expect(
      canvas.getByRole('button', { name: '버전 2 활성화' }),
    ).toBeDisabled()
  },
}
export const Empty: Story = {
  beforeEach: () => {
    rules = []
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('시간 자동화가 없습니다'),
    ).toBeVisible()
  },
}
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/automations', async () => {
          await delay('infinite')
        }),
        ...common,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('status')).toBeVisible()
  },
}
export const Denied: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/automations', () =>
          HttpResponse.json({ status: 403 }, { status: 403 }),
        ),
        ...common,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('자동화를 관리할 권한이 없습니다.'),
    ).toBeVisible()
  },
}
export const Error: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get('/api/v1/admin/automations', () => HttpResponse.error()),
        ...common,
      ],
    },
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByText('요청 결과를 확인하지 못했습니다.'),
    ).toBeVisible()
  },
}
