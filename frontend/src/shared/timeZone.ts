const formatterCache = new Map<string, Intl.DateTimeFormat>()

interface WallClock {
  year: number
  month: number
  day: number
  hour: number
  minute: number
}

function formatter(timeZone: string) {
  const cached = formatterCache.get(timeZone)
  if (cached) return cached
  const created = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  })
  formatterCache.set(timeZone, created)
  return created
}

function parseWallClock(value: string): WallClock {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value)
  if (!match) throw new RangeError('Use a complete local date and time.')
  const [, year, month, day, hour, minute] = match
  return {
    year: Number(year),
    month: Number(month),
    day: Number(day),
    hour: Number(hour),
    minute: Number(minute),
  }
}

function partsAt(epochMilliseconds: number, timeZone: string): WallClock {
  const values = Object.fromEntries(
    formatter(timeZone)
      .formatToParts(new Date(epochMilliseconds))
      .filter((part) => part.type !== 'literal')
      .map((part) => [part.type, Number(part.value)]),
  )
  const value = (name: string) => {
    const parsed = values[name]
    if (parsed === undefined) throw new RangeError('Unable to read timezone.')
    return parsed
  }
  return {
    year: value('year'),
    month: value('month'),
    day: value('day'),
    hour: value('hour'),
    minute: value('minute'),
  }
}

function asUtc(wallClock: WallClock) {
  return Date.UTC(
    wallClock.year,
    wallClock.month - 1,
    wallClock.day,
    wallClock.hour,
    wallClock.minute,
  )
}

function matches(
  epochMilliseconds: number,
  expected: WallClock,
  timeZone: string,
) {
  const actual = partsAt(epochMilliseconds, timeZone)
  return (
    actual.year === expected.year &&
    actual.month === expected.month &&
    actual.day === expected.day &&
    actual.hour === expected.hour &&
    actual.minute === expected.minute
  )
}

function candidateOffsets(naiveUtc: number, timeZone: string) {
  const offsets = new Set<number>()
  for (let hours = -48; hours <= 48; hours += 6) {
    const sample = naiveUtc + hours * 60 * 60 * 1000
    offsets.add(asUtc(partsAt(sample, timeZone)) - sample)
  }
  return [...offsets]
}

function matchingInstants(
  wallClock: WallClock,
  timeZone: string,
  offsets: number[],
) {
  const naiveUtc = asUtc(wallClock)
  return offsets
    .map((offset) => naiveUtc - offset)
    .filter((candidate) => matches(candidate, wallClock, timeZone))
    .sort((left, right) => left - right)
}

/**
 * Converts an offset-free datetime-local value using the schedule timezone.
 * DST overlaps choose the earlier instant. Gap values move by the transition
 * duration, matching the backend GAP_SHIFT_FORWARD policy.
 */
export function localDateTimeToInstant(value: string, timeZone: string) {
  const wallClock = parseWallClock(value)
  const naiveUtc = asUtc(wallClock)
  const offsets = candidateOffsets(naiveUtc, timeZone)
  const exact = matchingInstants(wallClock, timeZone, offsets)
  if (exact.length > 0) return new Date(exact[0]!).toISOString()

  const gapDuration = Math.max(...offsets) - Math.min(...offsets)
  if (gapDuration > 0) {
    const shifted = partsAt(naiveUtc + gapDuration, 'UTC')
    const afterGap = matchingInstants(shifted, timeZone, offsets)
    if (afterGap.length > 0) return new Date(afterGap[0]!).toISOString()
  }
  throw new RangeError(`The local date and time is invalid in ${timeZone}.`)
}
