import { useQuery } from '@tanstack/react-query'
import { useMemo, useRef, useState } from 'react'
import { getAuditExport } from '../../../api/client'
import { createOpaqueUuid } from '../../../api/uuid'

const MAX_POLL_ATTEMPTS = 5
const POLL_INTERVAL_MS = 3000

export function useAuditExportStatus(jobId: string) {
  // Stable for the lifetime of this route mount; reused across background
  // polling of the same job, but a fresh mount always mints a new one so a
  // cached response can't be served without a corresponding self-audit call.
  const interactionId = useMemo(createOpaqueUuid, [jobId])
  const attemptsRef = useRef(0)
  const [pollingExhausted, setPollingExhausted] = useState(false)

  const query = useQuery({
    queryKey: ['audit-export', jobId, interactionId],
    queryFn: async () => {
      try {
        const result = await getAuditExport(jobId, interactionId)
        attemptsRef.current += 1
        if (attemptsRef.current >= MAX_POLL_ATTEMPTS) setPollingExhausted(true)
        return result
      } catch (error) {
        // Count every real polling cycle, not just successes, and stop
        // immediately on failure rather than retrying a stable error every
        // 3 seconds indefinitely.
        attemptsRef.current += 1
        setPollingExhausted(true)
        throw error
      }
    },
    refetchInterval: pollingExhausted ? false : POLL_INTERVAL_MS,
  })

  const refresh = () => {
    attemptsRef.current = 0
    setPollingExhausted(false)
    query.refetch()
  }

  return { ...query, pollingExhausted, refresh }
}
