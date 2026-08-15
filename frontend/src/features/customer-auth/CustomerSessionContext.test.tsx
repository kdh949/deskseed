import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  CustomerSessionProvider,
  useCustomerSession,
} from './CustomerSessionContext'

function SessionProbe() {
  const session = useCustomerSession()
  return (
    <section aria-label="고객 세션 검사">
      <p>{session.status}</p>
      <p>{session.customer?.displayName ?? 'anonymous'}</p>
      <button onClick={() => void session.signOut()}>로그아웃</button>
    </section>
  )
}

function MagicLinkSessionProbe() {
  const session = useCustomerSession()
  return (
    <section aria-label="매직 링크 고객 세션 검사">
      <p>{session.status}</p>
      <p>{session.customer?.displayName ?? 'anonymous'}</p>
      <button
        onClick={() =>
          session.acceptAuthenticatedCustomer({
            id: '11111111-1111-4111-8111-111111111111',
            email: 'customer@example.test',
            displayName: '매직 링크 고객',
            verifiedAt: '2026-08-15T00:00:00Z',
          })
        }
      >
        매직 링크 세션 반영
      </button>
    </section>
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('CustomerSessionProvider', () => {
  it('accepts a decoded magic-link session result without persisting a client token', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status: 401 })),
    )
    const user = userEvent.setup()

    render(
      <CustomerSessionProvider>
        <MagicLinkSessionProbe />
      </CustomerSessionProvider>,
    )

    await screen.findByText('anonymous')
    await user.click(
      screen.getByRole('button', { name: '매직 링크 세션 반영' }),
    )

    expect(screen.getByText('매직 링크 고객')).toBeVisible()
    expect(screen.getByText('authenticated')).toBeVisible()
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('confirms the server session and clears local state only after CSRF-protected logout', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/v1/customer/me')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              id: '11111111-1111-4111-8111-111111111111',
              email: 'customer@example.test',
              displayName: '고객',
              verifiedAt: '2026-08-15T00:00:00Z',
            }),
            { status: 200 },
          ),
        )
      }
      if (url.endsWith('/api/v1/customer/csrf')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              token: 'a'.repeat(32),
              headerName: 'X-CSRF-TOKEN',
            }),
            { status: 200 },
          ),
        )
      }
      if (
        url.endsWith('/api/v1/customer/session') &&
        init?.method === 'DELETE'
      ) {
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(
      <CustomerSessionProvider>
        <SessionProbe />
      </CustomerSessionProvider>,
    )

    expect(await screen.findByText('고객')).toBeVisible()
    expect(screen.getByText('authenticated')).toBeVisible()

    await user.click(screen.getByRole('button', { name: '로그아웃' }))

    await waitFor(() => {
      expect(screen.getAllByText('anonymous')).toHaveLength(2)
    })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/customer/session',
      expect.objectContaining({
        method: 'DELETE',
        headers: { 'X-CSRF-TOKEN': 'a'.repeat(32) },
      }),
    )
  })
})
