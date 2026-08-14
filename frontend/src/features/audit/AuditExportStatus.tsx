import type { AuditExportJob } from '../../api/types'
import { DsButton, ScreenState } from '../../design-system'

export type AuditExportStatusState =
  | { status: 'loading' }
  | { status: 'not-found' }
  | { status: 'denied' }
  | { status: 'error'; requestId?: string }
  | { status: 'ready'; job: AuditExportJob; polling: boolean }

export interface AuditExportStatusProps {
  onRefresh: () => void
  onRetry: () => void
  state: AuditExportStatusState
}

const FORMAT_LABELS: Record<AuditExportJob['format'], string> = {
  CSV: 'CSV',
  JSONL: 'JSONL',
}

export function AuditExportStatus({
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
      </dl>
      <ScreenState
        action={
          !state.polling ? (
            <DsButton onClick={onRefresh}>새로고침</DsButton>
          ) : undefined
        }
        compact
        kind="loading"
        title="생성 중…"
      />
    </main>
  )
}

function formatCreatedAt(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
