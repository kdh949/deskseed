import {
  cloneElement,
  useEffect,
  useId,
  useState,
  type Dispatch,
  type FormEvent,
  type ReactElement,
  type SetStateAction,
} from 'react'
import { ApiError } from '../../api/client'
import type { SubmitRequestInput, SubmittedRequest } from '../../api/types'
import { DsButton, Notification } from '../../design-system'
import {
  EMPTY_REQUEST_FORM,
  validateRequestForm,
  type RequestField,
  type RequestFieldErrors,
} from './requestForm'
import { MAX_ATTACHMENTS } from '../attachments/attachmentPolicy'

export function CustomerRequestForm({
  onSubmitted,
  submit,
}: {
  onSubmitted: (submitted: SubmittedRequest) => void
  submit: (
    input: SubmitRequestInput,
    files?: File[],
  ) => Promise<SubmittedRequest>
}) {
  const [form, setForm] = useState(EMPTY_REQUEST_FORM)
  const [touched, setTouched] = useState<Set<RequestField>>(new Set())
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)
  const [files, setFiles] = useState<File[]>([])
  const [attachmentLimitError, setAttachmentLimitError] = useState(false)
  const errors = validateRequestForm(form)
  const valid = Object.keys(errors).length === 0
  const nameId = useId()
  const emailId = useId()
  const subjectId = useId()
  const messageId = useId()
  const attachmentsId = useId()

  useEffect(() => {
    if (!submitting || files.length === 0) return
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [files.length, submitting])

  const updateField = (field: RequestField, value: string) => {
    setForm((current) => ({ ...current, [field]: value }))
    setSubmitError(null)
  }

  const submitRequest = async () => {
    setTouched(new Set(['name', 'email', 'subject', 'message']))
    if (!valid || submitting) return
    setSubmitting(true)
    setSubmitError(null)
    try {
      onSubmitted(await (files.length ? submit(form, files) : submit(form)))
    } catch (error) {
      setSubmitError(toSubmitError(error))
    } finally {
      setSubmitting(false)
    }
  }

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    void submitRequest()
  }

  return (
    <div className="customer-page">
      <header className="customer-page-header">
        <p className="customer-page-eyebrow">고객 지원</p>
        <h1>문의하기</h1>
        <p>문의 내용을 남겨 주시면 공개 대화로 진행 상황을 알려 드립니다.</p>
      </header>

      {submitError ? <SubmitErrorNotice error={submitError} /> : null}

      <form className="customer-form" onSubmit={handleSubmit}>
        <CustomerField
          error={fieldError('name', errors, touched)}
          id={nameId}
          label="이름"
        >
          <input
            autoComplete="name"
            id={nameId}
            maxLength={100}
            onBlur={() => markTouched('name', setTouched)}
            onChange={(event) => updateField('name', event.target.value)}
            value={form.name}
          />
        </CustomerField>
        <CustomerField
          error={fieldError('email', errors, touched)}
          id={emailId}
          label="이메일"
        >
          <input
            autoComplete="email"
            id={emailId}
            inputMode="email"
            maxLength={254}
            onBlur={() => markTouched('email', setTouched)}
            onChange={(event) => updateField('email', event.target.value)}
            type="email"
            value={form.email}
          />
        </CustomerField>
        <CustomerField
          error={fieldError('subject', errors, touched)}
          id={subjectId}
          label="제목"
        >
          <input
            id={subjectId}
            maxLength={200}
            onBlur={() => markTouched('subject', setTouched)}
            onChange={(event) => updateField('subject', event.target.value)}
            value={form.subject}
          />
        </CustomerField>
        <CustomerField
          error={fieldError('message', errors, touched)}
          id={messageId}
          label="문의 내용"
        >
          <textarea
            id={messageId}
            maxLength={20_000}
            onBlur={() => markTouched('message', setTouched)}
            onChange={(event) => updateField('message', event.target.value)}
            rows={7}
            value={form.message}
          />
        </CustomerField>
        <section aria-label="문의 첨부 파일" className="customer-field">
          <label htmlFor={attachmentsId}>첨부 파일</label>
          <input
            disabled={submitting || files.length >= MAX_ATTACHMENTS}
            id={attachmentsId}
            multiple
            onChange={(event) => {
              const selected = Array.from(event.target.files ?? [])
              if (selected.length > MAX_ATTACHMENTS) {
                setAttachmentLimitError(true)
                event.target.value = ''
                return
              }
              setFiles(selected)
              setAttachmentLimitError(false)
              setSubmitError(null)
            }}
            type="file"
          />
          {attachmentLimitError ? (
            <small role="alert">
              첨부 파일은 최대 {MAX_ATTACHMENTS}개까지 선택할 수 있습니다.
            </small>
          ) : null}
          {files.length ? (
            <ul aria-live="polite" className="customer-attachment-selection">
              {files.map((file, index) => (
                <li key={`${file.name}-${file.size}-${index}`}>
                  <span>
                    {file.name} · {formatBytes(file.size)}
                  </span>
                  <DsButton
                    disabled={submitting}
                    onClick={() =>
                      setFiles((current) =>
                        current.filter((_, fileIndex) => fileIndex !== index),
                      )
                    }
                    tone="secondary"
                  >
                    선택에서 제거
                  </DsButton>
                </li>
              ))}
            </ul>
          ) : (
            <small>선택된 파일이 없습니다.</small>
          )}
          {submitting && files.length ? (
            <p role="status">
              파일을 업로드하고 악성 파일 검사를 완료하는 중입니다.
              <progress aria-label="문의 첨부 업로드 및 검사 진행 중" />
            </p>
          ) : null}
        </section>
        <footer className="customer-form-actions">
          <DsButton
            disabled={submitting || !valid}
            onClick={() => void submitRequest()}
            tone="primary"
          >
            {submitting ? '문의 접수 중…' : '문의 접수'}
          </DsButton>
          <p>
            접수 후 이메일 링크 또는 문의 조회 화면에서 공개 대화를 확인할 수
            있습니다.
          </p>
        </footer>
      </form>
    </div>
  )
}

