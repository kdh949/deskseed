import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  getAgentTicket,
  getPublicRequest,
  listAgentViews,
  listTicketsInView,
  submitRequest,
  updateAgentTicket,
} from './client'

const submitInput = {
  name: '김고객',
  email: 'customer@example.com',
  subject: '결제 오류',
  message: '결제 버튼을 누르면 오류가 납니다.',
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('customer request API client', () => {
  it('preserves RFC 9457 field errors and the safe response request ID', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: '/problems/validation',
            title: 'Request validation failed',
            status: 400,
            detail: 'One or more request fields are invalid.',
            fieldErrors: [
              {
                field: 'email',
                message: '올바른 이메일을 입력하세요.',
                code: 'Email',
              },
            ],
          }),
          {
            status: 400,
            headers: {
              'Content-Type': 'application/problem+json',
              'X-Request-Id': 'req-from-header',
            },
          },
        ),
      ),
    )

    const error = await submitRequest(submitInput).catch(
      (cause: unknown) => cause,
    )

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 400,
      requestId: 'req-from-header',
      fieldErrors: {
        email: '올바른 이메일을 입력하세요.',
      },
    })
  })

  it('keeps retry guidance from a rate-limit response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: '/problems/rate-limit',
            title: 'Too many requests',
            status: 429,
          }),
          {
            status: 429,
            headers: {
              'Content-Type': 'application/problem+json',
              'Retry-After': '30',
            },
          },
        ),
      ),
    )

    const error = await submitRequest(submitInput).catch(
      (cause: unknown) => cause,
    )

    expect(error).toMatchObject({ status: 429, retryAfter: '30' })
  })

  it('sends the opaque grant only in the request header', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          ticketNumber: 1042,
          subject: '결제 오류',
          status: 'NEW',
          createdAt: '2026-08-10T00:00:00Z',
          updatedAt: '2026-08-10T00:00:00Z',
          comments: [],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await getPublicRequest(1042, 'opaque-secret-token')

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/requests/1042', {
      headers: { 'X-Request-Access-Token': 'opaque-secret-token' },
      cache: 'no-store',
    })
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain(
      'requests/1042?access=',
    )
  })

  it('rejects malformed creation and detail success bodies as controlled API errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ ticketNumber: 1042 }), {
            status: 201,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ ticketNumber: 1042, comments: [] }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        ),
    )

    const createError = await submitRequest(submitInput).catch(
      (cause: unknown) => cause,
    )
    const detailError = await getPublicRequest(
      1042,
      'opaque-secret-token',
    ).catch((cause: unknown) => cause)

    for (const error of [createError, detailError]) {
      expect(error).toBeInstanceOf(ApiError)
      expect(error).toMatchObject({
        message: '서버 응답을 안전하게 처리할 수 없습니다.',
      })
    }
  })

  it('handles malformed problem field errors without masking the HTTP failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ fieldErrors: {} }), {
          status: 400,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      ),
    )

    const error = await submitRequest(submitInput).catch(
      (cause: unknown) => cause,
    )

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 400, fieldErrors: {} })
  })

  it('turns syntactically malformed success and problem JSON into controlled API errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(
          new Response('{', {
            status: 201,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
        .mockResolvedValueOnce(
          new Response('{', {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
        .mockResolvedValueOnce(
          new Response('{', {
            status: 400,
            headers: { 'Content-Type': 'application/problem+json' },
          }),
        ),
    )

    const errors = await Promise.all([
      submitRequest(submitInput).catch((cause: unknown) => cause),
      getPublicRequest(1042, 'opaque-secret-token').catch(
        (cause: unknown) => cause,
      ),
      submitRequest(submitInput).catch((cause: unknown) => cause),
    ])

    expect(errors).toHaveLength(3)
    for (const error of errors) expect(error).toBeInstanceOf(ApiError)
    expect(errors[0]).toMatchObject({ status: 201 })
    expect(errors[1]).toMatchObject({ status: 200 })
    expect(errors[2]).toMatchObject({ status: 400, fieldErrors: {} })
  })

  it('allowlists the public DTO instead of retaining unknown response fields', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            ticketNumber: 1042,
            subject: '결제 오류',
            status: 'NEW',
            createdAt: '2026-08-10T00:00:00Z',
            updatedAt: '2026-08-10T00:00:00Z',
            comments: [
              {
                id: 'comment-1',
                authorDisplayName: '김고객',
                body: '공개 문의',
                createdAt: '2026-08-10T00:00:00Z',
                staffMetadata: 'comment-private-marker',
              },
            ],
            internalComments: 'internal-private-marker',
            group: 'group-private-marker',
            assignee: 'assignee-private-marker',
            audit: 'audit-private-marker',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const request = await getPublicRequest(1042, 'opaque-secret-token')
    const serialized = JSON.stringify(request)
    const [firstComment] = request.comments

    expect(Object.keys(request).sort()).toEqual([
      'comments',
      'createdAt',
      'status',
      'subject',
      'ticketNumber',
      'updatedAt',
    ])
    expect(firstComment).toBeDefined()
    expect(Object.keys(firstComment!).sort()).toEqual([
      'authorDisplayName',
      'body',
      'createdAt',
      'id',
    ])
    expect(serialized).not.toContain('private-marker')
  })
})

