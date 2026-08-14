import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { listAuditActivities } from '../../../api/client'
import { createOpaqueUuid } from '../../../api/uuid'
import type { AuditActivityFilters } from '../../../api/types'

export function useAuditActivities(
  filters: AuditActivityFilters,
  cursor: string | null,
) {
  const filterSignature = JSON.stringify(filters)
  // A new interaction represents a new investigation: minted per distinct
  // filter set (or fresh mount), reused across cursor pagination and
  // background refetches of the same investigation.
  const interactionId = useMemo(createOpaqueUuid, [filterSignature])
  return useQuery({
    queryKey: ['audit-activities', filters, cursor, interactionId],
    queryFn: () => listAuditActivities(filters, cursor, interactionId),
  })
}
