import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  addCustomerFollowUp,
  downloadAuthenticatedCustomerAttachment,
  getCustomerRequest,
  listCustomerRequests,
  uploadAuthenticatedCustomerAttachment,
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
            content: { format: 'PLAIN_TEXT', text: '추가 답변' },
            createdAt: '2026-08-13T00:00:00Z',
            attachments: [],
          }),
          { status: 201 },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    await addCustomerFollowUp(
      1042,
      '추가 답변',
      '11111111-1111-4111-8111-111111111111',
      ['55555555-5555-4555-8555-555555555555'],
    )

    expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/customer/csrf')
    expect(fetchMock.mock.calls[0]![1]).toMatchObject({
      credentials: 'include',
      referrerPolicy: 'no-referrer',
    })
    expect(fetchMock.mock.calls[1]![0]).toBe(
      '/api/v1/customer/requests/1042/comments',
    )
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({
      method: 'POST',
      credentials: 'include',
      referrerPolicy: 'no-referrer',
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-TOKEN': 'csrf-token',
      },
      body: JSON.stringify({
        body: '추가 답변',
        attachmentIds: ['55555555-5555-4555-8555-555555555555'],
        clientCommandId: '11111111-1111-4111-8111-111111111111',
      }),
    })
  })

  it('strictly decodes PUBLIC attachment metadata without retaining storage or visibility fields', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            ticketNumber: 1042,
            subject: '결제 문의',
            status: 'OPEN',
            createdAt: '2026-08-12T00:00:00Z',
            updatedAt: '2026-08-13T00:00:00Z',
            comments: [
              {
                id: 'comment-1',
                authorDisplayName: 'Customer',
                body: '승인 내역입니다.',
                content: { format: 'PLAIN_TEXT', text: '승인 내역입니다.' },
                createdAt: '2026-08-13T00:00:00Z',
                visibility: 'PUBLIC',
                attachments: [
                  {
                    id: '55555555-5555-4555-8555-555555555555',
                    fileName: 'approval.pdf',
                    sizeBytes: 4096,
                    contentType: 'application/pdf',
                    objectKey: 'must-not-survive',
                    checksum: 'must-not-survive',
                    visibility: 'INTERNAL',
                  },
                ],
              },
            ],
          }),
          { status: 200 },
        ),
      ),
    )

    const request = await getCustomerRequest(1042)

    expect(request.comments[0]?.attachments).toEqual([
      {
        id: '55555555-5555-4555-8555-555555555555',
        fileName: 'approval.pdf',
        sizeBytes: 4096,
        contentType: 'application/pdf',
      },
    ])
    expect(request.comments[0]?.attachments[0]).not.toHaveProperty('objectKey')
    expect(request.comments[0]?.attachments[0]).not.toHaveProperty('checksum')
    expect(request.comments[0]?.attachments[0]).not.toHaveProperty('visibility')
  })

  it('gets customer CSRF before multipart upload and lets FormData own the content type boundary', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ token: 'csrf-token' }), { status: 200 }),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            id: '55555555-5555-4555-8555-555555555555',
            fileName: 'approval.pdf',
            sizeBytes: 4,
            contentType: 'application/pdf',
            scanStatus: 'CLEAN',
            expiresAt: '2099-08-17T05:00:00Z',
          }),
          { status: 201 },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    const result = await uploadAuthenticatedCustomerAttachment(
      1042,
      new File(['safe'], 'approval.pdf', { type: 'application/pdf' }),
    )

    expect(result.scanStatus).toBe('CLEAN')
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      '/api/v1/customer/requests/1042/attachments/uploads',
    )
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: { 'X-CSRF-TOKEN': 'csrf-token' },
      body: expect.any(FormData),
    })
    expect(fetchMock.mock.calls[1]?.[1]?.headers).not.toHaveProperty(
      'Content-Type',
    )
  })

  it('validates authenticated download headers before returning the private Blob', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('safe-bytes', {
          status: 200,
          headers: {
            'Content-Type': 'application/octet-stream',
            'Content-Disposition':
              "attachment; filename*=UTF-8''approval-history.pdf",
          },
        }),
      ),
    )

    const result = await downloadAuthenticatedCustomerAttachment(
      1042,
      '55555555-5555-4555-8555-555555555555',
    )

    expect(await result.content.text()).toBe('safe-bytes')
    expect(result.contentType).toBe('application/octet-stream')
    expect(result.fileName).toBe('approval-history.pdf')
  })
})
