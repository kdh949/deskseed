import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCustomerSession } from './CustomerSessionContext'
import { CustomerMagicLinkConsumePage } from './CustomerMagicLinkConsumePage'

vi.mock('./CustomerSessionContext', () => ({
  useCustomerSession: vi.fn(),
}))

function session() {
  return {
    acceptAuthenticatedCustomer: vi.fn(),
    customer: null,
    retry: vi.fn(),
    signOut: vi.fn(),
    signingOut: false,
    status: 'anonymous' as const,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/customer/sign-in/consume']}>
      <Routes>
        <Route
          path="/customer/sign-in/consume"
          element={<CustomerMagicLinkConsumePage />}
        />
        <Route path="/account/requests" element={<p>account-open</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(() => {
  sessionStorage.clear()
  window.history.replaceState(null, '', '/')
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('CustomerMagicLinkConsumePage', () => {
  it('removes the magic-link fragment before its only network request, keeps no token storage, and establishes the returned customer session', async () => {
    const customerSession = session()
    vi.mocked(useCustomerSession).mockReturnValue(customerSession)
    const token = 'opaque-magic-link-token'
    window.history.replaceState(
      null,
      '',
      `/customer/sign-in/consume#token=${token}`,
    )
    const fetchMock = vi.fn(() => {
      expect(window.location.hash).toBe('')
      return Promise.resolve(
        new Response(
          JSON.stringify({
            id: 'customer-1',
            email: 'mina@example.test',
            displayName: '김민아',
            companyName: '가온상사',
            verifiedAt: '2026-08-15T00:00:00Z',
            credentialState: 'PASSWORDLESS',
            registrationState: 'REGISTRATION_REQUIRED',
            availableAuthenticationMethods: ['MAGIC_LINK'],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    expect(await screen.findByText('account-open')).toBeVisible()
    expect(window.location.hash).toBe('')
    expect(sessionStorage.length).toBe(0)
    expect(customerSession.acceptAuthenticatedCustomer).toHaveBeenCalledWith({
      id: 'customer-1',
      email: 'mina@example.test',
      displayName: '김민아',
      companyName: '가온상사',
      verifiedAt: '2026-08-15T00:00:00Z',
      credentialState: 'PASSWORDLESS',
      registrationState: 'REGISTRATION_REQUIRED',
      availableAuthenticationMethods: ['MAGIC_LINK'],
    })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/customer/auth/magic-link-sessions',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
        body: JSON.stringify({ token }),
      }),
    )
  })

  it('does not issue a network request when the fragment is missing', async () => {
    const customerSession = session()
    vi.mocked(useCustomerSession).mockReturnValue(customerSession)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    expect(
      await screen.findByRole('heading', {
        name: '로그인 링크를 찾을 수 없습니다.',
      }),
    ).toBeVisible()
    expect(
      screen.getByText(
        '이 링크로 로그인할 수 없습니다. 이메일 주소를 입력해 새 링크를 받아 주세요.',
      ),
    ).toBeVisible()
    expect(screen.queryByText(/fragment/i)).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
