import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { ApiError, getAuditExport } from '../../../api/client'
import type { AuditExportJob } from '../../../api/types'
import { createOpaqueUuid } from '../../../api/uuid'

const POLL_INTERVAL_MS = 3000
const MAX_POLL_INTERVAL_MS = 30_000

export function useAuditExportStatus(jobId: string) {
  // Reuse one deliberate-read interaction across this route mount. Unmounting
  // disposes the query observer, which also stops its polling timer.
  const interactionId = useMemo(createOpaqueUuid, [jobId])
  const query = useQuery({
    queryKey: ['audit-export', jobId, interactionId],
    queryFn: () => getAuditExport(jobId, interactionId),
    retry: false,
    refetchInterval: (currentQuery) => {
      if (isTerminal(currentQuery.state.data)) return false
      if (isDefiniteError(currentQuery.state.error)) return false
      return Math.min(
        MAX_POLL_INTERVAL_MS,
        POLL_INTERVAL_MS * 2 ** currentQuery.state.fetchFailureCount,
      )
    },
  })
  const polling =
    !isDefiniteError(query.error) &&
    (query.isPending || !isTerminal(query.data))

  return { ...query, polling, refresh: () => query.refetch() }
}

function isDefiniteError(error: unknown) {
  return (
    error instanceof ApiError &&
    (error.status === 401 || error.status === 403 || error.status === 404)
  )
}

function isTerminal(job: AuditExportJob | undefined) {
  return (
    job?.status === 'READY' ||
    job?.status === 'FAILED' ||
    job?.status === 'EXPIRED'
  )
}
