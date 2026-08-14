import { useMemo } from 'react'
import { useSearchParams } from 'react-router'
import type { AuditActivityFilters, AuditLedgerType } from '../../../api/types'

const LEDGERS: AuditLedgerType[] = [
  'TICKET_CHANGE',
  'ACCESS_SEARCH',
  'ADMIN_SECURITY',
]

const OUTCOMES: AuditActivityFilters['outcome'][] = [
  'SUCCEEDED',
  'DENIED',
  'FAILED',
]

const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000

export const FILTER_KEYS = [
  'from',
  'to',
  'ledger',
  'action',
  'actorType',
  'actorId',
  'ticketNumber',
  'groupId',
  'field',
  'source',
  'outcome',
  'requestId',
  'correlationId',
  'searchFingerprint',
] as const

export type AuditFilterKey = (typeof FILTER_KEYS)[number]

export function useAuditActivityFilters() {
  const [searchParams, setSearchParams] = useSearchParams()
  const defaultFrom = useMemo(
    () => new Date(Date.now() - SEVEN_DAYS_MS).toISOString(),
    [],
  )

  const filters = filtersFrom(searchParams, defaultFrom)
  const cursor = searchParams.get('cursor')
  const hasActiveFilters = FILTER_KEYS.some((key) => searchParams.has(key))

  const updateFilter = (key: AuditFilterKey, value: string) => {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(key, value)
    else next.delete(key)
    next.delete('cursor')
    setSearchParams(next)
  }

  const setCursor = (nextCursor: string) => {
    const next = new URLSearchParams(searchParams)
    next.set('cursor', nextCursor)
    setSearchParams(next)
  }

  const clearFilters = () => {
    const next = new URLSearchParams(searchParams)
    FILTER_KEYS.forEach((key) => next.delete(key))
    next.delete('cursor')
    setSearchParams(next)
  }

  return {
    filters,
    cursor,
    hasActiveFilters,
    updateFilter,
    setCursor,
    clearFilters,
  }
}

function filtersFrom(
  searchParams: URLSearchParams,
  defaultFrom: string,
): AuditActivityFilters {
  const ledger = searchParams.get('ledger')
  const outcome = searchParams.get('outcome')
  const actorType = searchParams.get('actorType')
  const ticketNumber = searchParams.get('ticketNumber')
  return {
    from: searchParams.get('from') ?? defaultFrom,
    ...(searchParams.get('to') ? { to: searchParams.get('to')! } : {}),
    ...(ledger && LEDGERS.includes(ledger as AuditLedgerType)
      ? { ledger: ledger as AuditLedgerType }
      : {}),
    ...(searchParams.get('action')
      ? { action: searchParams.get('action')! }
      : {}),
    ...(actorType
      ? { actorType: actorType as AuditActivityFilters['actorType'] }
      : {}),
    ...(searchParams.get('actorId')
      ? { actorId: searchParams.get('actorId')! }
      : {}),
    ...(ticketNumber && /^[1-9]\d*$/.test(ticketNumber)
      ? { ticketNumber: Number(ticketNumber) }
      : {}),
    ...(searchParams.get('groupId')
      ? { groupId: searchParams.get('groupId')! }
      : {}),
    ...(searchParams.get('field') ? { field: searchParams.get('field')! } : {}),
    ...(searchParams.get('source')
      ? { source: searchParams.get('source')! }
      : {}),
    ...(outcome && OUTCOMES.includes(outcome as AuditActivityFilters['outcome'])
      ? { outcome: outcome as AuditActivityFilters['outcome'] }
      : {}),
    ...(searchParams.get('requestId')
      ? { requestId: searchParams.get('requestId')! }
      : {}),
    ...(searchParams.get('correlationId')
      ? { correlationId: searchParams.get('correlationId')! }
      : {}),
    ...(searchParams.get('searchFingerprint')
      ? { searchFingerprint: searchParams.get('searchFingerprint')! }
      : {}),
    limit: 50,
  }
}
