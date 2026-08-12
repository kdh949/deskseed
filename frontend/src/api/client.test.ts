import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  advanceStaffSessionGeneration,
  ApiError,
  createChildTicket,
  getAgentTicket,
  getCurrentStaff,
  getPublicRequest,
  isCurrentStaffSessionActorMismatch,
  loginStaff,
  listAgentViews,
  listStaff,
  listTicketsInView,
  searchAgentTickets,
  setConfirmedStaffActor,
  STAFF_SESSION_ACTOR_MISMATCH_EVENT,
  STAFF_SESSION_INVALID_EVENT,
  submitRequest,
  transferAgentTicket,
  updateAgentTicket,
} from './client'

const submitInput = {
  name: '김고객',
  email: 'customer@example.com',
  subject: '결제 오류',
  message: '결제 버튼을 누르면 오류가 납니다.',
}

afterEach(() => {
  setConfirmedStaffActor(null)
  localStorage.removeItem('deskseed:staff-session:last-authenticated-staff:v1')
  vi.unstubAllGlobals()
})

describe('admin list API client', () => {
  const staffRow = {
    id: '11111111-1111-4111-8111-111111111111',
    email: 'admin@example.com',
    displayName: '관리자',
    role: 'ADMIN',
    status: 'ACTIVE',
    memberships: [],
    auditAuthorities: [],
    lastLoginAt: null,
  }

  it('decodes bounded page metadata from response headers', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([staffRow]), {
        status: 200,
        headers: {
          'Content-Type': 'application/json',
          'X-Page-Number': '2',
          'X-Page-Size': '3',
          'X-Total-Count': '8',
          'X-Total-Pages': '3',
        },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(listStaff(2, 3)).resolves.toEqual({
      items: [staffRow],
      page: 2,
      size: 3,
      totalCount: 8,
      totalPages: 3,
    })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/admin/staff?page=2&size=3',
      expect.any(Object),
    )
  })

  it('fails closed when only part of the page metadata is present', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify([staffRow]), {
          status: 200,
          headers: {
            'Content-Type': 'application/json',
            'X-Page-Number': '0',
          },
        }),
      ),
    )

    await expect(listStaff()).rejects.toBeInstanceOf(ApiError)
  })
})

