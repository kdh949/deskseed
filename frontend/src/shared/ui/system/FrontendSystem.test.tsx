import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ComposerModeSeam } from './ComposerModeSeam'
import { ContextPanel, type ContextPanelTab } from './ContextPanel'
import { Notification, ScreenState } from './Feedback'
import { TicketTable } from './TicketTable'

describe('Deskseed frontend system primitives', () => {
  it('keeps PUBLIC and INTERNAL drafts separate with roving tab and panel semantics', async () => {
    const user = userEvent.setup()
    render(<ComposerModeSeam />)

    const publicTab = screen.getByRole('tab', { name: '공개 답변' })
    const internalTab = screen.getByRole('tab', { name: '내부 메모' })
    const publicPanel = document.getElementById(
      publicTab.getAttribute('aria-controls') ?? '',
    )
    const internalPanel = document.getElementById(
      internalTab.getAttribute('aria-controls') ?? '',
    )

    expect(publicTab).toHaveAttribute('tabindex', '0')
    expect(internalTab).toHaveAttribute('tabindex', '-1')
    expect(publicTab.getAttribute('aria-controls')).not.toBe(
      internalTab.getAttribute('aria-controls'),
    )
    expect(publicPanel).toHaveAttribute('role', 'tabpanel')
    expect(publicPanel).toHaveAttribute('aria-labelledby', publicTab.id)
    expect(internalPanel).toHaveAttribute('role', 'tabpanel')
    expect(internalPanel).toHaveAttribute('aria-labelledby', internalTab.id)
    expect(screen.getByRole('status')).toHaveTextContent('공개 답변 모드')
    await user.type(
      screen.getByRole('textbox', { name: '공개 답변' }),
      '고객 답변',
    )
    publicTab.focus()
    await user.keyboard('{ArrowRight}')

    expect(internalTab).toHaveFocus()
    expect(internalTab).toHaveAttribute('tabindex', '0')
    expect(publicTab).toHaveAttribute('tabindex', '-1')
    expect(screen.getByRole('status')).toHaveTextContent(
      '고객에게 공개되지 않습니다',
    )
    expect(screen.getByRole('textbox', { name: '내부 메모' })).toHaveValue('')
    await user.type(
      screen.getByRole('textbox', { name: '내부 메모' }),
      '팀 메모',
    )
    internalTab.focus()
    await user.keyboard('{Home}')

    expect(publicTab).toHaveFocus()
    expect(screen.getByRole('textbox', { name: '공개 답변' })).toHaveValue(
      '고객 답변',
    )
    publicTab.focus()
    await user.keyboard('{End}')
    expect(internalTab).toHaveFocus()
    expect(screen.getByRole('textbox', { name: '내부 메모' })).toHaveValue(
      '팀 메모',
    )
    internalTab.focus()
    await user.keyboard('{ArrowLeft}')
    expect(publicTab).toHaveFocus()
  })

  it('moves context tabs with arrow keys and updates the active panel', async () => {
    const user = userEvent.setup()
    render(<ContextHarness />)

    const customerTab = screen.getByRole('tab', { name: '고객' })
    customerTab.focus()
    await user.keyboard('{ArrowRight}')

    expect(screen.getByRole('tab', { name: '기록' })).toHaveFocus()
    expect(screen.getByRole('tabpanel')).toHaveTextContent('history content')
  })

  it('uses explicit roles and text for async states and notifications', () => {
    render(
      <>
        <ScreenState
          kind="denied"
          title="권한이 없습니다."
          requestId="safe-id"
        />
        <Notification tone="warning" title="열린 child ticket이 있습니다." />
      </>,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('접근 거부')
    expect(screen.getByRole('alert')).toHaveTextContent('safe-id')
    expect(screen.getByRole('status')).toHaveTextContent('열린 child ticket')
  })

  it('renders accessible sticky headers and a keyboard-open ticket link', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <TicketTable
          label="Pending 티켓"
          items={[
            {
              ticketNumber: 1042,
              subject: '결제 승인 오류',
              status: 'OPEN',
              priority: 'URGENT',
              requester: '김민수',
              group: '결제 지원',
              assignee: '박서연',
              updatedLabel: '08. 10. 오후 07:02',
            },
          ]}
        />
      </MemoryRouter>,
    )

    expect(screen.getByRole('columnheader', { name: '상태' })).toBeVisible()
    const link = screen.getByRole('link', { name: '#1042 결제 승인 오류 열기' })
    await user.tab()
    expect(link).toHaveFocus()
  })
})

function ContextHarness() {
  const tabs: ContextPanelTab[] = [
    { id: 'customer', label: '고객' },
    { id: 'history', label: '기록' },
    { id: 'related', label: '관련' },
  ]
  const [active, setActive] = useState('customer')
  return (
    <ContextPanel
      label="티켓 컨텍스트"
      tabs={tabs}
      activeTab={active}
      onTabChange={setActive}
    >
      {active} content
    </ContextPanel>
  )
}
