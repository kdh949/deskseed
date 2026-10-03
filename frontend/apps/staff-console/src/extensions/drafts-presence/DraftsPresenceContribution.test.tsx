import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ComposerPresenceStatus,
  TicketPresenceContext,
} from './DraftsPresenceContribution'
import { useTicketCollaboration } from './useTicketCollaboration'

vi.mock('./useTicketCollaboration', () => ({
  useTicketCollaboration: vi.fn(),
}))

const mockedCollaboration = vi.mocked(useTicketCollaboration)

describe('DraftsPresenceContribution', () => {
  it('renders a semantic empty, presence, stale-update, and unavailable state without ticket content', () => {
    mockedCollaboration.mockReturnValue({
      retry: vi.fn(),
      lastConfirmedAt: '2026-10-03T00:00:00Z',
      reconnectAt: null,
      snapshotReceived: true,
      connection: 'connected',
      members: [
        {
          staffId: '018f7c2c-7348-7a32-a971-4c9a845b3311',
          displayName: 'Presence Agent',
          state: 'EDITING_INTERNAL',
          lastSeenAt: '2026-08-18T00:00:00Z',
        },
      ],
      ticketUpdate: {
        ticketVersion: 8,
        changedFields: ['priority', 'comments'],
      },
    })

    render(<TicketPresenceContext ticketNumber={1042} />)

    expect(
      screen.getByRole('complementary', { name: '함께 작업 중인 상담사' }),
    ).toBeVisible()
    expect(screen.getByText('Presence Agent')).toBeVisible()
    expect(screen.getByText('내부 메모 작성 중')).toBeVisible()
    expect(screen.getByText('새 티켓 버전이 저장되었습니다.')).toBeVisible()
    expect(screen.getByRole('button', { name: '최신 버전 확인' })).toBeVisible()
    expect(screen.queryByText(/고객 이메일|댓글 본문/)).not.toBeInTheDocument()
  })

  it('keeps explicit unavailable and composer status copy', () => {
    mockedCollaboration.mockReturnValue({
      retry: vi.fn(),
      lastConfirmedAt: '2026-10-03T00:00:00Z',
      reconnectAt: null,
      snapshotReceived: true,
      connection: 'unavailable',
      members: [],
      ticketUpdate: null,
    })

    const { rerender } = render(<TicketPresenceContext ticketNumber={1042} />)
    expect(
      screen.getByText(/실시간 작업 상태를 연결하지 못했습니다/),
    ).toBeVisible()

    rerender(
      <ComposerPresenceStatus composerMode="public" ticketNumber={1042} />,
    )
    expect(
      screen.getByText(/초안 보관 상태를 별도로 확인해 주세요/),
    ).toBeVisible()
  })

  it('distinguishes an authorization denial from a transient connection failure', () => {
    mockedCollaboration.mockReturnValue({
      retry: vi.fn(),
      lastConfirmedAt: '2026-10-03T00:00:00Z',
      reconnectAt: null,
      snapshotReceived: true,
      connection: 'denied',
      members: [],
      ticketUpdate: null,
    })

    const { rerender } = render(<TicketPresenceContext ticketNumber={1042} />)
    expect(
      screen.getByText(/세션 또는 티켓 권한을 확인하지 못했습니다/),
    ).toBeVisible()
    expect(screen.getByText('권한 없음')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '연결 다시 확인' }))
    expect(
      mockedCollaboration.mock.results.at(-1)?.value.retry,
    ).toHaveBeenCalledOnce()

    rerender(
      <ComposerPresenceStatus composerMode="internal" ticketNumber={1042} />,
    )
    expect(
      screen.getByText(/실시간 작업 상태가 공유되지 않습니다/),
    ).toBeVisible()
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})
