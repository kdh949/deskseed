import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AgentTicketDetail } from '../../api/types'
import { STAFF_DRAFT_SESSION_OWNER_KEY } from './model/ticketEditorModel'
import { AgentTicketEditorWorkspace } from './AgentTicketEditorWorkspace'

const staffId = '11111111-1111-4111-8111-111111111111'

function createDetail(
  overrides: Partial<AgentTicketDetail> = {},
): AgentTicketDetail {
  return {
    ticket: {
      ticketNumber: 1042,
      subject: '결제 승인 오류',
      status: 'OPEN',
      priority: 'URGENT',
      requester: {
        id: 'customer-1',
        type: 'CUSTOMER',
        displayName: '김민수',
      },
      group: { id: 'group-payments', name: '결제 지원' },
      assignee: { id: 'staff-2', displayName: '이민아' },
      updatedAt: '2026-08-15T10:02:00Z',
      version: 3,
      isChild: false,
      openChildCount: 0,
      sla: null,
    },
    comments: [
      {
        id: 'comment-public',
        visibility: 'PUBLIC',
        actor: {
          id: 'customer-1',
          type: 'CUSTOMER',
          displayName: '김민수',
        },
        body: '결제가 계속 실패합니다.',
        createdAt: '2026-08-15T09:00:00Z',
        source: 'WEB',
        attachments: [],
      },
    ],
    capabilities: ['READ', 'UPDATE'],
    assignmentOptions: {
      groups: [
        {
          id: 'group-payments',
          name: '결제 지원',
          members: [{ id: 'staff-2', displayName: '이민아' }],
        },
        {
          id: 'group-shipping',
          name: '배송 지원',
          members: [{ id: 'staff-3', displayName: '박도윤' }],
        },
      ],
    },
    context: {
      customer: {
        id: 'customer-1',
        displayName: '김민수',
        email: 'minsu@example.test',
      },
      parent: null,
      children: [],
      externalReferenceCount: 0,
    },
    history: [],
    warnings: [],
    ...overrides,
  }
}

function renderWorkspace({
  detail = createDetail(),
  refreshLatest = vi.fn().mockResolvedValue(createDetail()),
}: {
  detail?: AgentTicketDetail
  refreshLatest?: () => Promise<AgentTicketDetail>
} = {}) {
  localStorage.setItem(STAFF_DRAFT_SESSION_OWNER_KEY, staffId)
  const router = createMemoryRouter(
    [
      {
        path: '/',
        element: (
          <AgentTicketEditorWorkspace
            detail={detail}
            refreshLatest={refreshLatest}
            staffId={staffId}
          />
        ),
      },
    ],
    { initialEntries: ['/'] },
  )
  return {
    refreshLatest,
    ...render(<RouterProvider router={router} />),
  }
}

function installMutationFetch(
  commandResponses: Array<Response | Error | Promise<Response>>,
) {
  const commands: Array<Record<string, unknown>> = []
  let uploadCount = 0
  const fetchMock = vi.fn(
    async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/v1/agent/csrf')) {
        return new Response(
          JSON.stringify({ token: 'a'.repeat(32), headerName: 'X-CSRF-TOKEN' }),
          { status: 200 },
        )
      }
      if (url.endsWith('/api/v1/agent/tickets/1042/commands')) {
        commands.push(JSON.parse(String(init?.body)) as Record<string, unknown>)
        const next = commandResponses.shift()
        if (next instanceof Error) throw next
        if (!next) throw new Error('Unexpected ticket command')
        return next
      }
      if (url.endsWith('/api/v1/agent/attachments/uploads')) {
        uploadCount += 1
        return new Response(
          JSON.stringify({
            id:
              uploadCount === 1
                ? '33333333-3333-4333-8333-333333333333'
                : '44444444-4444-4444-8444-444444444444',
            fileName: uploadCount === 1 ? 'a.png' : 'b.png',
            sizeBytes: 1,
            contentType: 'image/png',
            scanStatus: 'CLEAN',
            expiresAt: '2099-08-17T05:00:00Z',
          }),
          { status: 200 },
        )
      }
      throw new Error(`Unexpected request: ${url}`)
    },
  )
  vi.stubGlobal('fetch', fetchMock)
  return { commands, fetchMock }
}

