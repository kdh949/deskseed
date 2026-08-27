import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CustomerRequestConversation } from './CustomerRequestConversation'

describe('CustomerRequestConversation', () => {
  it('constructs the DOM from the allowlisted PUBLIC projection only', () => {
    const request = {
      ticketNumber: 1042,
      subject: '결제 확인 요청',
      status: 'OPEN' as const,
      createdAt: '2026-08-15T00:00:00Z',
      updatedAt: '2026-08-15T01:00:00Z',
      comments: [
        {
          id: 'comment-public-1',
          authorDisplayName: '김민아',
          body: '결제 승인 내역을 확인해 주세요.',
          createdAt: '2026-08-15T00:00:00Z',
          internalNote: 'must-not-render',
          auditMetadata: { actor: 'staff-1' },
        },
      ],
      internalComments: 'must-not-render',
      children: [{ ticketNumber: 1043 }],
      staffAssignee: 'must-not-render',
    }

    render(
      <CustomerRequestConversation
        onSubmitFollowUp={vi.fn().mockResolvedValue(undefined)}
        request={request}
      />,
    )

    expect(
      screen.getByRole('heading', { name: '#1042 결제 확인 요청' }),
    ).toBeVisible()
    expect(screen.getByText('처리 중')).toBeVisible()
    expect(screen.getByText('결제 승인 내역을 확인해 주세요.')).toBeVisible()
    expect(screen.getByRole('textbox', { name: '추가 답변' })).toBeVisible()
    expect(screen.queryByText('must-not-render')).not.toBeInTheDocument()
    expect(screen.queryByText('staff-1')).not.toBeInTheDocument()
    expect(screen.queryByText('1043')).not.toBeInTheDocument()
  })
})
