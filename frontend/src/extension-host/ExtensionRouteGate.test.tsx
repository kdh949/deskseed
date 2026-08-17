import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ExtensionRouteGate } from './ExtensionRouteGate'

let currentStaff: {
  role: string
  capabilities: string[]
} | null = null

vi.mock('../features/staff-auth/StaffSessionContext', () => ({
  useStaffSession: () => ({ staff: currentStaff }),
}))

const contribution = {
  id: 'knowledge.search',
  kind: 'route' as const,
  surface: 'agent' as const,
  path: 'knowledge',
  title: 'Knowledge',
  order: 0,
  requiredCapabilities: ['KNOWLEDGE_READ'],
  requiredRoles: ['AGENT'],
  element: <p>Knowledge route</p>,
}

describe('ExtensionRouteGate', () => {
  it('denies an unauthorized route before rendering the contribution', () => {
    currentStaff = { role: 'AGENT', capabilities: [] }
    render(<ExtensionRouteGate contribution={contribution} />)

    expect(
      screen.getByRole('heading', { name: '이 기능에 접근할 수 없습니다.' }),
    ).toBeVisible()
    expect(screen.queryByText('Knowledge route')).not.toBeInTheDocument()
  })

  it('renders the contribution only when the session matches its policy', () => {
    currentStaff = { role: 'AGENT', capabilities: ['KNOWLEDGE_READ'] }
    render(<ExtensionRouteGate contribution={contribution} />)

    expect(screen.getByText('Knowledge route')).toBeVisible()
  })
})
