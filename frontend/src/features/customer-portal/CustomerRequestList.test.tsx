import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { CustomerRequestList } from './CustomerRequestList'

describe('CustomerRequestList', () => {
  it('renders only the authenticated customer summary projection and uses updatedAt for the visible freshness time', () => {
    const items = [
      {
        ticketNumber: 1042,
        subject: '결제 확인 요청',
        status: 'OPEN' as const,
        createdAt: '2026-08-14T00:00:00Z',
        updatedAt: '2026-08-15T01:00:00Z',
        internalComment: 'must-not-render',
        assignedStaff: 'must-not-render',
      },
    ]
    render(
      <MemoryRouter>
        <CustomerRequestList
          items={items}
          loadingMore={false}
          nextCursor={null}
          onLoadMore={vi.fn()}
        />
      </MemoryRouter>,
    )

    expect(
      screen.getByRole('link', { name: /#1042 결제 확인 요청/ }),
    ).toHaveAttribute('href', '/account/requests/1042')
    expect(screen.getByText('처리 중')).toBeVisible()
    expect(
      screen.getByText('최근 업데이트').closest('p')?.querySelector('time'),
    ).toHaveAttribute('datetime', '2026-08-15T01:00:00Z')
    expect(screen.queryByText('must-not-render')).not.toBeInTheDocument()
  })
})
