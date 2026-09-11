import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { AgentRoute } from './StaffRoute'
import { staffDestination } from './staffNavigation'
import type { CurrentStaff } from '../../api/types'
const session = vi.hoisted(() => ({
  staff: {
    id: 'auditor',
    displayName: '감사 담당자',
    email: 'auditor@example.test',
    role: 'SECURITY_AUDITOR',
    capabilities: ['AUDIT_VIEW'],
  },
  signOut: vi.fn(),
  status: 'authenticated',
}))
vi.mock('./StaffSessionContext', () => ({
  useStaffSession: () => session,
  StaffSessionProvider: ({ children }: { children: unknown }) => children,
}))
afterEach(() => vi.clearAllMocks())
it('routes each role to an allowed home and keeps only permitted internal return paths', () => {
  const auditor = session.staff as CurrentStaff
  const admin = {
    ...auditor,
    role: 'ADMIN',
    capabilities: ['ADMIN_MANAGE', 'AGENT_WORKSPACE'],
  } as CurrentStaff
  const agent = {
    ...admin,
    role: 'AGENT',
    capabilities: ['AGENT_WORKSPACE'],
  } as CurrentStaff
  expect(staffDestination(auditor, '/agent/views/my-open')).toBe('/agent/audit')
  expect(staffDestination(auditor, '/agent/audit/exports/job-1')).toBe(
    '/agent/audit/exports/job-1',
  )
  expect(staffDestination(admin, undefined)).toBe('/admin/operations/mail')
  expect(staffDestination(admin, '/agent/audit')).toBe('/admin/operations/mail')
  expect(staffDestination(admin, '/admin/triggers')).toBe('/admin/triggers')
  expect(staffDestination(agent, '/admin/staff')).toBe('/agent/views/my-open')
  expect(staffDestination(agent, '/agent/tickets/42')).toBe('/agent/tickets/42')
  for (const from of [
    '//evil.test',
    'https://evil.test',
    '/agent/tickets/../audit',
    '/agent/tickets/42?token=x',
  ])
    expect(staffDestination(agent, from)).toBe('/agent/views/my-open')
})
it('terminates the current session before showing another-account login', async () => {
  session.signOut.mockResolvedValue(undefined)
  render(
    <MemoryRouter initialEntries={['/agent/tickets/42']}>
      <Routes>
        <Route element={<AgentRoute />}>
          <Route path="/agent/tickets/:id" element={<p>티켓</p>} />
        </Route>
        <Route path="/agent/login" element={<h1>다른 계정 로그인</h1>} />
      </Routes>
    </MemoryRouter>,
  )
  await userEvent.click(
    screen.getByRole('button', { name: '다른 계정으로 로그인' }),
  )
  expect(session.signOut).toHaveBeenCalledTimes(1)
  expect(await screen.findByText('다른 계정 로그인')).toBeVisible()
})
it('keeps a failed logout visible with retry instead of redirecting back into the denied route', async () => {
  session.signOut.mockRejectedValue(new Error('unavailable'))
  render(
    <MemoryRouter>
      <AgentRoute />
    </MemoryRouter>,
  )
  await userEvent.click(
    screen.getByRole('button', { name: '다른 계정으로 로그인' }),
  )
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '로그아웃하지 못했습니다',
  )
})
