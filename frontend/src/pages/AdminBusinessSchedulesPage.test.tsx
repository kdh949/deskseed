import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type * as ApiClient from '../api/client'
import type { BusinessSchedule } from '../api/types'
import { AdminBusinessSchedulesPage } from './AdminBusinessSchedulesPage'

const apiMocks = vi.hoisted(() => ({
  activateBusinessScheduleVersion: vi.fn(),
  createBusinessScheduleVersion: vi.fn(),
  listBusinessScheduleVersions: vi.fn(),
  listBusinessSchedules: vi.fn(),
  previewBusinessSchedule: vi.fn(),
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

describe('AdminBusinessSchedulesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.listBusinessSchedules.mockResolvedValue([schedule])
    apiMocks.listBusinessScheduleVersions.mockResolvedValue([schedule])
    apiMocks.previewBusinessSchedule.mockResolvedValue({
      dueAt: '2026-08-17T01:00:00Z',
      elapsedBusinessMinutes: 120,
      nextOpenAt: '2026-08-14T08:00:00Z',
      nextCloseAt: '2026-08-14T09:00:00Z',
      dstPolicy: 'GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH',
    })
  })

  it('edits weekend intervals and previews the unsaved definition', async () => {
    const user = userEvent.setup()
    render(<AdminBusinessSchedulesPage />)

    expect(
      await screen.findByRole('heading', { name: '업무 시간 일정' }),
    ).toBeVisible()
    expect(screen.getByDisplayValue('Asia/Seoul')).toBeVisible()
    await user.click(screen.getByRole('checkbox', { name: '토요일 영업' }))
    expect(screen.getByLabelText('토요일 구간 1 시작')).toHaveValue('09:00')

    await user.click(
      screen.getByRole('button', { name: '토요일 시간 구간 추가' }),
    )
    await user.clear(screen.getByLabelText('토요일 구간 2 시작'))
    await user.type(screen.getByLabelText('토요일 구간 2 시작'), '19:00')
    await user.clear(screen.getByLabelText('토요일 구간 2 종료'))
    await user.type(screen.getByLabelText('토요일 구간 2 종료'), '21:00')
    await user.click(
      screen.getByRole('button', { name: '미저장 일정 미리보기' }),
    )

    await waitFor(() =>
      expect(apiMocks.previewBusinessSchedule).toHaveBeenCalledTimes(1),
    )
    const request = apiMocks.previewBusinessSchedule.mock.calls[0]![0]
    expect(request.startAt).toBe('2026-08-14T08:00:00.000Z')
    expect(request.endAt).toBe('2026-08-17T01:00:00.000Z')
    expect(request.schedule.weekdays[5]).toEqual({
      weekday: 'SATURDAY',
      enabled: true,
      intervals: [
        { start: '09:00', end: '18:00' },
        { start: '19:00', end: '21:00' },
      ],
    })
    expect(await screen.findByText('2026. 8. 17. 오전 10:00')).toBeVisible()
    expect(screen.getByText('120분')).toBeVisible()
  })

  it('shows the active version separately from the latest draft', async () => {
    const latestDraft = {
      ...schedule,
      version: 2,
      activeVersion: 1,
      active: false,
    }
    apiMocks.listBusinessSchedules.mockResolvedValue([latestDraft])
    apiMocks.listBusinessScheduleVersions.mockResolvedValue([
      latestDraft,
      schedule,
    ])

    render(<AdminBusinessSchedulesPage />)

    expect(await screen.findByText('활성 v1 · 최신 v2 초안')).toBeVisible()
  })

  it('saves an immutable version then activates it with the latest aggregate version', async () => {
    const user = userEvent.setup()
    const versionTwo = {
      ...schedule,
      version: 2,
      aggregateVersion: 1,
      active: false,
    }
    const activeVersionTwo = {
      ...versionTwo,
      aggregateVersion: 2,
      active: true,
    }
    apiMocks.createBusinessScheduleVersion.mockResolvedValue(versionTwo)
    apiMocks.activateBusinessScheduleVersion.mockResolvedValue(activeVersionTwo)
    apiMocks.listBusinessSchedules
      .mockResolvedValueOnce([schedule])
      .mockResolvedValueOnce([versionTwo])
      .mockResolvedValueOnce([activeVersionTwo])
    apiMocks.listBusinessScheduleVersions
      .mockResolvedValueOnce([schedule])
      .mockResolvedValueOnce([versionTwo, schedule])
      .mockResolvedValueOnce([activeVersionTwo, schedule])

    render(<AdminBusinessSchedulesPage />)
    await screen.findByRole('heading', { name: '업무 시간 일정' })
    await user.click(screen.getByRole('button', { name: '새 버전 저장' }))

    await waitFor(() =>
      expect(apiMocks.createBusinessScheduleVersion).toHaveBeenCalledWith(
        schedule.id,
        0,
        expect.objectContaining({ name: schedule.name }),
      ),
    )
    expect(await screen.findByText('버전 2가 저장되었습니다.')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '버전 2 활성화' }))
    expect(apiMocks.activateBusinessScheduleVersion).toHaveBeenCalledWith(
      schedule.id,
      2,
      1,
    )
    expect(await screen.findByText('버전 2가 활성화되었습니다.')).toBeVisible()
  })
})
