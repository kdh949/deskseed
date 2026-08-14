import { useId, useState, type FormEvent } from 'react'
import type {
  AuditActivityFilters,
  CreateAuditExportInput,
} from '../../api/types'
import { DsButton, DsDrawer, Notification } from '../../design-system'
import type { CreateAuditExportError } from './model/useCreateAuditExport'

export const AUDIT_EXPORT_FIELDS = [
  { value: 'occurredAt', label: '시각' },
  { value: 'ledger', label: '레저' },
  { value: 'action', label: '액션' },
  { value: 'actor', label: '액터' },
  { value: 'ticketNumber', label: '티켓 번호' },
  { value: 'groupId', label: '그룹 ID' },
  { value: 'field', label: '필드' },
  { value: 'source', label: '출처' },
  { value: 'outcome', label: '결과' },
  { value: 'requestId', label: '요청 ID' },
  { value: 'correlationId', label: '상관관계 ID' },
  { value: 'searchFingerprint', label: '검색 지문' },
] as const

const DEFAULT_FIELDS: string[] = [
  'occurredAt',
  'ledger',
  'action',
  'actor',
  'outcome',
]

export interface CreateAuditExportDrawerProps {
  error: CreateAuditExportError | null
  filters: AuditActivityFilters
  onClose: () => void
  onSubmit: (input: CreateAuditExportInput) => void
  open: boolean
  submitting: boolean
}

export function CreateAuditExportDrawer({
  error,
  filters,
  onClose,
  onSubmit,
  open,
  submitting,
}: CreateAuditExportDrawerProps) {
  const [format, setFormat] = useState<CreateAuditExportInput['format']>('CSV')
  const [fields, setFields] = useState<string[]>(DEFAULT_FIELDS)
  const [reason, setReason] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)
  const reasonId = useId()

  const toggleField = (value: string) => {
    setFields((current) =>
      current.includes(value)
        ? current.filter((field) => field !== value)
        : [...current, value],
    )
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    setValidationError(null)
    if (fields.length === 0) {
      setValidationError('내보낼 필드를 하나 이상 선택해 주세요.')
      return
    }
    const trimmedReason = reason.trim()
    if (!trimmedReason) {
      setValidationError('내보내기 사유를 입력해 주세요.')
      return
    }
    onSubmit({ format, filters, fields, reason: trimmedReason })
  }

  return (
    <DsDrawer
      description="현재 필터 조건으로 감사 활동을 내보냅니다."
      onClose={onClose}
      open={open}
      title="감사 활동 내보내기"
    >
      {error ? <Notification title={error.message} tone="danger" /> : null}
      {validationError ? (
        <Notification title={validationError} tone="warning" />
      ) : null}
      <form className="audit-export-form" onSubmit={handleSubmit}>
        <fieldset>
          <legend>형식</legend>
          <label>
            <input
              checked={format === 'CSV'}
              onChange={() => setFormat('CSV')}
              type="radio"
            />
            CSV
          </label>
          <label>
            <input
              checked={format === 'JSONL'}
              onChange={() => setFormat('JSONL')}
              type="radio"
            />
            JSONL
          </label>
        </fieldset>

        <fieldset>
          <legend>필드</legend>
          {AUDIT_EXPORT_FIELDS.map((field) => (
            <label key={field.value}>
              <input
                checked={fields.includes(field.value)}
                onChange={() => toggleField(field.value)}
                type="checkbox"
              />
              {field.label}
            </label>
          ))}
        </fieldset>

        <label className="audit-export-reason" htmlFor={reasonId}>
          <span>사유</span>
          <textarea
            id={reasonId}
            maxLength={1000}
            onChange={(event) => setReason(event.target.value)}
            rows={3}
            value={reason}
          />
        </label>

        <footer className="audit-export-actions">
          <DsButton onClick={onClose} type="button">
            취소
          </DsButton>
          <DsButton disabled={submitting} tone="primary" type="submit">
            {submitting ? '요청 중…' : '내보내기 요청'}
          </DsButton>
        </footer>
      </form>
    </DsDrawer>
  )
}
