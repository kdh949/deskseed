import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { listAuditActivities } from '../../../api/client'
import { createOpaqueUuid } from '../../../api/uuid'
import type { AuditActivityFilters } from '../../../api/types'

export function useAuditActivities(
  filters: AuditActivityFilters,
  cursor: string | null,
) {
  const interactionId = useMemo(createOpaqueUuid, [])
  return useQuery({
    queryKey: ['audit-activities', filters, cursor],
    queryFn: () => listAuditActivities(filters, cursor, interactionId),
  })
}