function deferredResponse() {
  let resolve: ((response: Response) => void) | undefined
  let reject: ((reason?: unknown) => void) | undefined
  const promise = new Promise<Response>((nextResolve, nextReject) => {
    resolve = nextResolve
    reject = nextReject
  })
  return {
    promise,
    resolve: (response: Response) => resolve?.(response),
    reject: (reason?: unknown) => reject?.(reason),
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('AgentTicketEditorWorkspace', () => {
  it('sends a PUBLIC reply and changed field through one expected-version command, then refreshes', async () => {
    const user = userEvent.setup()
    const { commands } = installMutationFetch([
      new Response(
        JSON.stringify({
          ticketNumber: 1042,
          version: 4,
          auditId: '22222222-2222-4222-8222-222222222222',
          warnings: [],
        }),
        { status: 200 },
      ),
    ])
    const refreshed = createDetail({
      ticket: { ...createDetail().ticket, priority: 'HIGH', version: 4 },
    })
    const refreshLatest = vi.fn().mockResolvedValue(refreshed)
    renderWorkspace({ refreshLatest })

    await user.selectOptions(
      screen.getByRole('combobox', { name: '우선순위' }),
      'HIGH',
    )
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변 내용' }),
      '결제 시도 시간을 확인해 보겠습니다.',
    )
    await user.click(screen.getByRole('button', { name: '공개 답변 저장' }))

    await waitFor(() => expect(commands).toHaveLength(1))
    expect(commands[0]).toEqual(
      expect.objectContaining({
        expectedVersion: 3,
        changedFields: ['priority'],
        priority: 'HIGH',
        comment: {
          visibility: 'PUBLIC',
          body: '결제 시도 시간을 확인해 보겠습니다.',
        },
      }),
    )
    expect(commands[0]?.clientCommandId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
    expect(refreshLatest).toHaveBeenCalledOnce()
    expect(
      await screen.findByText('공개 답변과 변경사항을 저장했습니다.'),
    ).toBeVisible()
  })

  it('preserves the same command identity after an ambiguous failure', async () => {
    const user = userEvent.setup()
    const { commands } = installMutationFetch([
      new Error('network interrupted'),
      new Response(
        JSON.stringify({
          ticketNumber: 1042,
          version: 4,
          auditId: '22222222-2222-4222-8222-222222222222',
          warnings: [],
        }),
        { status: 200 },
      ),
    ])
    renderWorkspace()

    await user.type(
      screen.getByRole('textbox', { name: '공개 답변 내용' }),
      '동일 명령으로 다시 저장합니다.',
    )
    await user.click(screen.getByRole('button', { name: '공개 답변 저장' }))
    expect(
      await screen.findByText(/저장 결과를 확인할 수 없습니다/),
    ).toBeVisible()

    await user.click(screen.getByRole('button', { name: '공개 답변 저장' }))
    await waitFor(() => expect(commands).toHaveLength(2))
    expect(commands[1]?.clientCommandId).toBe(commands[0]?.clientCommandId)
    expect(commands[1]?.comment).toEqual(commands[0]?.comment)
  })

  it('rotates the complete command when attachments change after an ambiguous failure', async () => {
    const user = userEvent.setup()
    const { commands } = installMutationFetch([
      new Error('network interrupted'),
      new Response(
        JSON.stringify({
          ticketNumber: 1042,
          version: 4,
          auditId: '22222222-2222-4222-8222-222222222222',
          warnings: [],
        }),
        { status: 200 },
      ),
    ])
    renderWorkspace()
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변 내용' }),
      '첨부를 확인합니다.',
    )
    await user.upload(
      screen.getByLabelText('PUBLIC 첨부 파일'),
      new File(['a'], 'a.png', { type: 'image/png' }),
    )
    await screen.findByText(/^CLEAN/)
    await user.click(screen.getByRole('button', { name: '공개 답변 저장' }))
    await screen.findByText(/저장 결과를 확인할 수 없습니다/)
    await user.click(screen.getByRole('button', { name: '초안에서 제거' }))
    await user.upload(
      screen.getByLabelText('PUBLIC 첨부 파일'),
      new File(['b'], 'b.png', { type: 'image/png' }),
    )
    await screen.findByText(/^CLEAN/)
    await user.click(screen.getByRole('button', { name: '공개 답변 저장' }))
    await waitFor(() => expect(commands).toHaveLength(2))

    expect(commands[1]?.clientCommandId).not.toBe(commands[0]?.clientCommandId)
    expect(
      (commands[0]?.comment as { attachmentIds: string[] }).attachmentIds,
    ).toEqual(['33333333-3333-4333-8333-333333333333'])
    expect(
      (commands[1]?.comment as { attachmentIds: string[] }).attachmentIds,
    ).toEqual(['44444444-4444-4444-8444-444444444444'])
  })

  it('restores the exact attachment command snapshot after reload', async () => {
    const user = userEvent.setup()
    const { commands } = installMutationFetch([
      new Error('network interrupted'),
      new Response(
        JSON.stringify({
          ticketNumber: 1042,
          version: 4,
          auditId: '22222222-2222-4222-8222-222222222222',
          warnings: [],
        }),
        { status: 200 },
      ),
    ])
    const first = renderWorkspace()
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변 내용' }),
      '새로고침 뒤 재시도합니다.',
    )
    await user.upload(
      screen.getByLabelText('PUBLIC 첨부 파일'),
      new File(['a'], 'a.png', { type: 'image/png' }),
    )
    await screen.findByText(/^CLEAN/)
    await user.click(screen.getByRole('button', { name: '공개 답변 저장' }))
    await screen.findByText(/저장 결과를 확인할 수 없습니다/)
    first.unmount()

    renderWorkspace()
    expect(await screen.findByText(/이전 저장 시도에서 복원됨/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: '공개 답변 저장' }))
    await waitFor(() => expect(commands).toHaveLength(2))
    expect(commands[1]).toEqual(commands[0])
  })

  it('persists clean attachments before submit and restores them after reload', async () => {
    const user = userEvent.setup()
    const { commands } = installMutationFetch([
      new Response(
        JSON.stringify({
          ticketNumber: 1042,
          version: 4,
          auditId: '22222222-2222-4222-8222-222222222222',
          warnings: [],
        }),
        { status: 200 },
      ),
    ])
    const first = renderWorkspace()
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변 내용' }),
      '첨부 초안을 복원합니다.',
    )
    await user.upload(
      screen.getByLabelText('PUBLIC 첨부 파일'),
      new File(['a'], 'a.png', { type: 'image/png' }),
    )
    await screen.findByText(/^CLEAN/)
    first.unmount()

    renderWorkspace()
    expect(await screen.findByText(/이전 저장 시도에서 복원됨/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: '공개 답변 저장' }))
    await waitFor(() => expect(commands).toHaveLength(1))
    expect(
      (commands[0]?.comment as { attachmentIds: string[] }).attachmentIds,
    ).toEqual(['33333333-3333-4333-8333-333333333333'])
  })

  it('locks reply and property inputs during a save, then accepts a later draft after the confirmed result', async () => {
    const user = userEvent.setup()
    const pending = deferredResponse()
    const { commands } = installMutationFetch([pending.promise])
    renderWorkspace()

    const reply = screen.getByRole('textbox', { name: '공개 답변 내용' })
    await user.type(reply, '저장 중인 답변입니다.')
    await user.selectOptions(
      screen.getByRole('combobox', { name: '우선순위' }),
      'HIGH',
    )
    await user.click(screen.getByRole('button', { name: '공개 답변 저장' }))

    await waitFor(() => expect(commands).toHaveLength(1))
    expect(reply).toBeDisabled()
    expect(screen.getByRole('combobox', { name: '상태' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: '우선순위' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: '그룹' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: '담당자' })).toBeDisabled()

    pending.resolve(
      new Response(
        JSON.stringify({
          ticketNumber: 1042,
          version: 4,
          auditId: '22222222-2222-4222-8222-222222222222',
          warnings: [],
        }),
        { status: 200 },
      ),
    )

    expect(
      await screen.findByText('공개 답변과 변경사항을 저장했습니다.'),
    ).toBeVisible()
    expect(reply).toBeEnabled()
    await user.type(reply, '저장 완료 뒤의 새 답변입니다.')
    expect(reply).toHaveValue('저장 완료 뒤의 새 답변입니다.')
  })

  it('keeps the submitted command ID when a pending save loses its response', async () => {
    const user = userEvent.setup()
    const pending = deferredResponse()
    const { commands } = installMutationFetch([
      pending.promise,
      new Response(
        JSON.stringify({
          ticketNumber: 1042,
          version: 4,
          auditId: '22222222-2222-4222-8222-222222222222',
          warnings: [],
        }),
        { status: 200 },
      ),
    ])
    renderWorkspace()

    const reply = screen.getByRole('textbox', { name: '공개 답변 내용' })
    await user.type(reply, '응답 유실에도 같은 명령을 사용합니다.')
    await user.click(screen.getByRole('button', { name: '공개 답변 저장' }))

    await waitFor(() => expect(commands).toHaveLength(1))
    expect(reply).toBeDisabled()
    pending.reject(new Error('response lost after commit'))

    expect(
      await screen.findByText(/저장 결과를 확인할 수 없습니다/),
    ).toBeVisible()
    expect(reply).toBeEnabled()
    expect(reply).toHaveValue('응답 유실에도 같은 명령을 사용합니다.')

    await user.click(screen.getByRole('button', { name: '공개 답변 저장' }))
    await waitFor(() => expect(commands).toHaveLength(2))
    expect(commands[1]?.clientCommandId).toBe(commands[0]?.clientCommandId)
    expect(commands[1]?.comment).toEqual(commands[0]?.comment)
  })

  it('retains its draft and exposes field-by-field recovery after a 409 conflict', async () => {
    const user = userEvent.setup()
    installMutationFetch([
      new Response(
        JSON.stringify({
          type: '/problems/ticket-field-conflict',
          title: 'Ticket fields changed concurrently',
          status: 409,
          detail: 'Some fields were changed by another actor.',
          requestId: 'request-conflict-1',
          currentVersion: 4,
          conflictingFields: ['status'],
        }),
        {
          status: 409,
          headers: { 'Content-Type': 'application/problem+json' },
        },
      ),
    ])
    const latest = createDetail({
      ticket: { ...createDetail().ticket, status: 'PENDING', version: 4 },
    })
    renderWorkspace({ refreshLatest: vi.fn().mockResolvedValue(latest) })

    await user.selectOptions(
      screen.getByRole('combobox', { name: '상태' }),
      'SOLVED',
    )
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변 내용' }),
      '초안을 보존해야 합니다.',
    )
    await user.click(screen.getByRole('button', { name: '공개 답변 저장' }))

    expect(
      await screen.findByRole('region', { name: '티켓 저장 충돌' }),
    ).toBeVisible()
    expect(screen.getByRole('textbox', { name: '공개 답변 내용' })).toHaveValue(
      '초안을 보존해야 합니다.',
    )
    await user.click(
      screen.getByRole('button', { name: '상태에서 내 초안 유지' }),
    )
    expect(
      screen.queryByRole('region', { name: '티켓 저장 충돌' }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: '상태' })).toHaveValue('SOLVED')
  })

  it('keeps a read-only ticket visible when an explicit refresh fails', async () => {
    const user = userEvent.setup()
    const refreshLatest = vi
      .fn<() => Promise<AgentTicketDetail>>()
      .mockRejectedValue(new Error('unavailable'))
    renderWorkspace({
      detail: createDetail({ capabilities: ['READ'] }),
      refreshLatest,
    })

    await user.click(screen.getByRole('button', { name: '최신 정보 새로고침' }))

    expect(
      await screen.findByText(
        '최신 티켓 정보를 확인하지 못했습니다. 다시 시도해 주세요.',
      ),
    ).toBeVisible()
    expect(refreshLatest).toHaveBeenCalledOnce()
    expect(screen.getByText('결제 승인 오류')).toBeVisible()
  })

  it('limits a child ticket composer to INTERNAL content and hides unsupported actions', () => {
    renderWorkspace({
      detail: createDetail({
        ticket: { ...createDetail().ticket, isChild: true },
      }),
    })

    expect(
      screen.getByRole('textbox', { name: '내부 메모 내용' }),
    ).toBeVisible()
    expect(
      screen.queryByRole('textbox', { name: '공개 답변 내용' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('tab', { name: '공개 답변 작성 모드로 전환' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /이관|자식/ })).toBeNull()
  })

  it('renders an actual read-only projection without mutation or fixture controls', () => {
    renderWorkspace({
      detail: createDetail({ capabilities: ['READ'] }),
    })

    expect(
      screen.getByText('현재 권한으로는 티켓을 수정할 수 없습니다.'),
    ).toBeVisible()
    expect(screen.queryByText('ON_HOLD')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('textbox', { name: '공개 답변 내용' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /저장/ }),
    ).not.toBeInTheDocument()
    expect(screen.queryByText('김지연')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /이관|자식/ })).toBeNull()
  })
})
