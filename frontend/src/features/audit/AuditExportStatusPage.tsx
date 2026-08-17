import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { ApiError, downloadAuditExport } from '../../api/client'
import { createOpaqueUuid } from '../../api/uuid'
import {
  AuditExportStatus,
  type AuditExportStatusState,
} from './AuditExportStatus'
import { useAuditExportStatus } from './model/useAuditExportStatus'

export function AuditExportStatusPage() {
  const { jobId = '' } = useParams()
  const navigate = useNavigate()
  const query = useAuditExportStatus(jobId)
  const [downloading, setDownloading] = useState(false)
  const [downloadError, setDownloadError] = useState<string | undefined>()

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
      : { status: 'ready', job: query.data, polling: query.polling }

  return (
    <AuditExportStatus
      downloading={downloading}
      downloadError={downloadError}
      onDownload={() => {
        if (downloading) return
        void download(jobId)
      }}
      onRefresh={query.refresh}
      onRegenerate={() => navigate('/agent/audit')}
      onRetry={() => query.refetch()}
      state={state}
    />
  )

  async function download(exportJobId: string) {
    setDownloading(true)
    setDownloadError(undefined)
    try {
      const artifact = await downloadAuditExport(
        exportJobId,
        createOpaqueUuid(),
      )
      const url = URL.createObjectURL(artifact.content)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download =
        artifact.fileName ??
        `deskseed-audit-export.${artifact.contentType === 'text/csv' ? 'csv' : 'jsonl'}`
      document.body.append(anchor)
      anchor.click()
      anchor.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 0)
    } catch (error) {
      setDownloadError(
        error instanceof ApiError
          ? error.message
          : '보호된 내보내기 파일을 다운로드하지 못했습니다.',
      )
    } finally {
      setDownloading(false)
    }
  }
}
