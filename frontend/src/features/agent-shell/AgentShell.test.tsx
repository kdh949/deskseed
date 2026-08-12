import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AgentShell, DeskseedThemeProvider } from '../../design-system'

describe('AgentShell', () => {
  it('keeps the empty workspace within the desktop layout and exposes keyboard focusable navigation', async () => {
    const user = userEvent.setup()
    render(
      <DeskseedThemeProvider>
        <MemoryRouter>
          <AgentShell displayName="상담사" />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    const workspace = screen.getByRole('main', { name: '상담사 작업 공간' })
    expect(workspace).toHaveClass('agent-workspace')
    expect(screen.getByText('처리할 티켓을 선택하세요')).toBeVisible()
    expect(
      screen
        .getByRole('link', { name: 'Deskseed 상담사 홈' })
        .querySelector('img'),
    ).toHaveAttribute(
      'src',
      expect.stringContaining('brand-mark-transparent-v2.png'),
    )
    expect(screen.getByRole('button', { name: '생성' })).toBeVisible()

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
