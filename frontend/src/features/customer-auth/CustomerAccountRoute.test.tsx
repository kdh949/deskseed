import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CustomerAccountRoute } from './CustomerAccountRoute'
import { CustomerRouteLayout } from './CustomerRouteLayout'

afterEach(() => vi.unstubAllGlobals())

describe('CustomerAccountRoute', () => {
  it('does not construct account content before an anonymous visitor is redirected to customer sign-in', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ title: 'Unauthorized', status: 401 }), {
          status: 401,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      ),
    )

    render(
      <MemoryRouter initialEntries={['/account/requests']}>
        <Routes>
          <Route element={<CustomerRouteLayout />}>
            <Route element={<CustomerAccountRoute />}>
              <Route
                path="/account/requests"
                element={<p>private-customer-account-content</p>}
              />
            </Route>
            <Route path="/customer/sign-in" element={<p>customer-sign-in</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('customer-sign-in')).toBeVisible()
    expect(
      screen.queryByText('private-customer-account-content'),
    ).not.toBeInTheDocument()
  })
})
