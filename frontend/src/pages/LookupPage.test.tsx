import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'
import { RequestAccessProvider } from '../features/customer-requests/RequestAccessContext'
import { LookupPage } from './LookupPage'

function LocationProbe() {
  const location = useLocation()
  return (
    <output aria-label="current location">
      {location.pathname}
      {location.search}
    </output>
  )
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <RequestAccessProvider>
        <MemoryRouter initialEntries={['/requests/lookup']}>
          <Routes>
            <Route path="/requests/lookup" element={<LookupPage />} />
            <Route path="/requests/:ticketNumber" element={<LocationProbe />} />
          </Routes>
        </MemoryRouter>
      </RequestAccessProvider>
    </QueryClientProvider>,
  )
}

describe('LookupPage', () => {
  it('keeps the action disabled until both values match the contract', async () => {
    const user = userEvent.setup()
    renderPage()

    const button = screen.getByRole('button', { name: '문의 보기' })
    expect(button).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: /접수 번호/ }), '1042')
    await user.type(screen.getByRole('textbox', { name: /조회 키/ }), 'short')
    expect(button).toBeDisabled()
    expect(
      screen.getByRole('textbox', { name: /조회 키/ }),
    ).toHaveAccessibleDescription(
      '접수 완료 화면에서 발급된 32자 이상의 키를 입력하세요.',
    )
  })

  it('navigates without placing the access key in the URL', async () => {
    const user = userEvent.setup()
    renderPage()
    const token = 'lookup-token-that-is-at-least-32-characters'

    await user.type(screen.getByRole('textbox', { name: /접수 번호/ }), '1042')
    await user.type(screen.getByRole('textbox', { name: /조회 키/ }), token)
    await user.click(screen.getByRole('button', { name: '문의 보기' }))

    const location = screen.getByRole('status', { name: 'current location' })
    expect(location).toHaveTextContent('/requests/1042')
    expect(location).not.toHaveTextContent(token)
  })
})
