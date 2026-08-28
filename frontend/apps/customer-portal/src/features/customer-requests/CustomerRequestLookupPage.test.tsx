import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { requestAccessTokenStorageKey } from '../customer-portal/customerAccessToken'
import { CustomerRequestLookupPage } from './CustomerRequestLookupPage'

function LocationProbe() {
  const location = useLocation()
  return <p>{`${location.pathname}${location.search}`}</p>
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/requests/lookup']}>
      <Routes>
        <Route
          path="/requests/lookup"
          element={<CustomerRequestLookupPage />}
        />
        <Route path="/requests/:ticketNumber" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(() => sessionStorage.clear())

describe('CustomerRequestLookupPage', () => {
  it('opens a ticket only when this browser already holds that ticket-scoped access proof', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem(requestAccessTokenStorageKey(1042), 'a'.repeat(43))

    renderPage()

    await user.type(screen.getByLabelText('문의 번호'), '1042')
    await user.click(screen.getByRole('button', { name: '문의 열기' }))

    expect(await screen.findByText('/requests/1042')).toBeVisible()
    expect(screen.queryByText(/token=/)).not.toBeInTheDocument()
  })

  it('does not ask a customer to paste a capability token when this browser has no ticket-scoped access proof', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText('문의 번호'), '1042')
    await user.click(screen.getByRole('button', { name: '문의 열기' }))

    expect(
      await screen.findByText('이메일로 받은 문의 링크를 다시 열어 주세요.'),
    ).toBeVisible()
    expect(
      screen.queryByText(/이 브라우저|보안을 위해/),
    ).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/토큰/)).not.toBeInTheDocument()
  })
})
