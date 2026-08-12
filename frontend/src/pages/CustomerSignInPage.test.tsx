import { StrictMode } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  CustomerMagicLinkConsumePage,
  CustomerSignInPage,
  takeAndClearMagicLinkToken,
} from './CustomerSignInPage'

describe('customer magic-link pages', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    window.history.replaceState(null, '', '/')
  })

  afterEach(() => vi.unstubAllGlobals())

  it('shows the same accepted message after a request', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 202 }))
    render(
      <MemoryRouter>
        <CustomerSignInPage />
      </MemoryRouter>,
    )
    fireEvent.change(screen.getByLabelText('이메일'), {
      target: { value: 'customer@example.com' },
    })
    fireEvent.click(screen.getByRole('button', { name: '로그인 링크 받기' }))

    expect(
      await screen.findByText('입력한 이메일로 로그인 링크를 보냈습니다.'),
    ).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/customer/auth/magic-link-requests',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ email: 'customer@example.com' }),
        referrerPolicy: 'no-referrer',
      }),
    )
  })

  it('removes token and query from browser history before consume fetch', async () => {
    const rawToken = 'secret_token-value'
    window.history.replaceState(
      null,
      '',
      `/customer/sign-in/consume?unexpected=1#token=${rawToken}`,
    )
    vi.mocked(fetch).mockImplementation(async () => {
      expect(window.location.href).not.toContain(rawToken)
      expect(window.location.search).toBe('')
      expect(window.location.hash).toBe('')
      return new Response(
        JSON.stringify({
          id: 'customer-id',
          email: 'customer@example.com',
          displayName: '고객',
          verifiedAt: '2026-08-12T00:00:00Z',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )
    })

    const token = takeAndClearMagicLinkToken()
    render(
      <StrictMode>
        <MemoryRouter>
          <CustomerMagicLinkConsumePage token={token} />
        </MemoryRouter>
      </StrictMode>,
    )

    expect(await screen.findByText('로그인되었습니다.')).toBeInTheDocument()
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    expect(
      JSON.parse(String(vi.mocked(fetch).mock.calls[0]?.[1]?.body)),
    ).toEqual({
      token: rawToken,
    })
  })

  it('does not make a request when the fragment is missing', () => {
    window.history.replaceState(null, '', '/customer/sign-in/consume')
    render(
      <MemoryRouter>
        <CustomerMagicLinkConsumePage token={takeAndClearMagicLinkToken()} />
      </MemoryRouter>,
    )
    expect(
      screen.getByText('로그인 링크를 사용할 수 없습니다.'),
    ).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })
})
