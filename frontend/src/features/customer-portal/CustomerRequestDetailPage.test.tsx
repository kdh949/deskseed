import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  CustomerSessionProvider,
  useCustomerSession,
} from '../customer-auth/CustomerSessionContext'
import type { CurrentCustomer } from '../customer-auth/api/customerAuthClient'
import { CustomerRequestDetailPage } from './CustomerRequestDetailPage'
import { customerRequestQueryKeys } from './customerRequestQueryKeys'

const detail = {
  ticketNumber: 1042,
  subject: '결제 확인 요청',
  status: 'OPEN',
  createdAt: '2026-08-15T00:00:00Z',
  updatedAt: '2026-08-15T01:00:00Z',
  comments: [
    {
      id: 'comment-public-1',
      authorDisplayName: '김민아',
      body: '결제 승인 내역을 확인해 주세요.',
      createdAt: '2026-08-15T00:00:00Z',
      internalNote: 'must-not-render',
    },
  ],
  staffAssignee: 'must-not-render',
  auditMetadata: { actor: 'staff-1' },
}

const customerA: CurrentCustomer = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'customer-a@example.test',
  displayName: '고객 A',
  verifiedAt: '2026-08-15T00:00:00Z',
}

const customerB: CurrentCustomer = {
  id: '22222222-2222-4222-8222-222222222222',
  email: 'customer-b@example.test',
  displayName: '고객 B',
  verifiedAt: '2026-08-15T00:00:00Z',
}

function createQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
}

function renderPage(queryClient = createQueryClient()) {
  return render(
    <QueryClientProvider client={queryClient}>
      <CustomerSessionProvider>
        <MemoryRouter initialEntries={['/account/requests/1042']}>
          <Routes>
            <Route
              path="/account/requests/:ticketNumber"
              element={<CustomerRequestDetailPage />}
            />
          </Routes>
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
        <MemoryRouter initialEntries={['/account/requests/1042']}>
          <Routes>
            <Route
              path="/account/requests/:ticketNumber"
              element={<CustomerRequestDetailPage />}
            />
          </Routes>
        </MemoryRouter>
      </CustomerSessionProvider>
    </QueryClientProvider>,
  )
}

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe('CustomerRequestDetailPage', () => {
  it('loads the owned PUBLIC detail, writes an authenticated follow-up through CSRF, and refreshes the projection after confirmed success', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/v1/customer/me')) {
        return Promise.resolve(jsonResponse(customerA))
      }
      if (url.endsWith('/api/v1/customer/requests/1042') && !init?.method) {
        return Promise.resolve(
          new Response(JSON.stringify(detail), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      }
      if (url.endsWith('/api/v1/customer/csrf')) {
        return Promise.resolve(
          new Response(JSON.stringify({ token: 'csrf-token' }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      }
      if (
        url.endsWith('/api/v1/customer/requests/1042/comments') &&
        init?.method === 'POST'
      ) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              id: 'comment-public-2',
              authorDisplayName: '김민아',
              body: '추가 정보입니다.',
              createdAt: '2026-08-15T02:00:00Z',
            }),
            { status: 201, headers: { 'Content-Type': 'application/json' } },
          ),
        )
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    expect(
      await screen.findByRole('heading', { name: '#1042 결제 확인 요청' }),
    ).toBeVisible()
    expect(screen.queryByText('must-not-render')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('추가 답변'), '추가 정보입니다.')
    await user.click(screen.getByRole('button', { name: '답변 보내기' }))

    expect(await screen.findByText('답변이 저장되었습니다.')).toBeVisible()
    const writeCall = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith('/api/v1/customer/requests/1042/comments') &&
        (init as RequestInit | undefined)?.method === 'POST',
    )
    expect(writeCall?.[1]).toMatchObject({
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-TOKEN': 'csrf-token',
      },
    })
    expect(JSON.parse(String(writeCall?.[1]?.body))).toMatchObject({
      body: '추가 정보입니다.',
      clientCommandId: expect.any(String),
    })
    await waitFor(() => {
      expect(
        fetchMock.mock.calls.filter(
          ([input, init]) =>
            String(input).endsWith('/api/v1/customer/requests/1042') &&
            !(init as RequestInit | undefined)?.method,
        ),
      ).toHaveLength(2)
    })
  })

  it('does not render customer A detail cache when customer B opens the same ticket number', async () => {
    const user = userEvent.setup()
    const queryClient = createQueryClient()
    let activeCustomer = customerA.id
    let resolveCustomerB: ((response: Response) => void) | undefined
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/v1/customer/me'))
        return Promise.resolve(jsonResponse(customerA))
      if (url.endsWith('/api/v1/customer/requests/1042') && !init?.method) {
        if (activeCustomer === customerA.id) {
          return Promise.resolve(
            jsonResponse({ ...detail, subject: 'A의 같은 번호 비공개 문의' }),
          )
        }
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
      await screen.findByRole('heading', {
        name: '#1042 A의 같은 번호 비공개 문의',
      }),
    ).toBeVisible()

    await user.click(screen.getByRole('button', { name: '고객 B로 전환' }))

    await waitFor(() => expect(resolveCustomerB).toBeDefined())
    expect(
      screen.queryByRole('heading', {
        name: '#1042 A의 같은 번호 비공개 문의',
      }),
    ).not.toBeInTheDocument()
    expect(
      queryClient.getQueryData(
        customerRequestQueryKeys.detail(customerA.id, 1042),
      ),
    ).toBeUndefined()

    resolveCustomerB?.(
      jsonResponse({ ...detail, subject: 'B의 같은 번호 비공개 문의' }),
    )
    expect(
      await screen.findByRole('heading', {
        name: '#1042 B의 같은 번호 비공개 문의',
      }),
    ).toBeVisible()
  })
})
