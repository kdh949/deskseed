import { useParams } from 'react-router'
import { ApiError } from '../../api/client'
import {
  AuditExportStatus,
  type AuditExportStatusState,
} from './AuditExportStatus'
import { useAuditExportStatus } from './model/useAuditExportStatus'

export function AuditExportStatusPage() {
  const { jobId = '' } = useParams()
  const query = useAuditExportStatus(jobId)

  const state: AuditExportStatusState = query.isPending
    ? { status: 'loading' }
    : query.isError
      ? query.error instanceof ApiError && query.error.status === 404
        ? { status: 'not-found' }
        : query.error instanceof ApiError && query.error.status === 403
          ? { status: 'denied' }
          : {
              status: 'error',
              requestId:
                query.error instanceof ApiError
                  ? query.error.requestId
                  : undefined,
            }
      : { status: 'ready', job: query.data, polling: !query.pollingExhausted }

  return (
    <AuditExportStatus
      onRefresh={query.refresh}
      onRetry={() => query.refetch()}
      state={state}
    />
  )
}
