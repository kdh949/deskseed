import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  consumeCustomerMagicLink,
  getCustomerAccessMode,
  requestCustomerMagicLink,
} from './customerAuthClient'

afterEach(() => vi.unstubAllGlobals())

describe('customer authentication API adapter', () => {
  it('keeps the email and magic-link token in POST bodies with no-referrer semantics', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 202 }))
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            id: 'customer-1',
            email: 'customer@example.com',
            displayName: 'Customer',
            verifiedAt: '2026-08-13T00:00:00Z',
          }),
          { status: 200 },
        ),
      )
    vi.stubGlobal('fetch', fetchMock)

    await requestCustomerMagicLink('customer@example.com')
    await consumeCustomerMagicLink('opaque-magic-link-token')

    expect(fetchMock.mock.calls[0]![0]).toBe(
      '/api/v1/customer/auth/magic-link-requests',
    )
    expect(fetchMock.mock.calls[0]![1]).toMatchObject({
      method: 'POST',
      credentials: 'include',
      referrerPolicy: 'no-referrer',
      body: JSON.stringify({ email: 'customer@example.com' }),
    })
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({
      method: 'POST',
      referrerPolicy: 'no-referrer',
      body: JSON.stringify({ token: 'opaque-magic-link-token' }),
    })
  })

  it('fails closed for an unknown access-mode projection', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ mode: 'INTERNAL_ONLY' }), {
          status: 200,
        }),
      ),
    )

    await expect(getCustomerAccessMode()).rejects.toThrow(
      'customer-access-mode-response-invalid',
    )
  })
})
