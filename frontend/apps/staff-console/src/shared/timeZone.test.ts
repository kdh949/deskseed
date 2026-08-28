import { describe, expect, it } from 'vitest'
import { localDateTimeToInstant } from './timeZone'

describe('localDateTimeToInstant', () => {
  it('interprets a wall-clock input in the selected IANA timezone', () => {
    expect(localDateTimeToInstant('2026-08-14T17:00', 'Asia/Seoul')).toBe(
      '2026-08-14T08:00:00.000Z',
    )
  })

  it('uses the earlier instant in a DST overlap and shifts gaps forward', () => {
    expect(localDateTimeToInstant('2026-11-01T01:30', 'America/New_York')).toBe(
      '2026-11-01T05:30:00.000Z',
    )
    expect(localDateTimeToInstant('2026-03-08T02:30', 'America/New_York')).toBe(
      '2026-03-08T07:30:00.000Z',
    )
  })
})