describe('agent ticket read API client', () => {
  it('decodes default views and sends stable filters through the queue URL', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify([
            {
              key: 'my-open',
              name: '내 open',
              scope: 'SYSTEM',
              categoryPath: ['내 작업'],
              ticketCount: null,
              readScope: 'ALL_TICKETS',
            },
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            items: [],
            nextCursor: null,
            totalApproximate: null,
            sort: 'updatedAt:desc,ticketNumber:desc',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    await expect(listAgentViews()).resolves.toMatchObject([
      { key: 'my-open', readScope: 'ALL_TICKETS' },
    ])
    await listTicketsInView('pending', {
      status: 'PENDING',
      priority: 'URGENT',
      groupId: 'group-id',
      assigneeId: 'me',
      cursor: 'opaque-cursor',
      limit: 25,
    })

    const queueUrl = String(fetchMock.mock.calls[1]?.[0])
    expect(queueUrl).toContain('/api/v1/agent/views/pending/tickets?')
    expect(queueUrl).toContain('status=PENDING')
    expect(queueUrl).toContain('priority=URGENT')
    expect(queueUrl).toContain('groupId=group-id')
    expect(queueUrl).toContain('assigneeId=me')
    expect(queueUrl).toContain('cursor=opaque-cursor')
    expect(queueUrl).toContain('limit=25')
  })

  it('sends navigation metadata and decodes an on-hold staff detail projection', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          ticket: {
            ticketNumber: 1042,
            subject: '결제 오류',
            status: 'ON_HOLD',
            priority: 'URGENT',
            requester: {
              id: 'customer-id',
              type: 'CUSTOMER',
              displayName: '김고객',
            },
            group: { id: 'group-id', name: '결제 지원' },
            assignee: { id: 'staff-id', displayName: '상담사' },
            updatedAt: '2026-08-10T00:00:00Z',
            version: 3,
            isChild: false,
            openChildCount: 0,
            sla: null,
          },
          comments: [
            {
              id: 'comment-1',
              visibility: 'INTERNAL',
              actor: {
                id: 'staff-id',
                type: 'STAFF',
                displayName: '상담사',
              },
              body: '내부 확인 필요',
              createdAt: '2026-08-10T00:00:00Z',
              source: 'AGENT_UI',
              attachments: [],
            },
          ],
          capabilities: ['READ', 'UPDATE'],
          assignmentOptions: {
            groups: [
              {
                id: 'group-id',
                name: '결제 지원',
                members: [{ id: 'staff-id', displayName: '상담사' }],
              },
            ],
          },
          context: {
            customer: {
              id: 'customer-id',
              displayName: '김고객',
              email: 'customer@example.com',
            },
            parent: null,
            children: [],
            externalReferences: [],
          },
          history: [
            {
              id: 'history-id',
              eventType: 'TICKET_CREATED',
              actor: {
                id: 'staff-id',
                type: 'STAFF',
                displayName: '상담사',
              },
              occurredAt: '2026-08-10T00:00:00Z',
            },
          ],
          warnings: [],
          serverOnly: 'private-marker',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const detail = await getAgentTicket(
      1042,
      '11111111-1111-4111-8111-111111111111',
      'NAVIGATION',
    )

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/agent/tickets/1042', {
      credentials: 'include',
      cache: 'no-store',
      headers: {
        'X-Interaction-Id': '11111111-1111-4111-8111-111111111111',
        'X-Deskseed-Read-Intent': 'NAVIGATION',
      },
    })
    expect(detail.comments[0]?.visibility).toBe('INTERNAL')
    expect(detail.context.customer.email).toBe('customer@example.com')
    expect(detail.ticket.status).toBe('ON_HOLD')
    expect(detail.assignmentOptions.groups[0]?.members[0]?.displayName).toBe(
      '상담사',
    )
    expect(JSON.stringify(detail)).not.toContain('private-marker')
  })

  it('sends one exact combined ticket command with CSRF protection', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            ticketNumber: 1042,
            version: 8,
            auditId: '11111111-1111-4111-8111-111111111111',
            warnings: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      updateAgentTicket(1042, {
        expectedVersion: 7,
        changedFields: ['status', 'priority', 'groupId', 'assigneeId'],
        status: 'PENDING',
        priority: 'HIGH',
        groupId: '22222222-2222-4222-8222-222222222222',
        assigneeId: null,
        comment: { visibility: 'INTERNAL', body: '결제팀 확인 요청' },
        clientCommandId: '33333333-3333-4333-8333-333333333333',
      }),
    ).resolves.toMatchObject({ version: 8 })

    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      '/api/v1/agent/tickets/1042/commands',
    )
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-TOKEN': 'csrf-token',
      },
    })
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toEqual({
      expectedVersion: 7,
      changedFields: ['status', 'priority', 'groupId', 'assigneeId'],
      status: 'PENDING',
      priority: 'HIGH',
      groupId: '22222222-2222-4222-8222-222222222222',
      assigneeId: null,
      comment: { visibility: 'INTERNAL', body: '결제팀 확인 요청' },
      clientCommandId: '33333333-3333-4333-8333-333333333333',
    })
  })

  it('preserves field-conflict metadata and its safe request id', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(
          new Response(
            JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }),
            { status: 200 },
          ),
        )
        .mockResolvedValueOnce(
          new Response(
            JSON.stringify({
              type: '/problems/ticket-field-conflict',
              status: 409,
              requestId: 'request-conflict-409',
              currentVersion: 9,
              conflictingFields: ['priority'],
            }),
            { status: 409 },
          ),
        ),
    )

    const error = await updateAgentTicket(1042, {
      expectedVersion: 7,
      changedFields: ['priority'],
      priority: 'HIGH',
      comment: null,
      clientCommandId: '33333333-3333-4333-8333-333333333333',
    }).catch((cause: unknown) => cause)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 409,
      requestId: 'request-conflict-409',
      problem: { currentVersion: 9, conflictingFields: ['priority'] },
    })
  })

  it('decodes a closed ticket in an agent list response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            items: [
              {
                ticketNumber: 1042,
                subject: '종료된 티켓',
                status: 'CLOSED',
                priority: 'NORMAL',
                requester: {
                  id: 'customer-id',
                  type: 'CUSTOMER',
                  displayName: '김고객',
                },
                group: null,
                assignee: null,
                updatedAt: '2026-08-10T00:00:00Z',
                version: 3,
                isChild: false,
                openChildCount: 0,
                sla: null,
              },
            ],
            nextCursor: null,
            totalApproximate: null,
            sort: 'updatedAt:desc,ticketNumber:desc',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    await expect(listTicketsInView('recently-solved')).resolves.toMatchObject({
      items: [{ status: 'CLOSED' }],
    })
  })

  it('rejects malformed agent list and detail success bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(
          new Response(JSON.stringify([{ key: 'my-open' }]), { status: 200 }),
        )
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ ticket: { ticketNumber: 1042 } }), {
            status: 200,
          }),
        ),
    )

    await expect(listAgentViews()).rejects.toBeInstanceOf(ApiError)
    await expect(
      getAgentTicket(
        1042,
        '11111111-1111-4111-8111-111111111111',
        'NAVIGATION',
      ),
    ).rejects.toBeInstanceOf(ApiError)
  })
})
