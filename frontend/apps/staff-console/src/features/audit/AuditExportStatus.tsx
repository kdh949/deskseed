import type { AuditExportJob } from '../../api/types'
import { DsButton, ScreenState } from '../../design-system'

export type AuditExportStatusState =
  | { status: 'loading' }
  | { status: 'not-found' }
  | { status: 'denied' }
  | { status: 'error'; requestId?: string }
  | { status: 'ready'; job: AuditExportJob; polling: boolean }

export interface AuditExportStatusProps {
  downloading?: boolean
  downloadError?: string
  onDownload: () => void
  onRegenerate: () => void
  onRefresh: () => void
  onRetry: () => void
  state: AuditExportStatusState
}

const FORMAT_LABELS: Record<AuditExportJob['format'], string> = {
  CSV: 'CSV',
  JSONL: 'JSONL',
}

export function AuditExportStatus({
  downloading = false,
  downloadError,
  onDownload,
  onRegenerate,
  onRefresh,
  onRetry,
  state,
}: AuditExportStatusProps) {
  if (state.status === 'loading') {
    return (
      <main className="audit-export-status">
        <ScreenState
          kind="loading"
          title="내보내기 작업 정보를 불러오고 있습니다."
        />
      </main>
    )
  }
  if (state.status === 'not-found') {
    return (
      <main className="audit-export-status">
        <ScreenState
          description="요청하신 내보내기 작업이 존재하지 않거나 접근할 수 없습니다."
          kind="not-found"
          title="내보내기 작업을 찾을 수 없습니다."
        />
      </main>
    )
  }
  if (state.status === 'denied') {
    return (
      <main className="audit-export-status">
        <ScreenState
          description="이 작업을 조회할 권한이 없습니다."
          kind="denied"
          title="내보내기 작업에 접근할 수 없습니다."
        />
      </main>
    )
  }
  if (state.status === 'error') {
    return (
      <main className="audit-export-status">
        <ScreenState
          action={<DsButton onClick={onRetry}>다시 시도</DsButton>}
          description={
            state.requestId ? `요청 ID: ${state.requestId}` : undefined
          }
          kind="error"
          title="내보내기 작업 상태를 불러오지 못했습니다."
        />
      </main>
    )
  }

  const { job } = state
  const terminal =
    job.status === 'READY' ||
    job.status === 'FAILED' ||
    job.status === 'EXPIRED'
  const readyForDownload =
    job.status === 'READY' && job.artifact.state === 'READY'
  const expired =
    job.status === 'EXPIRED' ||
    job.artifact.state === 'EXPIRED' ||
    job.artifact.state === 'DELETED'
  return (
    <main className="audit-export-status" aria-label="감사 내보내기 작업 상태">
      <header>
        <h1>내보내기 작업 상태</h1>
        <p>{FORMAT_LABELS[job.format]} 형식으로 요청됨</p>
      </header>
      <dl className="audit-export-status-summary">
        <div>
          <dt>작업 ID</dt>
          <dd>{job.id}</dd>
        </div>
        <div>
          <dt>요청 시각</dt>
          <dd>{formatCreatedAt(job.createdAt)}</dd>
        </div>
        <div>
          <dt>필드</dt>
          <dd>{job.fields.join(', ')}</dd>
        </div>
        <div>
          <dt>상태</dt>
          <dd>{statusLabel(job.status)}</dd>
        </div>
      </dl>
      {readyForDownload ? (
        <section
          aria-label="준비된 내보내기 파일"
          className="audit-export-ready"
        >
          <p>파일이 준비되었습니다.</p>
          <dl className="audit-export-status-summary">
            <div>
              <dt>행 수</dt>
              <dd>{job.artifact.rowCount?.toLocaleString('ko-KR') ?? '-'}</dd>
            </div>
            <div>
              <dt>파일 크기</dt>
              <dd>{formatSize(job.artifact.sizeBytes)}</dd>
            </div>
            <div>
              <dt>만료 시각</dt>
              <dd>
                {job.artifact.expiresAt
                  ? formatCreatedAt(job.artifact.expiresAt)
                  : '-'}
              </dd>
            </div>
          </dl>
          <DsButton disabled={downloading} onClick={onDownload} tone="primary">
            {downloading ? '다운로드 준비 중…' : '다운로드'}
          </DsButton>
          {downloadError ? (
            <ScreenState
              compact
              description={downloadError}
              kind="error"
              title="파일을 다운로드하지 못했습니다."
            />
          ) : null}
        </section>
      ) : expired ? (
        <ScreenState
          action={<DsButton onClick={onRegenerate}>새 내보내기 요청</DsButton>}
          compact
          description="보호된 내보내기 artifact는 만료 또는 삭제되어 다시 다운로드할 수 없습니다."
          kind="stale"
          title="내보내기 파일이 만료되었습니다."
        />
      ) : job.status === 'FAILED' || job.artifact.state === 'FAILED' ? (
        <ScreenState
          action={<DsButton onClick={onRefresh}>상태 새로고침</DsButton>}
          compact
          description={
            job.artifact.failureCode
              ? `생성 실패 코드: ${job.artifact.failureCode}`
              : '파일 생성이 완료되지 않았습니다.'
          }
          kind="error"
          title="내보내기 파일 생성에 실패했습니다."
        />
      ) : (
        <ScreenState
          action={
            !state.polling || terminal ? (
              <DsButton onClick={onRefresh}>새로고침</DsButton>
            ) : undefined
          }
          compact
          kind="loading"
          title="생성 중…"
        />
      )}
    </main>
  )
}

function formatCreatedAt(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function formatSize(value: number | null) {
  if (value === null) return '-'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${Math.ceil(value / 1024)} KB`
  return `${(value / (1024 * 1024)).toFixed(1)} MB`
}

function statusLabel(status: AuditExportJob['status']) {
  const labels: Record<AuditExportJob['status'], string> = {
    REQUESTED: '요청됨',
    RUNNING: '생성 중',
    READY: '준비됨',
    FAILED: '실패',
    EXPIRED: '만료됨',
  }
  return labels[status]
}