describe('customer request API client', () => {
  it('uses the customer CSRF contract for authenticated submission', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ token: 'customer-csrf-token' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            ticketNumber: 1042,
            status: 'NEW',
            accessToken: 'one-time-access-token-that-is-long-enough',
            createdAt: '2026-08-10T00:00:00Z',
          }),
          { status: 201, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    await submitRequest(submitInput, true)

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/customer/csrf', {
      credentials: 'include',
      cache: 'no-store',
    })
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/requests',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': 'customer-csrf-token',
        },
      }),
    )
  })

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
  it('sends the tab-local confirmed actor on ordinary staff reads and writes', async () => {
    const confirmedActor = '11111111-1111-4111-8111-111111111111'
    setConfirmedStaffActor(confirmedActor)
    localStorage.setItem(
      'deskseed:staff-session:last-authenticated-staff:v1',
      '22222222-2222-4222-8222-222222222222',
    )
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }),
          { status: 200 },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            ticketNumber: 1042,
            version: 8,
            auditId: '33333333-3333-4333-8333-333333333333',
            warnings: [],
          }),
          { status: 200 },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    await listAgentViews()
    await updateAgentTicket(1042, {
      expectedVersion: 7,
      changedFields: ['priority'],
      priority: 'HIGH',
      comment: null,
      clientCommandId: '44444444-4444-4444-8444-444444444444',
    })

    for (const call of fetchMock.mock.calls) {
      expect(
        new Headers((call[1] as RequestInit | undefined)?.headers).get(
          'X-Deskseed-Expected-Staff-Id',
        ),
      ).toBe(confirmedActor)
    }
  })

  it('omits the confirmed actor only for login mutation and post-login verification', async () => {
    setConfirmedStaffActor('11111111-1111-4111-8111-111111111111')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }),
          { status: 200 },
        ),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            id: '22222222-2222-4222-8222-222222222222',
            email: 'actor-b@example.com',
            displayName: 'Actor B',
            role: 'AGENT',
            capabilities: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    await loginStaff('actor-b@example.com', 'Correct horse 42')
    await getCurrentStaff({ omitExpectedStaffActor: true })

    for (const call of fetchMock.mock.calls) {
      expect(
        new Headers((call[1] as RequestInit | undefined)?.headers).has(
          'X-Deskseed-Expected-Staff-Id',
        ),
      ).toBe(false)
    }
  })

  it('emits a distinct actor-mismatch event only for the request generation that received the exact problem', async () => {
    setConfirmedStaffActor('11111111-1111-4111-8111-111111111111')
    const mismatches: Event[] = []
    const invalidations: Event[] = []
    const mismatchListener = (event: Event) => mismatches.push(event)
    const invalidationListener = (event: Event) => invalidations.push(event)
    window.addEventListener(
      STAFF_SESSION_ACTOR_MISMATCH_EVENT,
      mismatchListener,
    )
    window.addEventListener(STAFF_SESSION_INVALID_EVENT, invalidationListener)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: '/problems/staff-session-actor-mismatch',
            title: 'Staff session actor changed',
            status: 409,
            detail: 'Refresh the staff session before continuing.',
          }),
          {
            status: 409,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        ),
      ),
    )

    try {
      await expect(listAgentViews()).rejects.toBeInstanceOf(ApiError)
      expect(mismatches).toHaveLength(1)
      expect(invalidations).toEqual([])
      advanceStaffSessionGeneration()
      expect(isCurrentStaffSessionActorMismatch(mismatches[0]!)).toBe(false)
    } finally {
      window.removeEventListener(
        STAFF_SESSION_ACTOR_MISMATCH_EVENT,
        mismatchListener,
      )
      window.removeEventListener(
        STAFF_SESSION_INVALID_EVENT,
        invalidationListener,
      )
    }
  })

  it('does not invalidate the staff session for an ordinary conflict response', async () => {
    setConfirmedStaffActor('11111111-1111-4111-8111-111111111111')
    const mismatches: Event[] = []
    const invalidations: Event[] = []
    const mismatchListener = (event: Event) => mismatches.push(event)
    const invalidationListener = (event: Event) => invalidations.push(event)
    window.addEventListener(
      STAFF_SESSION_ACTOR_MISMATCH_EVENT,
      mismatchListener,
    )
    window.addEventListener(STAFF_SESSION_INVALID_EVENT, invalidationListener)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: '/problems/ticket-field-conflict',
            title: 'Ticket fields changed concurrently',
            status: 409,
            currentVersion: 9,
            conflictingFields: ['priority'],
          }),
          {
            status: 409,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        ),
      ),
    )

    try {
      await expect(listAgentViews()).rejects.toBeInstanceOf(ApiError)
      expect(mismatches).toEqual([])
      expect(invalidations).toEqual([])
    } finally {
      window.removeEventListener(
        STAFF_SESSION_ACTOR_MISMATCH_EVENT,
        mismatchListener,
      )
      window.removeEventListener(
        STAFF_SESSION_INVALID_EVENT,
        invalidationListener,
      )
    }
  })

  it('keeps the confirmed actor as the final reserved header when a CSRF header name collides', async () => {
    const confirmedActor = '11111111-1111-4111-8111-111111111111'
    setConfirmedStaffActor(confirmedActor)
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            token: 'must-not-override-the-actor',
            headerName: 'X-Deskseed-Expected-Staff-Id',
          }),
          { status: 200 },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            ticketNumber: 1042,
            version: 8,
            auditId: '33333333-3333-4333-8333-333333333333',
            warnings: [],
          }),
          { status: 200 },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    await updateAgentTicket(1042, {
      expectedVersion: 7,
      changedFields: ['priority'],
      priority: 'HIGH',
      comment: null,
      clientCommandId: '44444444-4444-4444-8444-444444444444',
    })

    expect(
      new Headers((fetchMock.mock.calls[1]?.[1] as RequestInit).headers).get(
        'X-Deskseed-Expected-Staff-Id',
      ),
    ).toBe(confirmedActor)
  })

  it('does not upgrade a held A mutation to B after the CSRF request starts', async () => {
    const actorA = '11111111-1111-4111-8111-111111111111'
    const actorB = '22222222-2222-4222-8222-222222222222'
    let resolveCsrf!: (response: Response) => void
    const heldCsrf = new Promise<Response>((resolve) => {
      resolveCsrf = resolve
    })
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => heldCsrf)
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            ticketNumber: 1042,
            version: 8,
            auditId: '33333333-3333-4333-8333-333333333333',
            warnings: [],
          }),
          { status: 200 },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)
    setConfirmedStaffActor(actorA)

    const pendingMutation = updateAgentTicket(1042, {
      expectedVersion: 7,
      changedFields: ['priority'],
      priority: 'HIGH',
      comment: null,
      clientCommandId: '44444444-4444-4444-8444-444444444444',
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(
      new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get(
        'X-Deskseed-Expected-Staff-Id',
      ),
    ).toBe(actorA)

    advanceStaffSessionGeneration()
    setConfirmedStaffActor(actorB)
    resolveCsrf(
      new Response(
        JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }),
        { status: 200 },
      ),
    )

    await expect(pendingMutation).rejects.toThrow(
      'Staff session changed before mutation',
    )
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('posts the raw search query outside the URL and decodes its canonical audit linkage', async () => {
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
            searchEventId: '11111111-1111-4111-8111-111111111111',
            searchInteractionId: '22222222-2222-4222-8222-222222222222',
            items: [],
            resultCount: 0,
            sort: 'updatedAt:desc,ticketNumber:desc',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      searchAgentTickets(
        {
          query: 'customer@example.com secret value',
          filters: { status: 'OPEN', assigneeId: 'me' },
          sort: 'updatedAt:desc,ticketNumber:desc',
          limit: 25,
        },
        '22222222-2222-4222-8222-222222222222',
      ),
    ).resolves.toMatchObject({
      searchEventId: '11111111-1111-4111-8111-111111111111',
      resultCount: 0,
    })

    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/agent/search')
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      method: 'POST',
      headers: {
        'X-CSRF-TOKEN': 'csrf-token',
        'X-Interaction-Id': '22222222-2222-4222-8222-222222222222',
        'Content-Type': 'application/json',
      },
    })
    expect(String(fetchMock.mock.calls[1]?.[0])).not.toContain('customer')
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toEqual({
      query: 'customer@example.com secret value',
      filters: { status: 'OPEN', assigneeId: 'me' },
      sort: 'updatedAt:desc,ticketNumber:desc',
      limit: 25,
    })
  })

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
      '22222222-2222-4222-8222-222222222222',
    )

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/agent/tickets/1042', {
      credentials: 'include',
      cache: 'no-store',
      headers: {
        'X-Interaction-Id': '11111111-1111-4111-8111-111111111111',
        'X-Deskseed-Read-Intent': 'NAVIGATION',
        'X-Origin-Search-Event-Id': '22222222-2222-4222-8222-222222222222',
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

  it('sends transfer and child as distinct ETag guarded commands', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            token: 'csrf-transfer',
            headerName: 'X-CSRF-TOKEN',
          }),
          { status: 200 },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            ticketNumber: 1042,
            version: 8,
            auditId: 'transfer-audit-id',
            warnings: [],
          }),
          { status: 200 },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: 'csrf-child', headerName: 'X-CSRF-TOKEN' }),
          { status: 200 },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            parentTicketNumber: 1042,
            parentVersion: 9,
            childTicketNumber: 1043,
            parentAuditId: 'parent-audit-id',
            childAuditId: 'child-audit-id',
          }),
          { status: 201 },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      transferAgentTicket(1042, {
        expectedVersion: 7,
        groupId: 'group-payments',
        assigneeId: 'agent-specialist',
        reason: '전문 그룹이 소유권을 인수합니다.',
        clientCommandId: '11111111-1111-4111-8111-111111111111',
      }),
    ).resolves.toMatchObject({ ticketNumber: 1042, version: 8 })
    await expect(
      createChildTicket(1042, {
        expectedVersion: 8,
        subject: '승인 로그 확인',
        body: '고객 비노출 내부 조사',
        groupId: 'group-payments',
        assigneeId: null,
        priority: 'HIGH',
        clientCommandId: '22222222-2222-4222-8222-222222222222',
      }),
    ).resolves.toEqual({
      parentTicketNumber: 1042,
      parentVersion: 9,
      childTicketNumber: 1043,
      parentAuditId: 'parent-audit-id',
      childAuditId: 'child-audit-id',
    })

    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      '/api/v1/agent/tickets/1042/transfer',
    )
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'If-Match': '"7"',
        'X-CSRF-TOKEN': 'csrf-transfer',
      },
    })
    expect(fetchMock.mock.calls[3]?.[0]).toBe(
      '/api/v1/agent/tickets/1042/children',
    )
    expect(fetchMock.mock.calls[3]?.[1]).toMatchObject({
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'If-Match': '"8"',
        'X-CSRF-TOKEN': 'csrf-child',
      },
    })
  })

  it('preserves the open child warning count and ticket numbers', async () => {
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
              ticketNumber: 1042,
              version: 8,
              auditId: 'solve-audit-id',
              warnings: [
                {
                  code: 'OPEN_CHILD_TICKETS',
                  message: '2개의 열린 child ticket이 있지만 저장되었습니다.',
                  count: 2,
                  relatedTicketNumbers: [1043, 1044],
                },
              ],
            }),
            { status: 200 },
          ),
        ),
    )

    await expect(
      updateAgentTicket(1042, {
        expectedVersion: 7,
        changedFields: ['status'],
        status: 'SOLVED',
        comment: null,
        clientCommandId: '33333333-3333-4333-8333-333333333333',
      }),
    ).resolves.toMatchObject({
      warnings: [
        {
          code: 'OPEN_CHILD_TICKETS',
          count: 2,
          relatedTicketNumbers: [1043, 1044],
        },
      ],
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
