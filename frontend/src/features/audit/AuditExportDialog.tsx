import { useMutation } from '@tanstack/react-query'
import { useRef, useState, type FormEvent } from 'react'
import { ApiError, createAuditExport } from '../../api/client'
import type {
  AuditActivityFilters,
  AuditExportJob,
  CreateAuditExportInput,
} from '../../api/types'
import { Notification } from '../../shared/ui/system'
import { TicketActionDialogFrame } from '../ticket-workspace/TicketActionDialogFrame'
import { createAuditInteractionId } from './auditInteraction'

const EXPORT_FIELDS = [
  ['occurredAt', '발생 시각'],
  ['ledger', '원장'],
  ['action', '활동'],
  ['actor', '행위자'],
  ['ticketNumber', '티켓 번호'],
  ['groupId', '그룹 ID'],
  ['field', '필드'],
  ['source', '소스'],
  ['outcome', '결과'],
  ['requestId', '요청 ID'],
  ['correlationId', '상관 ID'],
  ['searchFingerprint', '검색 fingerprint'],
] as const

export function AuditExportDialog({
  filters,
  onClose,
}: {
  filters: AuditActivityFilters
  onClose: () => void
}) {
  const [format, setFormat] = useState<CreateAuditExportInput['format']>('CSV')
  const [reason, setReason] = useState('')
  const [fields, setFields] = useState<string[]>([
    'occurredAt',
    'ledger',
    'action',
    'actor',
    'ticketNumber',
    'source',
    'outcome',
  ])
  const reasonRef = useRef<HTMLTextAreaElement>(null)
  const mutation = useMutation({
    mutationFn: () =>
      createAuditExport(
        { format, filters, fields, reason: reason.trim() },
        createAuditInteractionId(),
      ),
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!reason.trim() || fields.length === 0 || mutation.isPending) return
    mutation.mutate()
  }

  return (
    <TicketActionDialogFrame
      eyebrow="AUDIT EXPORT · REQUEST ONLY"
      title="감사 export 요청"
      description="선택한 필터와 필드, 현재 권한 snapshot을 저장합니다. 이 단계에서는 파일이나 다운로드 링크를 만들지 않습니다."
      initialFocusRef={reasonRef}
      onClose={() => {
        if (!mutation.isPending) onClose()
      }}
    >
      {mutation.isError ? (
        <Notification tone="danger" title="Export 요청을 저장하지 못했습니다.">
          <p>{exportError(mutation.error)}</p>
        </Notification>
      ) : null}
      {mutation.data ? <ExportAccepted job={mutation.data} /> : null}
      {!mutation.data ? (
        <form className="ticket-action-form" onSubmit={submit}>
          <label>
            <span>조사 사유</span>
            <textarea
              ref={reasonRef}
              value={reason}
              maxLength={1000}
              required
              disabled={mutation.isPending}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <label>
            <span>형식</span>
            <select
              value={format}
              disabled={mutation.isPending}
              onChange={(event) =>
                setFormat(event.target.value as CreateAuditExportInput['format'])
              }
            >
              <option value="CSV">CSV</option>
              <option value="JSONL">JSONL</option>
            </select>
          </label>
          <fieldset className="audit-export-fields">
            <legend>포함할 필드</legend>
            {EXPORT_FIELDS.map(([value, label]) => (
              <label key={value}>
                <input
                  type="checkbox"
                  value={value}
                  checked={fields.includes(value)}
                  disabled={mutation.isPending}
                  onChange={(event) =>
                    setFields((current) =>
                      event.target.checked
                        ? [...current, value]
                        : current.filter((field) => field !== value),
                    )
                  }
                />
                <span>{label}</span>
              </label>
            ))}
          </fieldset>
          <div className="ticket-action-form-actions">
            <button
              className="button secondary"
              type="button"
              disabled={mutation.isPending}
              onClick={onClose}
            >
              취소
            </button>
            <button
              className="button primary"
              type="submit"
              aria-busy={mutation.isPending}
              disabled={!reason.trim() || fields.length === 0 || mutation.isPending}
            >
              {mutation.isPending ? '요청 저장 중…' : 'Export 요청 저장'}
            </button>
          </div>
        </form>
      ) : null}
    </TicketActionDialogFrame>
  )
}

function ExportAccepted({ job }: { job: AuditExportJob }) {
  return (
    <Notification tone="success" title="Export 요청이 안전하게 저장되었습니다.">
      <p>
        Job <code>{job.id}</code> · artifact {job.artifact.state}
      </p>
      <p>파일 생성과 다운로드는 후속 기능이며 현재 사용할 수 없습니다.</p>
    </Notification>
  )
}

function exportError(error: Error): string {
  if (error instanceof ApiError && error.status === 403)
    return '현재 세션에 export 권한이 없습니다.'
  return error.message || '잠시 후 다시 시도해 주세요.'
}
