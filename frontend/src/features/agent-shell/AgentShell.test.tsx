import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DeskseedThemeProvider } from '../../shared/ui/DeskseedThemeProvider'
import { StaffSessionProvider } from '../staff-auth/StaffSessionContext'
import { AgentShell } from './AgentShell'

describe('AgentShell', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('keeps the empty workspace within the desktop layout and exposes keyboard focusable navigation', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            id: 'agent-id',
            email: 'agent@example.com',
            displayName: '상담사',
            role: 'AGENT',
            capabilities: ['AGENT_WORKSPACE'],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    render(
      <DeskseedThemeProvider>
        <MemoryRouter>
          <StaffSessionProvider>
            <AgentShell />
          </StaffSessionProvider>
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    const workspace = screen.getByRole('main', { name: '상담사 작업 공간' })
    expect(workspace).toHaveClass('agent-workspace')
    expect(screen.getByText('아직 열려 있는 티켓이 없습니다.')).toBeVisible()

    await user.tab()
    expect(
      screen.getByRole('link', { name: 'Deskseed 상담사 홈' }),
    ).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('link', { name: '홈' })).toHaveFocus()
    expect(
      screen.queryByRole('link', { name: '관리자 설정' }),
    ).not.toBeInTheDocument()
  })
})
