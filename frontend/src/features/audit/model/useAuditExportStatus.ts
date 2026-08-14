import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { getAuditExport } from '../../../api/client'
import { createOpaqueUuid } from '../../../api/uuid'

const MAX_POLL_ATTEMPTS = 5
const POLL_INTERVAL_MS = 3000

export function useAuditExportStatus(jobId: string) {
  const interactionId = useMemo(createOpaqueUuid, [jobId])
  const attemptsRef = useRef(0)
  const [pollingExhausted, setPollingExhausted] = useState(false)

  const query = useQuery({
    queryKey: ['audit-export', jobId],
    queryFn: () => getAuditExport(jobId, interactionId),
    refetchInterval: pollingExhausted ? false : POLL_INTERVAL_MS,
  })

  useEffect(() => {
    if (query.dataUpdatedAt === 0) return
    attemptsRef.current += 1
    if (attemptsRef.current >= MAX_POLL_ATTEMPTS) setPollingExhausted(true)
  }, [query.dataUpdatedAt])

  const refresh = () => {
    attemptsRef.current = 0
    setPollingExhausted(false)
    query.refetch()
  }

  return { ...query, pollingExhausted, refresh }
}
