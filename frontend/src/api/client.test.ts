import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, getPublicRequest, submitRequest } from './client'

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
