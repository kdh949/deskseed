import { StrictMode } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import {
  CustomerPasswordResetPage,
  CustomerPasswordResetRequestPage,
} from './CustomerPasswordResetPages'
afterEach(() => {
  vi.unstubAllGlobals()
  window.history.replaceState(null, '', '/')
})
it('scrubs the proof before any request and only changes the password once on submit', async () => {
  const token = 'synthetic-reset-proof-0000000000000000'
  window.history.replaceState(
    null,
    '',
    '/customer/password/reset#token=' + token,
  )
  const fetcher = vi.fn(async (_url: string, init?: RequestInit) => {
    expect(window.location.hash).toBe('')
    expect(JSON.parse(init!.body as string)).toEqual({
      token,
      newPassword: 'synthetic-password-123',
    })
    expect(init).toMatchObject({
      method: 'POST',
      credentials: 'include',
      referrerPolicy: 'no-referrer',
    })
    return new Response(null, { status: 204 })
  })
  vi.stubGlobal('fetch', fetcher)
  render(
    <StrictMode>
      <MemoryRouter>
        <CustomerPasswordResetPage />
      </MemoryRouter>
    </StrictMode>,
  )
  expect(fetcher).not.toHaveBeenCalled()
  const user = userEvent.setup()
  await user.type(
    screen.getByLabelText('새 비밀번호'),
    'synthetic-password-123',
  )
  await user.click(screen.getByRole('button', { name: '비밀번호 변경' }))
  expect(await screen.findByText('비밀번호를 변경했습니다.')).toBeVisible()
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(screen.queryByLabelText('새 비밀번호')).not.toBeInTheDocument()
})
it('rejects missing proof without an API call', () => {
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  render(
    <MemoryRouter>
      <CustomerPasswordResetPage />
    </MemoryRouter>,
  )
  expect(screen.getByText('재설정 링크를 사용할 수 없습니다.')).toBeVisible()
  expect(fetcher).not.toHaveBeenCalled()
})
it('retains new password on a transient failure and invalidates a rejected proof', async () => {
  window.history.replaceState(
    null,
    '',
    '/#token=synthetic-reset-proof-0000000000000000',
  )
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(new Response(null, { status: 401 })),
  )
  render(
    <MemoryRouter>
      <CustomerPasswordResetPage />
    </MemoryRouter>,
  )
  const user = userEvent.setup()
  await user.type(
    screen.getByLabelText('새 비밀번호'),
    'synthetic-password-123',
  )
  await user.click(screen.getByRole('button', { name: '비밀번호 변경' }))
  expect(await screen.findByRole('alert')).toBeVisible()
  expect(screen.getByLabelText('새 비밀번호')).toHaveValue(
    'synthetic-password-123',
  )
  await user.click(screen.getByRole('button', { name: '비밀번호 변경' }))
  expect(
    await screen.findByText('재설정 링크를 사용할 수 없습니다.'),
  ).toBeVisible()
})
it('uses the reset purpose for initial and repeated mail requests without claiming account existence', async () => {
  const fetcher = vi.fn(async (url: string) => {
    expect(url).toContain('/password-reset-requests')
    return new Response(null, { status: 202 })
  })
  vi.stubGlobal('fetch', fetcher)
  render(
    <MemoryRouter>
      <CustomerPasswordResetRequestPage />
    </MemoryRouter>,
  )
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('이메일 주소'), 'unknown@example.test')
  await user.click(screen.getByRole('button', { name: '재설정 링크 요청' }))
  expect(await screen.findByText(/재설정 가능한 계정이면/)).toBeVisible()
  await user.click(
    screen.getByRole('button', { name: '재설정 링크 다시 요청' }),
  )
  expect(fetcher).toHaveBeenCalledTimes(2)
})
it('retains a valid proof after input rejection and accepts a corrected password', async () => {
  const token = 'synthetic-reset-proof-0000000000000000'
  window.history.replaceState(null, '', '/#token=' + token)
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(new Response(null, { status: 400 }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
  vi.stubGlobal('fetch', fetcher)
  render(
    <MemoryRouter>
      <CustomerPasswordResetPage />
    </MemoryRouter>,
  )
  const user = userEvent.setup()
  const field = screen.getByLabelText('새 비밀번호')
  await user.type(field, 'abcdefghij🙂')
  await user.click(screen.getByRole('button', { name: '비밀번호 변경' }))
  expect(fetcher).not.toHaveBeenCalled()
  await user.type(field, 'k')
  await user.click(screen.getByRole('button', { name: '비밀번호 변경' }))
  expect(
    await screen.findByText('입력 내용을 확인하고 다시 시도해 주세요.'),
  ).toBeVisible()
  expect(field).toHaveValue('abcdefghij🙂k')
  await user.clear(field)
  await user.type(field, 'corrected-password-123')
  await user.click(screen.getByRole('button', { name: '비밀번호 변경' }))
  expect(await screen.findByText('비밀번호를 변경했습니다.')).toBeVisible()
  expect(JSON.parse(fetcher.mock.calls[1]![1].body)).toEqual({
    token,
    newPassword: 'corrected-password-123',
  })
})
it.each([429, 503, 'network'])(
  'clears previous mail success on email changes and failed retry: %s',
  async (failure) => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 202 }))
      .mockResolvedValueOnce(new Response(null, { status: 202 }))
    if (failure === 'network')
      fetcher.mockRejectedValueOnce(new TypeError('offline'))
    else
      fetcher.mockResolvedValueOnce(
        new Response(null, { status: failure as number }),
      )
    vi.stubGlobal('fetch', fetcher)
    render(
      <MemoryRouter>
        <CustomerPasswordResetRequestPage />
      </MemoryRouter>,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이메일 주소'), 'first@example.test')
    await user.click(screen.getByRole('button', { name: '재설정 링크 요청' }))
    expect(await screen.findByText(/재설정 가능한 계정이면/)).toBeVisible()
    await user.clear(screen.getByLabelText('이메일 주소'))
    expect(screen.queryByText(/재설정 가능한 계정이면/)).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('이메일 주소'), 'next@example.test')
    await user.click(screen.getByRole('button', { name: '재설정 링크 요청' }))
    await user.click(
      await screen.findByRole('button', { name: '재설정 링크 다시 요청' }),
    )
    expect(await screen.findByRole('alert')).toBeVisible()
    expect(screen.queryByText(/재설정 가능한 계정이면/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('이메일 주소')).toHaveValue(
      'next@example.test',
    )
    expect(
      screen.getByText(failure === 429 ? /요청이 많습니다/ : /일시적으로 요청/),
    ).toBeVisible()
  },
)
