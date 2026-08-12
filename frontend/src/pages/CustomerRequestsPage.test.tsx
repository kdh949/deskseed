import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../features/customer-portal/customerPortalClient'
import type * as PortalClient from '../features/customer-portal/customerPortalClient'
import {
  CustomerRequestDetailPage,
  CustomerRequestsPage,
} from './CustomerRequestsPage'

const apiMocks = vi.hoisted(() => ({
  listCustomerRequests: vi.fn(),
  getCustomerRequest: vi.fn(),
  addCustomerFollowUp: vi.fn(),
  claimCustomerRequest: vi.fn(),
}))

vi.mock('../features/customer-portal/customerPortalClient', async () => {
  const actual = await vi.importActual<typeof PortalClient>(
    '../features/customer-portal/customerPortalClient',
  )
  return { ...actual, ...apiMocks }
})

const request = {
  ticketNumber: 1042,
  subject: '결제 오류 문의',
  status: 'OPEN' as const,
  createdAt: '2026-08-10T00:00:00Z',
  updatedAt: '2026-08-10T01:00:00Z',
}

function renderAt(path: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/account/requests" element={<CustomerRequestsPage />} />
          <Route
            path="/account/requests/:ticketNumber"
            element={<CustomerRequestDetailPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('Customer Requests portal', () => {
  beforeEach(() => vi.clearAllMocks())

  it('distinguishes loading and empty request states', async () => {
    apiMocks.listCustomerRequests.mockReturnValueOnce(
      new Promise(() => undefined),
    )
    const view = renderAt('/account/requests')
    expect(
      screen.getByRole('status', { name: '내 문의를 불러오는 중' }),
    ).toHaveAttribute('aria-busy', 'true')
    view.unmount()

    apiMocks.listCustomerRequests.mockResolvedValueOnce({
      items: [],
      nextCursor: null,
    })
    renderAt('/account/requests')
    expect(
      await screen.findByRole('heading', {
        name: '아직 연결된 문의가 없습니다.',
      }),
    ).toBeVisible()
  })

  it('shows customer-safe list fields and claims with a preserved proof', async () => {
    const user = userEvent.setup()
    apiMocks.listCustomerRequests.mockResolvedValue({
      items: [request],
      nextCursor: null,
    })
    apiMocks.claimCustomerRequest.mockRejectedValueOnce(
      new ApiError('claim expired', 404, 'claim-request-id'),
    )
    renderAt('/account/requests')

    expect(
      await screen.findByRole('link', { name: /결제 오류 문의/ }),
    ).toBeVisible()
    await user.type(
      screen.getByRole('spinbutton', { name: '접수 번호' }),
      '1099',
    )
    await user.type(
      screen.getByRole('textbox', { name: '연결 증명' }),
      'x'.repeat(43),
    )
    await user.click(screen.getByRole('button', { name: '문의 연결' }))

    expect(
      await screen.findByRole('heading', {
        name: '연결 증명을 사용할 수 없습니다.',
      }),
    ).toBeVisible()
    expect(screen.getByRole('textbox', { name: '연결 증명' })).toHaveValue(
      'x'.repeat(43),
    )
    expect(apiMocks.claimCustomerRequest).toHaveBeenCalledWith(1099, {
      requestAccessToken: 'x'.repeat(43),
    })
  })

  it('loads the next cursor page and removes duplicate tickets', async () => {
    const user = userEvent.setup()
    apiMocks.listCustomerRequests
      .mockResolvedValueOnce({ items: [request], nextCursor: 'cursor-2' })
      .mockResolvedValueOnce({
        items: [
          request,
          {
            ...request,
            ticketNumber: 1041,
            subject: '이전 문의',
          },
        ],
        nextCursor: null,
      })
    renderAt('/account/requests')

    await user.click(
      await screen.findByRole('button', { name: '문의 더 보기' }),
    )

    expect(await screen.findByRole('link', { name: /이전 문의/ })).toBeVisible()
    expect(
      screen.getAllByRole('link', { name: /결제 오류 문의/ }),
    ).toHaveLength(1)
    expect(apiMocks.listCustomerRequests).toHaveBeenNthCalledWith(
      2,
      undefined,
      'cursor-2',
    )
    expect(
      screen.queryByRole('button', { name: '문의 더 보기' }),
    ).not.toBeInTheDocument()
  })

  it('preserves follow-up draft on failure and invalidates detail after success', async () => {
    const user = userEvent.setup()
    apiMocks.getCustomerRequest.mockResolvedValue({
      ...request,
      comments: [
        {
          id: 'comment-1',
          authorDisplayName: '고객',
          body: '최초 문의',
          createdAt: request.createdAt,
        },
      ],
    })
    apiMocks.addCustomerFollowUp
      .mockRejectedValueOnce(
        new ApiError('temporary', 503, 'follow-up-request-id'),
      )
      .mockResolvedValueOnce({
        id: 'comment-2',
        authorDisplayName: '고객',
        body: '추가 정보',
        createdAt: request.updatedAt,
      })
    renderAt('/account/requests/1042')

    const draft = await screen.findByRole('textbox', { name: '공개 후속 답변' })
    await user.type(draft, '추가 정보')
    await user.click(screen.getByRole('button', { name: '공개 답변 보내기' }))
    expect(
      await screen.findByRole('alert', {
        name: '후속 답변을 보내지 못했습니다.',
      }),
    ).toHaveTextContent('follow-up-request-id')
    expect(draft).toHaveValue('추가 정보')

    await user.click(screen.getByRole('button', { name: '공개 답변 보내기' }))
    await waitFor(() => expect(draft).toHaveValue(''))
    expect(apiMocks.addCustomerFollowUp).toHaveBeenCalledTimes(2)
    expect(apiMocks.addCustomerFollowUp.mock.calls[0]?.[2]).toBe(
      apiMocks.addCustomerFollowUp.mock.calls[1]?.[2],
    )
  })

  it('uses a new command id when the draft changes after a failure', async () => {
    const user = userEvent.setup()
    apiMocks.getCustomerRequest.mockResolvedValue({
      ...request,
      comments: [],
    })
    apiMocks.addCustomerFollowUp
      .mockRejectedValueOnce(new ApiError('temporary', 503))
      .mockResolvedValueOnce({
        id: 'comment-2',
        authorDisplayName: '고객',
        body: '수정한 추가 정보',
        createdAt: request.updatedAt,
      })
    renderAt('/account/requests/1042')

    const draft = await screen.findByRole('textbox', { name: '공개 후속 답변' })
    await user.type(draft, '추가 정보')
    await user.click(screen.getByRole('button', { name: '공개 답변 보내기' }))
    await screen.findByRole('alert', {
      name: '후속 답변을 보내지 못했습니다.',
    })
    await user.clear(draft)
    await user.type(draft, '수정한 추가 정보')
    await user.click(screen.getByRole('button', { name: '공개 답변 보내기' }))

    await waitFor(() => expect(draft).toHaveValue(''))
    expect(apiMocks.addCustomerFollowUp.mock.calls[0]?.[2]).not.toBe(
      apiMocks.addCustomerFollowUp.mock.calls[1]?.[2],
    )
    expect(apiMocks.addCustomerFollowUp.mock.calls[1]?.[1]).toBe(
      '수정한 추가 정보',
    )
  })

  it('renders denied detail without leaking server detail', async () => {
    apiMocks.getCustomerRequest.mockRejectedValue(
      new ApiError('requester mismatch internal detail', 404, 'safe-id'),
    )
    renderAt('/account/requests/9999')
    const state = await screen.findByRole('status', {
      name: '문의를 확인할 수 없습니다.',
    })
    expect(state).not.toHaveTextContent('requester mismatch')
  })
})
