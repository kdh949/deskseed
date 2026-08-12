import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  addCustomerFollowUp,
  listCustomerRequests,
} from './customerPortalClient'

afterEach(() => vi.unstubAllGlobals())

describe('customer portal API adapter', () => {
  it('decodes only the public list projection and omits unknown staff fields', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            items: [
              {
                ticketNumber: 1042,
                subject: '결제 문의',
                status: 'OPEN',
                createdAt: '2026-08-12T00:00:00Z',
                updatedAt: '2026-08-13T00:00:00Z',
                internalComment: 'must-not-survive',
                auditMetadata: { actorId: 'staff-1' },
              },
            ],
            nextCursor: null,
          }),
          { status: 200 },
        ),
      ),
    )

    const page = await listCustomerRequests('OPEN')

    expect(page.items).toEqual([
      {
        ticketNumber: 1042,
        subject: '결제 문의',
        status: 'OPEN',
        createdAt: '2026-08-12T00:00:00Z',
        updatedAt: '2026-08-13T00:00:00Z',
      },
    ])
    expect(page.items[0]).not.toHaveProperty('internalComment')
    expect(page.items[0]).not.toHaveProperty('auditMetadata')
  })

  it('preserves the logical command ID and customer CSRF boundary for follow-up writes', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ token: 'csrf-token' }), { status: 200 }),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            id: 'comment-1',
            authorDisplayName: 'Customer',
            body: '추가 답변',
            createdAt: '2026-08-13T00:00:00Z',
          }),
          { status: 201 },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    await addCustomerFollowUp(
      1042,
      '추가 답변',
      '11111111-1111-4111-8111-111111111111',
    )

    expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/customer/csrf')
    expect(fetchMock.mock.calls[1]![0]).toBe(
      '/api/v1/customer/requests/1042/comments',
    )
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-TOKEN': 'csrf-token',
      },
      body: JSON.stringify({
        body: '추가 답변',
        clientCommandId: '11111111-1111-4111-8111-111111111111',
      }),
    })
  })
})
