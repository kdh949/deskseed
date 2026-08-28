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
    expect(
      screen.queryByText('로그인한 계정에 연결된 공개 문의만 표시합니다.'),
    ).not.toBeInTheDocument()
  })

  it('uses a direct empty state without explaining the server-side access boundary', () => {
    render(
      <MemoryRouter>
        <CustomerRequestList
          items={[]}
          loadingMore={false}
          nextCursor={null}
          onLoadMore={vi.fn()}
        />
      </MemoryRouter>,
    )

    expect(
      screen.getByRole('heading', { name: '아직 접수한 문의가 없습니다.' }),
    ).toBeVisible()
    expect(
      screen.getByText(
        '새 문의를 접수하면 이곳에서 진행 상황과 답변을 확인할 수 있습니다.',
      ),
    ).toBeVisible()
    expect(screen.queryByText(/공개 문의|공개 답변/)).not.toBeInTheDocument()
  })
})
