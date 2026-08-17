import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { getAuditExport } from '../../../api/client'
import type { AuditExportJob } from '../../../api/types'
import { createOpaqueUuid } from '../../../api/uuid'

const POLL_INTERVAL_MS = 3000

export function useAuditExportStatus(jobId: string) {
  // Reuse one deliberate-read interaction across this route mount. Unmounting
  // disposes the query observer, which also stops its polling timer.
  const interactionId = useMemo(createOpaqueUuid, [jobId])
  const query = useQuery({
    queryKey: ['audit-export', jobId, interactionId],
    queryFn: () => getAuditExport(jobId, interactionId),
    retry: false,
    refetchInterval: (currentQuery) => {
      if (currentQuery.state.status === 'error') return false
      return isTerminal(currentQuery.state.data) ? false : POLL_INTERVAL_MS
    },
  })
  const polling =
    query.isPending || (query.data !== undefined && !isTerminal(query.data))

  return { ...query, polling, refresh: () => query.refetch() }
}

function isTerminal(job: AuditExportJob | undefined) {
  return (
    job?.status === 'READY' ||
    job?.status === 'FAILED' ||
    job?.status === 'EXPIRED'
  )
}
