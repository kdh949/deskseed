import { describe, expect, it } from 'vitest'
import {
  DURATION_UNITS,
  durationInput,
  durationLabel,
  durationMinutes,
} from './automationDuration'

describe('automation duration input', () => {
  it('keeps the exact minute value through every selectable unit', () => {
    for (const minutes of [1, 59, 60, 61, 1439, 1440, 1501, 525600]) {
      for (const unit of Object.keys(DURATION_UNITS) as Array<
        keyof typeof DURATION_UNITS
      >) {
        expect(
          durationMinutes(String(minutes / DURATION_UNITS[unit]), unit),
        ).toBe(minutes)
      }
    }
    expect(durationInput(1440)).toEqual({ value: '1', unit: 'DAYS' })
    expect(durationInput(61)).toEqual({ value: '61', unit: 'MINUTES' })
  })
  it('accepts exact fractional hours while rejecting partial minutes and empty input', () => {
    expect(durationMinutes('1.5', 'HOURS')).toBe(90)
    expect(durationMinutes('1.001', 'HOURS')).toBeNaN()
    expect(durationMinutes('', 'MINUTES')).toBeNaN()
    expect(durationMinutes('Infinity', 'MINUTES')).toBeNaN()
    expect(durationLabel(1501)).toBe('1일 1시간 1분')
  })
})
