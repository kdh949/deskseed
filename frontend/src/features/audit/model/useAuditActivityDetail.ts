import { useQuery } from '@tanstack/react-query'
import { getAuditActivity } from '../../../api/client'

export function useAuditActivityDetail(
  activityId: string | null,
  interactionId: string | null,
) {
  return useQuery({
    enabled: activityId !== null && interactionId !== null,
    queryKey: ['audit-activity', activityId, interactionId],
    queryFn: () => getAuditActivity(activityId!, interactionId!),
  })
}
