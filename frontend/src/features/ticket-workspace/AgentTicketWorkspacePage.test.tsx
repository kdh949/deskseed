import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  Outlet,
  RouterProvider,
  createMemoryRouter,
  useNavigate,
} from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AgentTicketWorkspacePage } from './AgentTicketWorkspacePage'

vi.mock('../staff-auth/StaffSessionContext', () => ({
  useStaffSession: () => ({
    status: 'authenticated',
    staff: { id: 'agent-id', displayName: '상담사', role: 'AGENT' },
  }),
}))

const detail = {
  ticket: {
    ticketNumber: 1042,
    subject: '결제 승인 오류',
    status: 'OPEN',
    priority: 'URGENT',
    requester: { id: 'customer-1', type: 'CUSTOMER', displayName: '김민수' },
    group: { id: 'group-payments', name: '결제 지원' },
    assignee: { id: 'agent-2', displayName: '다른 그룹 상담사' },
    updatedAt: '2026-08-10T10:02:00Z',
    version: 3,
    isChild: false,
    openChildCount: 0,
    sla: null,
  },
  comments: [
    {
      id: 'comment-public',
      visibility: 'PUBLIC',
      actor: { id: 'customer-1', type: 'CUSTOMER', displayName: '김민수' },
      body: '결제가 계속 실패합니다.',
      createdAt: '2026-08-10T09:00:00Z',
      source: 'WEB',
      attachments: [],
    },
    {
      id: 'comment-internal',
      visibility: 'INTERNAL',
      actor: { id: 'agent-2', type: 'STAFF', displayName: '다른 그룹 상담사' },
      body: 'PG사 확인이 필요합니다.',
      createdAt: '2026-08-10T09:30:00Z',
      source: 'STAFF_WEB',
      attachments: [],
    },
  ],
  capabilities: ['READ'],
  assignmentOptions: {
    groups: [
      {
        id: 'group-payments',
        name: '결제 지원',
        members: [{ id: 'agent-2', displayName: '다른 그룹 상담사' }],
      },
      {
        id: 'group-support',
        name: '고객 지원',
        members: [{ id: 'agent-id', displayName: '상담사' }],
      },
    ],
  },
  context: {
    customer: {
      id: 'customer-1',
      displayName: '김민수',
      email: 'minsu@example.com',
    },
    parent: null,
    children: [],
    externalReferences: [],
  },
  history: [
    {
      id: 'history-1',
      eventType: 'TICKET_CREATED',
      actor: { id: 'customer-1', type: 'CUSTOMER', displayName: '김민수' },
      occurredAt: '2026-08-10T09:00:00Z',
    },
  ],
  warnings: [],
}

function RouteNavigationControls() {
  const navigate = useNavigate()
  return (
    <>
      <button type="button" onClick={() => navigate('/agent/tickets/1042')}>
        티켓 1042 열기
      </button>
      <button type="button" onClick={() => navigate('/agent/tickets/1043')}>
        티켓 1043 열기
      </button>
    </>
  )
}

function TestLayout() {
  return (
    <>
      <RouteNavigationControls />
      <Outlet />
    </>
  )
}

