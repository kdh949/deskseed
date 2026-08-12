import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type * as ApiClient from '../api/client'
import type { BusinessSchedule, FirstReplySlaPolicy } from '../api/types'
import { AdminFirstReplySlaPage } from './AdminFirstReplySlaPage'

const apiMocks = vi.hoisted(() => ({
  activateFirstReplySlaPolicyVersion: vi.fn(),
  createFirstReplySlaPolicyVersion: vi.fn(),
  getFirstReplySlaAnalytics: vi.fn(),
  listBusinessSchedules: vi.fn(),
  listFirstReplySlaPolicies: vi.fn(),
  listFirstReplySlaPolicyVersions: vi.fn(),
  previewFirstReplySlaPolicy: vi.fn(),
}))

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof ApiClient>('../api/client')
  return { ...actual, ...apiMocks }
})

const schedule: BusinessSchedule = {
  id: '51000000-0000-0000-0000-000000000001',
  name: 'Default Support Hours',
  timeZone: 'Asia/Seoul',
  weekdays: (
    [
      'MONDAY',
      'TUESDAY',
      'WEDNESDAY',
      'THURSDAY',
      'FRIDAY',
      'SATURDAY',
      'SUNDAY',
    ] as const
  ).map((weekday, index) => ({
    weekday,
    enabled: index < 5,
    intervals: index < 5 ? [{ start: '09:00', end: '18:00' }] : [],
  })),
  exceptions: [],
  version: 1,
  activeVersion: 1,
  activeTimeZone: 'Asia/Seoul',
  aggregateVersion: 0,
  active: true,
  createdAt: '2026-08-10T00:00:00Z',
  createdBy: {
    actorType: 'SYSTEM',
    actorId: null,
    displayName: 'Deskseed seed',
  },
}

const policy: FirstReplySlaPolicy = {
  id: '62000000-0000-0000-0000-000000000001',
  name: '기본 First Reply',
  position: 10,
  scheduleId: schedule.id,
  scheduleVersion: 1,
  conditions: { groupId: null, channel: 'WEB' },
  targets: { LOW: 480, NORMAL: 240, HIGH: 120, URGENT: 60 },
  pauseStatuses: ['PENDING'],
  version: 1,
  activeVersion: null,
  aggregateVersion: 0,
  active: false,
  createdAt: '2026-08-12T00:00:00Z',
  createdBy: {
    actorType: 'STAFF',
    actorId: '61000000-0000-0000-0000-000000000001',
    displayName: 'SLA 관리자',
  },
}

describe('AdminFirstReplySlaPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.listBusinessSchedules.mockResolvedValue([schedule])
    apiMocks.listFirstReplySlaPolicies.mockResolvedValue([policy])
    apiMocks.listFirstReplySlaPolicyVersions.mockResolvedValue([policy])
    apiMocks.getFirstReplySlaAnalytics.mockResolvedValue({
      metric: 'FIRST_REPLY',
      calculationVersion: 'FIRST_REPLY_BUSINESS_TIME_V1',
      active: 3,
      paused: 1,
      achieved: 8,
      breached: 2,
      cancelled: 1,
      noPolicy: 4,
      achievedRateDenominator: 10,
      achievedRate: 0.8,
    })
    apiMocks.previewFirstReplySlaPolicy.mockResolvedValue({
      matched: true,
      dueAt: '2026-08-17T03:00:00Z',
      targetMinutes: 180,
      policyId: null,
      policyVersion: null,
      scheduleId: schedule.id,
      scheduleVersion: 1,
      dstPolicy: 'GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH',
    })
    apiMocks.activateFirstReplySlaPolicyVersion.mockResolvedValue({
      ...policy,
      active: true,
      aggregateVersion: 1,
    })
  })

  it('previews an unsaved ordered policy and activates an immutable version', async () => {
    const user = userEvent.setup()
    render(<AdminFirstReplySlaPage />)

    expect(
      await screen.findByRole('heading', { name: 'First Reply SLA 정책' }),
    ).toBeVisible()
    expect(screen.getByText('80%')).toBeVisible()
    expect(screen.getByText('NO_POLICY')).toBeVisible()
    expect(screen.getByDisplayValue('기본 First Reply')).toBeVisible()
    expect(screen.getByLabelText('PENDING')).toBeChecked()
    expect(screen.queryByLabelText('SOLVED')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('CLOSED')).not.toBeInTheDocument()
    await user.type(
      screen.getByLabelText('Sample group ID'),
      '71000000-0000-0000-0000-000000000001',
    )
    await user.clear(screen.getByLabelText('NORMAL target minutes'))
    await user.type(screen.getByLabelText('NORMAL target minutes'), '180')
    await user.click(screen.getByRole('button', { name: '선택·기한 preview' }))

    await waitFor(() =>
      expect(apiMocks.previewFirstReplySlaPolicy).toHaveBeenCalledTimes(1),
    )
    expect(
      apiMocks.previewFirstReplySlaPolicy.mock.calls[0]![0].candidate.targets
        .NORMAL,
    ).toBe(180)
    expect(apiMocks.previewFirstReplySlaPolicy.mock.calls[0]![0]).toEqual(
      expect.objectContaining({
        candidatePolicyId: policy.id,
        startAt: '2026-08-14T09:30:00.000Z',
        ticket: expect.objectContaining({
          groupId: '71000000-0000-0000-0000-000000000001',
        }),
      }),
    )
    expect(screen.getByText('Asia/Seoul 기준')).toBeVisible()
    expect(await screen.findByText('180분')).toBeVisible()

    await user.click(screen.getByRole('button', { name: '이 버전 활성화' }))
    expect(apiMocks.activateFirstReplySlaPolicyVersion).toHaveBeenCalledWith(
      policy.id,
      1,
      0,
    )
    expect(await screen.findByText('정책 v1이 활성화되었습니다.')).toBeVisible()
  })

  it('shows active policy and schedule versions separately from latest drafts', async () => {
    const latestPolicy = {
      ...policy,
      version: 2,
      activeVersion: 1,
      active: false,
    }
    const latestSchedule = {
      ...schedule,
      version: 2,
      activeVersion: 1,
      active: false,
    }
    apiMocks.listFirstReplySlaPolicies.mockResolvedValue([latestPolicy])
    apiMocks.listFirstReplySlaPolicyVersions.mockResolvedValue([
      latestPolicy,
      { ...policy, activeVersion: 1, active: true },
    ])
    apiMocks.listBusinessSchedules.mockResolvedValue([latestSchedule])

    render(<AdminFirstReplySlaPage />)

    expect(await screen.findByText('활성 v1 · 최신 v2 초안')).toBeVisible()
    expect(
      screen.getByRole('option', {
        name: 'Default Support Hours · active v1 · latest v2 draft',
      }),
    ).toBeInTheDocument()
  })
})
