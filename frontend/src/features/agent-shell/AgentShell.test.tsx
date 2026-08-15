import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AgentShell, DeskseedThemeProvider } from '../../design-system'

describe('AgentShell', () => {
  it('exposes only the keyboard-accessible Queue navigation contract', async () => {
    const user = userEvent.setup()
    render(
      <DeskseedThemeProvider>
        <MemoryRouter>
          <AgentShell canCreateTicket displayName="상담사" />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(screen.getByRole('link', { name: 'Deskseed 티켓 큐' })).toBeVisible()
    expect(screen.getByRole('link', { name: 'Views' })).toBeVisible()
    expect(screen.getByRole('link', { name: '새 티켓' })).toBeVisible()
    expect(
      screen.queryByRole('navigation', { name: '열린 티켓 탭' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByText('#1042')).not.toBeInTheDocument()
    expect(screen.queryByText('#1038')).not.toBeInTheDocument()
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '생성' }),
    ).not.toBeInTheDocument()

    await user.tab()
    expect(screen.getByRole('link', { name: 'Deskseed 티켓 큐' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('link', { name: 'Views' })).toHaveFocus()
    expect(
      screen.queryByRole('link', { name: '관리자 설정' }),
    ).not.toBeInTheDocument()
  })

  it('hides ticket creation when the caller does not grant the capability', () => {
    render(
      <DeskseedThemeProvider>
        <MemoryRouter>
          <AgentShell displayName="감사 담당자" />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(
      screen.queryByRole('link', { name: '새 티켓' }),
    ).not.toBeInTheDocument()
  })
})
