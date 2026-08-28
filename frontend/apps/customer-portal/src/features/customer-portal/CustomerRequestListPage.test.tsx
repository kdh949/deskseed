import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  CustomerSessionProvider,
  useCustomerSession,
} from '../customer-auth/CustomerSessionContext'
import type { CurrentCustomer } from '../customer-auth/api/customerAuthClient'
import { CustomerRequestListPage } from './CustomerRequestListPage'
import { customerRequestQueryKeys } from './customerRequestQueryKeys'

const customerA: CurrentCustomer = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'customer-a@example.test',
  displayName: '고객 A',
  companyName: 'A 회사',
  verifiedAt: '2026-08-15T00:00:00Z',
  credentialState: 'PASSWORD',
  registrationState: 'COMPLETE',
  availableAuthenticationMethods: ['PASSWORD'],
}

const customerB: CurrentCustomer = {
  id: '22222222-2222-4222-8222-222222222222',
  email: 'customer-b@example.test',
  displayName: '고객 B',
  companyName: 'B 회사',
  verifiedAt: '2026-08-15T00:00:00Z',
  credentialState: 'PASSWORD',
  registrationState: 'COMPLETE',
  availableAuthenticationMethods: ['PASSWORD'],
}

function requestPage(ticketNumber: number, subject: string) {
  return {
    items: [
      {
        ticketNumber,
        subject,
        status: 'OPEN',
        createdAt: '2026-08-14T00:00:00Z',
        updatedAt: '2026-08-15T01:00:00Z',
      },
    ],
    nextCursor: null,
  }
}

function renderPage(queryClient = createQueryClient()) {
  return render(
    <QueryClientProvider client={queryClient}>
      <CustomerSessionProvider>
        <MemoryRouter>
          <CustomerRequestListPage />
        </MemoryRouter>
      </CustomerSessionProvider>
    </QueryClientProvider>,
  )
}

function CustomerSwitch({ onSwitch }: { onSwitch: () => void }) {
  const session = useCustomerSession()
  return (
    <button
      onClick={() => {
        onSwitch()
        session.acceptAuthenticatedCustomer(customerB)
      }}
    >
      고객 B로 전환
    </button>
  )
}

function renderPageWithCustomerSwitch(
  queryClient: QueryClient,
  onSwitch: () => void,
) {
  return render(
    <QueryClientProvider client={queryClient}>
      <CustomerSessionProvider>
        <CustomerSwitch onSwitch={onSwitch} />
        <MemoryRouter>
          <CustomerRequestListPage />
        </MemoryRouter>
      </CustomerSessionProvider>
    </QueryClientProvider>,
  )
}

function createQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
}

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe('CustomerRequestListPage', () => {
  it('loads owned customer request summaries with no-referrer transport and does not retain unknown staff fields', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/v1/customer/me'))
        return Promise.resolve(jsonResponse(customerA))
      if (url.endsWith('/api/v1/customer/requests?limit=25')) {
        return Promise.resolve(
          jsonResponse({
            ...requestPage(1042, '결제 확인 요청'),
            internalComment: 'must-not-render',
            auditMetadata: { actor: 'staff-1' },
          }),
        )
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

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

  it('does not render customer A cache while customer B requests are loading after an in-app account switch', async () => {
    const user = userEvent.setup()
    const queryClient = createQueryClient()
    let activeCustomer = customerA.id
    let resolveCustomerB: ((response: Response) => void) | undefined
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/v1/customer/me'))
        return Promise.resolve(jsonResponse(customerA))
      if (url.endsWith('/api/v1/customer/requests?limit=25')) {
        if (activeCustomer === customerA.id)
          return Promise.resolve(
            jsonResponse(requestPage(1042, 'A의 비공개 문의')),
          )
        return new Promise<Response>((resolve) => {
          resolveCustomerB = resolve
        })
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPageWithCustomerSwitch(queryClient, () => {
      activeCustomer = customerB.id
    })

    expect(
      await screen.findByRole('link', { name: /A의 비공개 문의/ }),
    ).toBeVisible()

    await user.click(screen.getByRole('button', { name: '고객 B로 전환' }))

    await waitFor(() => expect(resolveCustomerB).toBeDefined())
    expect(screen.queryByText('A의 비공개 문의')).not.toBeInTheDocument()
    expect(
      queryClient.getQueryData(customerRequestQueryKeys.list(customerA.id)),
    ).toBeUndefined()

    resolveCustomerB?.(jsonResponse(requestPage(1042, 'B의 비공개 문의')))
    expect(
      await screen.findByRole('link', { name: /B의 비공개 문의/ }),
    ).toBeVisible()
  })
})