function renderPage(path = '/agent/tickets/1042') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const router = createMemoryRouter(
    [
      {
        element: <TestLayout />,
        children: [
          {
            path: '/agent/tickets/:ticketNumber',
            element: <AgentTicketWorkspacePage />,
          },
        ],
      },
    ],
    { initialEntries: [path] },
  )
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('AgentTicketWorkspacePage', () => {
  it('renders the read-only three-panel staff projection with public and internal conversation', async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify(detail), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    expect(
      await screen.findByRole('heading', { name: '결제 승인 오류' }),
    ).toBeVisible()
    expect(screen.getByRole('region', { name: '티켓 속성' })).toBeVisible()
    expect(screen.getByRole('region', { name: '대화' })).toBeVisible()
    expect(screen.getByRole('region', { name: '티켓 컨텍스트' })).toBeVisible()
    expect(screen.getByText('결제가 계속 실패합니다.')).toBeVisible()
    expect(screen.getByText('PG사 확인이 필요합니다.')).toBeVisible()
    expect(screen.getByText('내부 메모')).toBeVisible()
    expect(screen.getByText('읽기 전용')).toBeVisible()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()

    const request = fetchMock.mock.calls[0]!
    const requestOptions = request[1] as { headers: Record<string, string> }
    expect(request[0]).toBe('/api/v1/agent/tickets/1042')
    expect(requestOptions.headers['X-Deskseed-Read-Intent']).toBe('NAVIGATION')
    expect(requestOptions.headers['X-Interaction-Id']).toBeTruthy()
  })

  it('reuses the navigation interaction for refetch and supports keyboard panel controls', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify(detail), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await screen.findByRole('heading', { name: '결제 승인 오류' })
    await user.click(screen.getByRole('button', { name: '티켓 새로고침' }))

    expect(fetchMock).toHaveBeenCalledTimes(2)
    const firstOptions = fetchMock.mock.calls[0]![1] as {
      headers: Record<string, string>
    }
    const secondOptions = fetchMock.mock.calls[1]![1] as {
      headers: Record<string, string>
    }
    expect(secondOptions.headers['X-Interaction-Id']).toBe(
      firstOptions.headers['X-Interaction-Id'],
    )
    expect(secondOptions.headers['X-Deskseed-Read-Intent']).toBe('BACKGROUND')

    await user.click(screen.getByRole('button', { name: '티켓 1043 열기' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    await user.click(screen.getByRole('button', { name: '티켓 1042 열기' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4))

    const thirdOptions = fetchMock.mock.calls[2]![1] as {
      headers: Record<string, string>
    }
    const fourthOptions = fetchMock.mock.calls[3]![1] as {
      headers: Record<string, string>
    }
    expect(thirdOptions.headers['X-Interaction-Id']).not.toBe(
      firstOptions.headers['X-Interaction-Id'],
    )
    expect(fourthOptions.headers['X-Interaction-Id']).not.toBe(
      firstOptions.headers['X-Interaction-Id'],
    )
    expect(fourthOptions.headers['X-Interaction-Id']).not.toBe(
      thirdOptions.headers['X-Interaction-Id'],
    )

    const propertySeparator = screen.getByRole('separator', {
      name: '속성 패널 너비 조절',
    })
    propertySeparator.focus()
    await user.keyboard('{ArrowRight}')
    expect(
      localStorage.getItem('deskseed:agent:agent-id:workspace-panels:v1'),
    ).toContain('propertyWidth')

    await user.click(screen.getByRole('button', { name: '컨텍스트 패널 접기' }))
    expect(
      screen.queryByRole('region', { name: '티켓 컨텍스트' }),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '컨텍스트 패널 펼치기' }),
    ).toBeVisible()
  })

  it('shows a safe denied state with a request id', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            title: 'Forbidden',
            status: 403,
            requestId: 'request-safe-403',
          }),
          {
            status: 403,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        ),
      ),
    )

    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '티켓을 열 수 없습니다.',
    )
    expect(screen.getByRole('alert')).toHaveTextContent('request-safe-403')
  })

  it.each(['not-a-number', '0', '-1', '9007199254740992'])(
    'shows a validation state without fetching for invalid ticket route %s',
    async (ticketNumber) => {
      const fetchMock = vi.fn()
      vi.stubGlobal('fetch', fetchMock)

      renderPage(`/agent/tickets/${ticketNumber}`)

      expect(
        await screen.findByRole('heading', {
          name: '티켓 번호를 확인할 수 없습니다.',
        }),
      ).toBeVisible()
      expect(fetchMock).not.toHaveBeenCalled()
    },
  )

  it('uses an RFC 4122 UUID when randomUUID is unavailable', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(detail), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes: Uint8Array) => bytes.fill(0xab),
    })

    renderPage()
    await screen.findByRole('heading', { name: '결제 승인 오류' })

    const request = fetchMock.mock.calls[0]!
    const options = request[1] as { headers: Record<string, string> }
    expect(options.headers['X-Interaction-Id']).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
  })

  it('submits one combined command and clears only the successful mode draft', async () => {
    const user = userEvent.setup()
    const editableDetail = {
      ...detail,
      capabilities: ['READ', 'UPDATE'],
    }
    const updatedDetail = {
      ...editableDetail,
      ticket: {
        ...editableDetail.ticket,
        status: 'PENDING',
        priority: 'HIGH',
        group: { id: 'group-support', name: '고객 지원' },
        assignee: { id: 'agent-id', displayName: '상담사' },
        version: 4,
      },
      comments: [
        ...editableDetail.comments,
        {
          id: 'comment-public-2',
          visibility: 'PUBLIC',
          actor: { id: 'agent-id', type: 'STAFF', displayName: '상담사' },
          body: '고객 안내 답변',
          createdAt: '2026-08-10T10:10:00Z',
          source: 'AGENT_UI',
          attachments: [],
        },
      ],
    }
    let detailReads = 0
    const commandBodies: unknown[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: string, init?: RequestInit) => {
        if (input === '/api/v1/agent/csrf') {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                token: 'csrf-token',
                headerName: 'X-CSRF-TOKEN',
              }),
              { status: 200 },
            ),
          )
        }
        if (input.endsWith('/commands')) {
          commandBodies.push(JSON.parse(String(init?.body)))
          return Promise.resolve(
            new Response(
              JSON.stringify({
                ticketNumber: 1042,
                version: 4,
                auditId: 'audit-id',
                warnings: [],
              }),
              { status: 200 },
            ),
          )
        }
        detailReads += 1
        return Promise.resolve(
          new Response(
            JSON.stringify(detailReads === 1 ? editableDetail : updatedDetail),
            { status: 200 },
          ),
        )
      }),
    )

    renderPage()
    await screen.findByRole('heading', { name: '결제 승인 오류' })
    await user.click(screen.getByRole('tab', { name: '내부 메모' }))
    await user.type(
      screen.getByRole('textbox', { name: '내부 메모' }),
      '보존할 내부 초안',
    )
    await user.click(screen.getByRole('tab', { name: '공개 답변' }))
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변' }),
      '고객 안내 답변',
    )
    await user.selectOptions(
      screen.getByRole('combobox', { name: '상태' }),
      'PENDING',
    )
    await user.selectOptions(
      screen.getByRole('combobox', { name: '우선순위' }),
      'HIGH',
    )
    await user.selectOptions(
      screen.getByRole('combobox', { name: '그룹' }),
      'group-support',
    )
    await user.selectOptions(
      screen.getByRole('combobox', { name: '담당자' }),
      'agent-id',
    )
    await user.click(screen.getByRole('button', { name: '변경사항 저장' }))

    await waitFor(() => expect(commandBodies).toHaveLength(1))
    expect(commandBodies[0]).toMatchObject({
      expectedVersion: 3,
      changedFields: ['status', 'priority', 'groupId', 'assigneeId'],
      status: 'PENDING',
      priority: 'HIGH',
      groupId: 'group-support',
      assigneeId: 'agent-id',
      comment: { visibility: 'PUBLIC', body: '고객 안내 답변' },
    })
    expect(screen.getByRole('textbox', { name: '공개 답변' })).toHaveValue('')
    await user.click(screen.getByRole('tab', { name: '내부 메모' }))
    expect(screen.getByRole('textbox', { name: '내부 메모' })).toHaveValue(
      '보존할 내부 초안',
    )
  })

  it('disables duplicate saves while the combined command is submitting', async () => {
    const user = userEvent.setup()
    const editableDetail = { ...detail, capabilities: ['READ', 'UPDATE'] }
    let resolveCommand!: (response: Response) => void
    const commandResponse = new Promise<Response>((resolve) => {
      resolveCommand = resolve
    })
    let commandCalls = 0
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: string) => {
        if (input === '/api/v1/agent/csrf') {
          return Promise.resolve(
            new Response(
              JSON.stringify({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }),
              { status: 200 },
            ),
          )
        }
        if (input.endsWith('/commands')) {
          commandCalls += 1
          return commandResponse
        }
        return Promise.resolve(
          new Response(JSON.stringify(editableDetail), { status: 200 }),
        )
      }),
    )

    renderPage()
    await screen.findByRole('heading', { name: '결제 승인 오류' })
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변' }),
      '한 번만 저장할 답변',
    )
    const save = screen.getByRole('button', { name: '변경사항 저장' })
    await user.click(save)
    expect(save).toBeDisabled()
    await user.click(save)
    await waitFor(() => expect(commandCalls).toBe(1))

    resolveCommand(
      new Response(
        JSON.stringify({
          ticketNumber: 1042,
          version: 4,
          auditId: 'audit-once',
          warnings: [],
        }),
        { status: 200 },
      ),
    )
    expect(await screen.findByText(/공개 답변과 변경사항/)).toBeVisible()
    expect(save).toBeDisabled()
  })

  it('focuses the property conflict banner and preserves local fields and both drafts', async () => {
    const user = userEvent.setup()
    const editableDetail = { ...detail, capabilities: ['READ', 'UPDATE'] }
    const latestDetail = {
      ...editableDetail,
      ticket: { ...editableDetail.ticket, priority: 'URGENT', version: 4 },
    }
    let detailReads = 0
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: string) => {
        if (input === '/api/v1/agent/csrf') {
          return Promise.resolve(
            new Response(
              JSON.stringify({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }),
              { status: 200 },
            ),
          )
        }
        if (input.endsWith('/commands')) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                type: '/problems/ticket-field-conflict',
                status: 409,
                requestId: 'request-conflict-409',
                currentVersion: 4,
                conflictingFields: ['priority'],
              }),
              { status: 409 },
            ),
          )
        }
        detailReads += 1
        return Promise.resolve(
          new Response(
            JSON.stringify(detailReads === 1 ? editableDetail : latestDetail),
            { status: 200 },
          ),
        )
      }),
    )

    renderPage()
    await screen.findByRole('heading', { name: '결제 승인 오류' })
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변' }),
      '고객 답변 보존',
    )
    await user.click(screen.getByRole('tab', { name: '내부 메모' }))
    await user.type(
      screen.getByRole('textbox', { name: '내부 메모' }),
      '내부 메모 보존',
    )
    await user.click(screen.getByRole('tab', { name: '공개 답변' }))
    await user.selectOptions(
      screen.getByRole('combobox', { name: '우선순위' }),
      'HIGH',
    )
    await user.click(screen.getByRole('button', { name: '변경사항 저장' }))

    const conflict = await screen.findByRole('alert', {
      name: /변경 충돌/,
    })
    expect(document.activeElement).toBe(conflict)
    expect(conflict).toHaveTextContent('우선순위')
    expect(conflict).toHaveTextContent('request-conflict-409')
    expect(screen.getByRole('combobox', { name: '우선순위' })).toHaveValue(
      'HIGH',
    )
    expect(screen.getByRole('textbox', { name: '공개 답변' })).toHaveValue(
      '고객 답변 보존',
    )
    await user.click(screen.getByRole('tab', { name: '내부 메모' }))
    expect(screen.getByRole('textbox', { name: '내부 메모' })).toHaveValue(
      '내부 메모 보존',
    )
    expect(screen.getByRole('button', { name: '변경사항 저장' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '내 변경으로 재시도' }))
    expect(screen.getByRole('button', { name: '변경사항 저장' })).toBeEnabled()
  })

  it('warns before ticket navigation when a draft is unsaved', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({ ...detail, capabilities: ['READ', 'UPDATE'] }),
            { status: 200 },
          ),
        ),
    )

    renderPage()
    await screen.findByRole('heading', { name: '결제 승인 오류' })
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변' }),
      '저장하지 않은 답변',
    )
    await user.click(screen.getByRole('button', { name: '티켓 1043 열기' }))

    expect(await screen.findByRole('dialog')).toHaveTextContent(
      '저장하지 않은 변경사항',
    )
    expect(
      screen.getByRole('heading', { name: '결제 승인 오류' }),
    ).toBeVisible()
  })

  it('offers INTERNAL composition only for an internal child ticket', async () => {
    const user = userEvent.setup()
    const childDetail = {
      ...detail,
      ticket: {
        ...detail.ticket,
        subject: '고객 비노출 내부 조사',
        isChild: true,
      },
      capabilities: ['READ', 'UPDATE'],
      context: {
        ...detail.context,
        parent: { ...detail.ticket, ticketNumber: 1001 },
      },
    }
    const commands: Array<Record<string, unknown>> = []
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: string, init?: RequestInit) => {
        if (input === '/api/v1/agent/csrf') {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                token: 'csrf-child',
                headerName: 'X-CSRF-TOKEN',
              }),
              { status: 200 },
            ),
          )
        }
        if (input.endsWith('/commands')) {
          commands.push(JSON.parse(String(init?.body)))
          return Promise.resolve(
            new Response(
              JSON.stringify({
                ticketNumber: 1042,
                version: 4,
                auditId: 'child-note-audit',
                warnings: [],
              }),
              { status: 200 },
            ),
          )
        }
        return Promise.resolve(
          new Response(JSON.stringify(childDetail), { status: 200 }),
        )
      }),
    )

    renderPage()
    await screen.findByRole('heading', { name: '고객 비노출 내부 조사' })
    expect(
      screen.queryByRole('tab', { name: '공개 답변' }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '내부 메모' })).toBeVisible()
    await user.type(
      screen.getByRole('textbox', { name: '내부 메모' }),
      'child 전용 내부 진행 상황',
    )
    await user.click(screen.getByRole('button', { name: '변경사항 저장' }))

    await waitFor(() => expect(commands).toHaveLength(1))
    expect(commands[0]).toMatchObject({
      comment: {
        visibility: 'INTERNAL',
        body: 'child 전용 내부 진행 상황',
      },
    })
  })

  it('shows related parent and children and submits an accessible child dialog command', async () => {
    const user = userEvent.setup()
    const editableDetail = {
      ...detail,
      capabilities: ['READ', 'UPDATE'],
      context: {
        ...detail.context,
        parent: {
          ...detail.ticket,
          ticketNumber: 1001,
          subject: '원래 고객 문의',
          isChild: false,
        },
        children: [
          {
            ...detail.ticket,
            ticketNumber: 1043,
            subject: '기존 결제 조사',
            isChild: true,
          },
        ],
      },
    }
    const commandCalls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: string, init?: RequestInit) => {
        if (input === '/api/v1/agent/csrf') {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                token: 'csrf-child',
                headerName: 'X-CSRF-TOKEN',
              }),
              { status: 200 },
            ),
          )
        }
        if (input.endsWith('/children')) {
          commandCalls.push([input, init])
          return Promise.resolve(
            new Response(
              JSON.stringify({
                parentTicketNumber: 1042,
                parentVersion: 4,
                childTicketNumber: 1044,
                parentAuditId: 'parent-audit-id',
                childAuditId: 'child-audit-id',
              }),
              { status: 201 },
            ),
          )
        }
        return Promise.resolve(
          new Response(JSON.stringify(editableDetail), { status: 200 }),
        )
      }),
    )

    renderPage()
    await screen.findByRole('heading', { name: '결제 승인 오류' })
    await user.click(screen.getByRole('tab', { name: '관련' }))
    expect(
      screen.getByRole('link', { name: /#1001 원래 고객 문의/ }),
    ).toBeVisible()
    expect(
      screen.getByRole('link', { name: /#1043 기존 결제 조사/ }),
    ).toBeVisible()

    const trigger = screen.getByRole('button', { name: '내부 child 만들기' })
    await user.click(trigger)
    const dialog = screen.getByRole('dialog', { name: '내부 child 만들기' })
    expect(dialog).toBeVisible()
    expect(document.activeElement).toBe(
      screen.getByRole('textbox', { name: 'Child 제목' }),
    )
    await user.type(
      screen.getByRole('textbox', { name: 'Child 제목' }),
      '신규 승인 로그 확인',
    )
    await user.type(
      screen.getByRole('textbox', { name: '내부 작업 설명' }),
      '고객에게 비노출되는 조사 메모',
    )
    await user.selectOptions(
      screen.getByRole('combobox', { name: '대상 그룹' }),
      'group-support',
    )
    await user.selectOptions(
      screen.getByRole('combobox', { name: '대상 담당자' }),
      'agent-id',
    )
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Child 우선순위' }),
      'HIGH',
    )
    await user.click(screen.getByRole('button', { name: 'Child 생성' }))

    await waitFor(() => expect(commandCalls).toHaveLength(1))
    expect(commandCalls[0]?.[0]).toBe('/api/v1/agent/tickets/1042/children')
    expect(commandCalls[0]?.[1]?.headers).toMatchObject({
      'If-Match': '"3"',
      'X-CSRF-TOKEN': 'csrf-child',
    })
    expect(JSON.parse(String(commandCalls[0]?.[1]?.body))).toMatchObject({
      expectedVersion: 3,
      subject: '신규 승인 로그 확인',
      body: '고객에게 비노출되는 조사 메모',
      groupId: 'group-support',
      assigneeId: 'agent-id',
      priority: 'HIGH',
    })
    await waitFor(() =>
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
    )
    expect(trigger).toHaveFocus()
  })

  it('submits transfer through its own ETag guarded dialog and restores focus', async () => {
    const user = userEvent.setup()
    const editableDetail = { ...detail, capabilities: ['READ', 'UPDATE'] }
    const commandCalls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: string, init?: RequestInit) => {
        if (input === '/api/v1/agent/csrf') {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                token: 'csrf-transfer',
                headerName: 'X-CSRF-TOKEN',
              }),
              { status: 200 },
            ),
          )
        }
        if (input.endsWith('/transfer')) {
          commandCalls.push([input, init])
          return Promise.resolve(
            new Response(
              JSON.stringify({
                ticketNumber: 1042,
                version: 4,
                auditId: 'transfer-audit-id',
                warnings: [],
              }),
              { status: 200 },
            ),
          )
        }
        return Promise.resolve(
          new Response(JSON.stringify(editableDetail), { status: 200 }),
        )
      }),
    )

    renderPage()
    await screen.findByRole('heading', { name: '결제 승인 오류' })
    await user.click(screen.getByRole('tab', { name: '관련' }))
    const trigger = screen.getByRole('button', { name: '티켓 이관' })
    await user.click(trigger)
    expect(screen.getByRole('dialog', { name: '티켓 이관' })).toBeVisible()
    await user.selectOptions(
      screen.getByRole('combobox', { name: '대상 그룹' }),
      'group-support',
    )
    await user.selectOptions(
      screen.getByRole('combobox', { name: '대상 담당자' }),
      'agent-id',
    )
    await user.type(
      screen.getByRole('textbox', { name: '이관 사유 (내부 메모)' }),
      '고객 지원 그룹이 응답 책임을 인수합니다.',
    )
    await user.click(screen.getByRole('button', { name: '소유권 이관' }))

    await waitFor(() => expect(commandCalls).toHaveLength(1))
    expect(commandCalls[0]?.[0]).toBe('/api/v1/agent/tickets/1042/transfer')
    expect(commandCalls[0]?.[1]?.headers).toMatchObject({
      'If-Match': '"3"',
      'X-CSRF-TOKEN': 'csrf-transfer',
    })
    expect(JSON.parse(String(commandCalls[0]?.[1]?.body))).toMatchObject({
      expectedVersion: 3,
      groupId: 'group-support',
      assigneeId: 'agent-id',
      reason: '고객 지원 그룹이 응답 책임을 인수합니다.',
    })
    await waitFor(() =>
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
    )
    expect(trigger).toHaveFocus()
  })

  it('announces the non blocking open child warning after parent solve', async () => {
    const user = userEvent.setup()
    const editableDetail = { ...detail, capabilities: ['READ', 'UPDATE'] }
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: string) => {
        if (input === '/api/v1/agent/csrf') {
          return Promise.resolve(
            new Response(
              JSON.stringify({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }),
              { status: 200 },
            ),
          )
        }
        if (input.endsWith('/commands')) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                ticketNumber: 1042,
                version: 4,
                auditId: 'solve-audit-id',
                warnings: [
                  {
                    code: 'OPEN_CHILD_TICKETS',
                    message: '열린 child ticket 2개가 있지만 저장되었습니다.',
                    count: 2,
                    relatedTicketNumbers: [1043, 1044],
                  },
                ],
              }),
              { status: 200 },
            ),
          )
        }
        return Promise.resolve(
          new Response(JSON.stringify(editableDetail), { status: 200 }),
        )
      }),
    )

    renderPage()
    await screen.findByRole('heading', { name: '결제 승인 오류' })
    await user.selectOptions(
      screen.getByRole('combobox', { name: '상태' }),
      'SOLVED',
    )
    await user.click(screen.getByRole('button', { name: '변경사항 저장' }))

    const warning = await screen.findByRole('alert', {
      name: /열린 child ticket/,
    })
    expect(warning).toHaveTextContent('2개')
    expect(warning).toHaveTextContent('#1043')
    expect(warning).toHaveTextContent('#1044')
  })

  it('restores PUBLIC and INTERNAL drafts independently after moving between tickets', async () => {
    const user = userEvent.setup()
    const editableDetail = { ...detail, capabilities: ['READ', 'UPDATE'] }
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((input: string) => {
        const ticketNumber = input.endsWith('/1043') ? 1043 : 1042
        return Promise.resolve(
          new Response(
            JSON.stringify({
              ...editableDetail,
              ticket: {
                ...editableDetail.ticket,
                ticketNumber,
                subject:
                  ticketNumber === 1043 ? '배송 일정 문의' : '결제 승인 오류',
              },
            }),
            { status: 200 },
          ),
        )
      }),
    )

    renderPage()
    await screen.findByRole('heading', { name: '결제 승인 오류' })
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변' }),
      '1042 공개 초안',
    )
    expect(
      screen.getByRole('tab', { name: /저장하지 않은 변경사항/ }),
    ).toBeVisible()
    await user.click(screen.getByRole('tab', { name: '내부 메모' }))
    await user.type(
      screen.getByRole('textbox', { name: '내부 메모' }),
      '1042 내부 초안',
    )

    await user.click(screen.getByRole('button', { name: '티켓 1043 열기' }))
    await user.click(
      await screen.findByRole('button', { name: '초안 유지하고 나가기' }),
    )
    await screen.findByRole('heading', { name: '배송 일정 문의' })
    expect(screen.getByRole('textbox', { name: '공개 답변' })).toHaveValue('')
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변' }),
      '1043 공개 초안',
    )

    await user.click(screen.getByRole('button', { name: '티켓 1042 열기' }))
    await user.click(
      await screen.findByRole('button', { name: '초안 유지하고 나가기' }),
    )
    await screen.findByRole('heading', { name: '결제 승인 오류' })
    expect(screen.getByRole('textbox', { name: '내부 메모' })).toHaveValue(
      '1042 내부 초안',
    )
    await user.click(screen.getByRole('tab', { name: '공개 답변' }))
    expect(screen.getByRole('textbox', { name: '공개 답변' })).toHaveValue(
      '1042 공개 초안',
    )
  })
})
