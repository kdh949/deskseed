import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type * as ApiClient from '../api/client'
import { AdminStaffPage } from './AdminStaffPage'

const apiMocks = vi.hoisted(() => ({
  listStaff: vi.fn(),
  createStaff: vi.fn(),
  disableStaff: vi.fn(),
  grantStaffAuditAuthority: vi.fn(),
  revokeStaffAuditAuthority: vi.fn(),
}))

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof ApiClient>('../api/client')
  return { ...actual, ...apiMocks }
})

const auditor = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'auditor@example.com',
  displayName: '감사 담당자',
  role: 'SECURITY_AUDITOR' as const,
  status: 'ACTIVE' as const,
  memberships: [],
  auditAuthorities: [],
  lastLoginAt: null,
}

describe('AdminStaffPage audit authorities', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows explicit high-risk grants and lets an admin grant then revoke one', async () => {
    const user = userEvent.setup()
    apiMocks.listStaff
      .mockResolvedValueOnce([auditor])
      .mockResolvedValueOnce([
        { ...auditor, auditAuthorities: ['AUDIT_SEARCH_QUERY_REVEAL'] },
      ])
      .mockResolvedValueOnce([auditor])
    apiMocks.grantStaffAuditAuthority.mockResolvedValue(undefined)
    apiMocks.revokeStaffAuditAuthority.mockResolvedValue(undefined)

    render(<AdminStaffPage />)

    await user.click(
      await screen.findByRole('button', {
        name: '검색어 원문 공개 권한 부여',
      }),
    )
    expect(apiMocks.grantStaffAuditAuthority).toHaveBeenCalledWith(
      auditor.id,
      'AUDIT_SEARCH_QUERY_REVEAL',
    )

    await user.click(
      await screen.findByRole('button', {
        name: '검색어 원문 공개 권한 회수',
      }),
    )
    expect(apiMocks.revokeStaffAuditAuthority).toHaveBeenCalledWith(
      auditor.id,
      'AUDIT_SEARCH_QUERY_REVEAL',
    )
    await waitFor(() => expect(apiMocks.listStaff).toHaveBeenCalledTimes(3))
  })
})
