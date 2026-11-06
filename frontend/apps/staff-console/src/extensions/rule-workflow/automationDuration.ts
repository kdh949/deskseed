export const DURATION_UNITS = { DAYS: 1440, HOURS: 60, MINUTES: 1 } as const
export type DurationUnit = keyof typeof DURATION_UNITS

export function durationInput(minutes: number) {
  const unit: DurationUnit =
    minutes % 1440 === 0 ? 'DAYS' : minutes % 60 === 0 ? 'HOURS' : 'MINUTES'
  return { value: String(minutes / DURATION_UNITS[unit]), unit }
}

export function durationMinutes(value: string, unit: DurationUnit): number {
  if (!value.trim()) return NaN
  const minutes = Number(value) * DURATION_UNITS[unit]
  const rounded = Math.round(minutes)
  // A unit switch must preserve an integer minute, including 61 minutes -> hours.
  return Number.isFinite(minutes) && Math.abs(minutes - rounded) < 1e-8
    ? rounded
    : NaN
}

export function durationLabel(minutes: number): string {
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  const remainder = minutes % 60
  return [
    days && `${days}일`,
    hours && `${hours}시간`,
    remainder && `${remainder}분`,
  ]
    .filter(Boolean)
    .join(' ')
}
