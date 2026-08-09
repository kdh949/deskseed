import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import App from './App'
import { DeskseedThemeProvider } from './shared/ui/DeskseedThemeProvider'

describe('App', () => {
  it('renders the Agent Shell without the public customer portal chrome', () => {
    render(
      <DeskseedThemeProvider>
        <MemoryRouter initialEntries={['/agent/home']}>
          <App />
        </MemoryRouter>
      </DeskseedThemeProvider>,
    )

    expect(screen.getByRole('main', { name: '상담사 작업 공간' })).toBeVisible()
    expect(
      screen.queryByRole('navigation', { name: '주요 메뉴' }),
    ).not.toBeInTheDocument()
  })
})