function CustomerField({
  children,
  error,
  id,
  label,
}: {
  children: ReactElement<FieldControlProps>
  error?: string
  id: string
  label: string
}) {
  const errorId = `${id}-error`
  const control = cloneElement(children, {
    'aria-describedby': error ? errorId : undefined,
    'aria-invalid': error || undefined,
  })
  return (
    <label className="customer-field" htmlFor={id}>
      <span>{label}</span>
      {control}
      {error ? (
        <small id={errorId} role="alert">
          {error}
        </small>
      ) : null}
    </label>
  )
}

interface FieldControlProps {
  'aria-describedby'?: string
  'aria-invalid'?: boolean | string
  id: string
}

function fieldError(
  field: RequestField,
  errors: RequestFieldErrors,
  touched: Set<RequestField>,
) {
  return touched.has(field) ? errors[field] : undefined
}

function markTouched(
  field: RequestField,
  setTouched: Dispatch<SetStateAction<Set<RequestField>>>,
) {
  setTouched((current) => new Set(current).add(field))
}

interface SubmitError {
  kind:
    | 'denied'
    | 'rate-limited'
    | 'unavailable'
    | 'rejected-attachment'
    | 'invalid-attachment'
    | 'unknown'
  requestId?: string
  retryAfter?: string
}

function toSubmitError(error: unknown): SubmitError {
  if (!(error instanceof ApiError)) return { kind: 'unknown' }
  if (error.status === 403)
    return { kind: 'denied', requestId: error.requestId }
  if (error.status === 422)
    return { kind: 'rejected-attachment', requestId: error.requestId }
  if (error.status === 413 || error.status === 415)
    return { kind: 'invalid-attachment', requestId: error.requestId }
  if (error.status === 429) {
    return {
      kind: 'rate-limited',
      requestId: error.requestId,
      retryAfter: error.retryAfter,
    }
  }
  if (error.status >= 500)
    return { kind: 'unavailable', requestId: error.requestId }
  return { kind: 'unknown', requestId: error.requestId }
}

function SubmitErrorNotice({ error }: { error: SubmitError }) {
  const details =
    error.kind === 'rate-limited'
      ? `${formatRetryAfter(error.retryAfter)} 후 다시 시도해 주세요.`
      : error.kind === 'denied'
        ? '현재 고객 접근 설정에서는 이 방식으로 문의를 접수할 수 없습니다.'
        : error.kind === 'rejected-attachment'
          ? '첨부 파일이 감염 또는 격리 상태여서 문의를 접수하지 않았습니다. 해당 파일을 제거해 주세요.'
          : error.kind === 'invalid-attachment'
            ? '첨부 파일의 크기 또는 형식이 허용 범위를 벗어났습니다.'
            : error.kind === 'unavailable'
              ? '서비스를 일시적으로 사용할 수 없습니다. 입력한 내용은 유지됩니다.'
              : '문의 접수에 실패했습니다. 입력한 내용을 확인한 뒤 다시 시도해 주세요.'
  return (
    <Notification
      title="문의가 접수되지 않았습니다."
      tone={error.kind === 'rate-limited' ? 'warning' : 'danger'}
    >
      <p>{details}</p>
      {error.requestId ? <p>요청 ID: {error.requestId}</p> : null}
    </Notification>
  )
}

function formatRetryAfter(retryAfter: string | undefined) {
  const seconds = Number(retryAfter)
  if (!Number.isSafeInteger(seconds) || seconds < 1) return '잠시'
  return `${seconds}초`
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
