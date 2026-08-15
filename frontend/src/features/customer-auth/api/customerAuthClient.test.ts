import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  consumeCustomerMagicLink,
  deleteCustomerSession,
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

  it('uses a session-bound CSRF token for no-referrer logout', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: 'a'.repeat(32), headerName: 'X-CSRF-TOKEN' }),
          { status: 200 },
        ),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await deleteCustomerSession()

    expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/customer/csrf')
    expect(fetchMock.mock.calls[0]![1]).toMatchObject({
      credentials: 'include',
      referrerPolicy: 'no-referrer',
    })
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/v1/customer/session')
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({
      method: 'DELETE',
      credentials: 'include',
      referrerPolicy: 'no-referrer',
      headers: { 'X-CSRF-TOKEN': 'a'.repeat(32) },
    })
  })
})
