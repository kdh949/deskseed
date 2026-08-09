import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { DeskseedThemeProvider } from '../../shared/ui/DeskseedThemeProvider'
import { AgentShell } from './AgentShell'

describe('AgentShell', () => {
  it('keeps the empty workspace within the desktop layout and exposes keyboard focusable navigation', async () => {
    const user = userEvent.setup()

    render(
      <DeskseedThemeProvider>
        <MemoryRouter>
          <AgentShell />
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
  })
})
