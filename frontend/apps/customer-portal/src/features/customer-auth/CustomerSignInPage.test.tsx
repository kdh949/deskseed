import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CustomerSignInPage } from './CustomerSignInPage'
import { CustomerCheckEmailPage } from './CustomerCheckEmailPage'

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/customer/sign-in']}>
      <Routes>
        <Route path="/customer/sign-in" element={<CustomerSignInPage />} />
        <Route
          path="/customer/sign-in/check-email"
          element={<CustomerCheckEmailPage />}
        />
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('CustomerSignInPage', () => {
  it('requests a customer magic link with no-referrer transport and gives the same accepted message without account enumeration', async () => {
    const user = userEvent.setup()
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ accepted: true }), { status: 202 }),
      )
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    await user.type(screen.getByLabelText(/이메일 주소/), 'mina@example.test')
    await user.click(screen.getByRole('button', { name: '로그인 링크 보내기' }))

    expect(await screen.findByText('받은 편지함을 확인해 주세요')).toBeVisible()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/customer/auth/magic-link-requests',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
        body: JSON.stringify({ email: 'mina@example.test' }),
      }),
    )
  })

  it('preserves the email input and gives a recoverable unavailable state when delivery cannot be requested', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status: 503 })),
    )

    renderPage()

    await user.type(screen.getByLabelText(/이메일 주소/), 'mina@example.test')
    await user.click(screen.getByRole('button', { name: '로그인 링크 보내기' }))

    expect(
      await screen.findByText('로그인 요청을 완료할 수 없습니다.'),
    ).toBeVisible()
    expect(screen.getByLabelText(/이메일 주소/)).toHaveValue(
      'mina@example.test',
    )
  })
})
