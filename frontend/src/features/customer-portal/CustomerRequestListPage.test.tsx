import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CustomerRequestListPage } from './CustomerRequestListPage'

afterEach(() => vi.unstubAllGlobals())

describe('CustomerRequestListPage', () => {
  it('loads owned customer request summaries with no-referrer transport and does not retain unknown staff fields', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [
            {
              ticketNumber: 1042,
              subject: '결제 확인 요청',
              status: 'OPEN',
              createdAt: '2026-08-14T00:00:00Z',
              updatedAt: '2026-08-15T01:00:00Z',
              internalComment: 'must-not-render',
              auditMetadata: { actor: 'staff-1' },
            },
          ],
          nextCursor: null,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <CustomerRequestListPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(
      await screen.findByRole('link', { name: /#1042 결제 확인 요청/ }),
    ).toBeVisible()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/customer/requests?limit=25',
      expect.objectContaining({
        credentials: 'include',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
      }),
    )
    expect(screen.queryByText('must-not-render')).not.toBeInTheDocument()
    expect(screen.queryByText('staff-1')).not.toBeInTheDocument()
  })
})
