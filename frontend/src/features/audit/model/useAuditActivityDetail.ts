import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { getAuditActivity } from '../../../api/client'
import { createOpaqueUuid } from '../../../api/uuid'

export function useAuditActivityDetail(activityId: string | null) {
  const interactionId = useMemo(createOpaqueUuid, [activityId])
  return useQuery({
    enabled: activityId !== null,
    queryKey: ['audit-activity', activityId],
    queryFn: () => getAuditActivity(activityId!, interactionId),
  })
}
