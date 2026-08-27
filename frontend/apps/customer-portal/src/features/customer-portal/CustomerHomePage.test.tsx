import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { requestAccessTokenStorageKey } from './customerAccessToken'
import { CustomerHomePage } from './CustomerHomePage'

function LocationProbe() {
  const location = useLocation()
  return <p>{location.pathname}</p>
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<CustomerHomePage />} />
        <Route path="/requests/:ticketNumber" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(() => sessionStorage.clear())

describe('CustomerHomePage', () => {
  it('starts with the request lookup task without a capability-token field', () => {
    renderPage()

    expect(
      screen.getByRole('heading', {
        level: 1,
        name: '문의 번호로 빠르게 확인하세요',
      }),
    ).toBeVisible()
    expect(screen.getByLabelText('문의 번호')).toBeVisible()
    expect(screen.queryByLabelText(/토큰|조회 키/)).not.toBeInTheDocument()
  })

  it('shows the supported invalid-number state in place', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText('문의 번호'), 'abc')
    await user.click(screen.getByRole('button', { name: '문의 열기' }))

    expect(await screen.findByText('문의 번호를 확인해 주세요.')).toBeVisible()
  })

  it('opens a request only with the browser-held ticket-scoped access proof', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem(requestAccessTokenStorageKey(1042), 'a'.repeat(43))
    renderPage()

    await user.type(screen.getByLabelText('문의 번호'), '1042')
    await user.click(screen.getByRole('button', { name: '문의 열기' }))

    expect(await screen.findByText('/requests/1042')).toBeVisible()
  })
})
