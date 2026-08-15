import { afterEach, describe, expect, it, vi } from 'vitest'
import { addAnonymousRequestComment } from './client'

afterEach(() => vi.unstubAllGlobals())

describe('anonymous customer request API adapter', () => {
  it('sends a stable command ID and ticket proof only in the no-referrer request header', async () => {
    const accessToken = 'a'.repeat(43)
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: '11111111-1111-4111-8111-111111111111',
          authorDisplayName: '고객',
          body: '추가 정보입니다.',
          createdAt: '2026-08-15T00:00:00Z',
        }),
        { status: 201 },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    await addAnonymousRequestComment(
      1042,
      accessToken,
      '추가 정보입니다.',
      'follow-up-1042-1',
    )

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/requests/1042/comments',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
        headers: {
          'Content-Type': 'application/json',
          'X-Request-Access-Token': accessToken,
        },
        body: JSON.stringify({
          body: '추가 정보입니다.',
          clientCommandId: 'follow-up-1042-1',
        }),
      }),
    )
    expect(String(fetchMock.mock.calls[0]![0])).not.toContain(accessToken)
  })
})
