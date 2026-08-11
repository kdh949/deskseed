import {
  type ChangeEvent,
  type FormEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { useBeforeUnload, useBlocker } from 'react-router'
import { ApiError } from '../api/client'
import type { SubmitRequestInput } from '../api/types'
import { useRequestSubmission } from '../features/customer-requests/RequestSubmissionContext'
import {
  EMPTY_REQUEST_FORM,
  isRequestField,
  REQUEST_FIELD_LIMITS,
  type RequestField,
  type RequestFieldErrors,
  validateRequestForm,
} from '../features/customer-requests/requestForm'
import { RequestSuccess } from '../features/customer-requests/RequestSuccess'
import { Notification } from '../shared/ui/system'

type TouchedFields = Partial<Record<RequestField, boolean>>

interface ErrorSummary {
  title: string
  detail: string
  requestId?: string
}

function toServerFieldErrors(error: ApiError): RequestFieldErrors {
  return Object.fromEntries(
    Object.entries(error.fieldErrors).filter(([field]) =>
      isRequestField(field),
    ),
  )
}

function toErrorSummary(error: unknown): ErrorSummary {
  if (!(error instanceof ApiError)) {
    return {
      title: '문의 접수 오류',
      detail: '네트워크 연결을 확인한 뒤 다시 시도해 주세요.',
    }
  }
  if (error.status === 400) {
    return {
      title: '입력 내용을 확인해 주세요',
      detail: '표시된 항목을 고친 뒤 다시 접수해 주세요.',
      requestId: error.requestId,
    }
  }
  if (error.status === 429) {
    const retryAfterSeconds = parseRetryAfterSeconds(error.retryAfter)
    return {
      title: '잠시 후 다시 시도해 주세요',
      detail: retryAfterSeconds
        ? `${retryAfterSeconds}초 뒤에 다시 접수해 주세요.`
        : '요청이 많습니다. 잠시 기다린 뒤 다시 접수해 주세요.',
      requestId: error.requestId,
    }
  }
  return {
    title: '문의 접수 오류',
    detail: '내용은 그대로 보존했습니다. 잠시 후 다시 시도해 주세요.',
    requestId: error.requestId,
  }
}

function parseRetryAfterSeconds(value: string | undefined): number | undefined {
  if (!value) return undefined
  const deltaSeconds = Number(value)
  if (Number.isFinite(deltaSeconds) && deltaSeconds > 0) {
    return Math.ceil(deltaSeconds)
  }
  const retryAt = Date.parse(value)
  if (!Number.isFinite(retryAt)) return undefined
  const seconds = Math.ceil((retryAt - Date.now()) / 1_000)
  return seconds > 0 ? seconds : undefined
}

export function NewRequestPage() {
  const requestSubmission = useRequestSubmission()
  const [form, setForm] = useState<SubmitRequestInput>(EMPTY_REQUEST_FORM)
  const [touched, setTouched] = useState<TouchedFields>({})
  const [serverErrors, setServerErrors] = useState<RequestFieldErrors>({})
  const [summary, setSummary] = useState<ErrorSummary | null>(null)
  const [shouldFocusSummary, setShouldFocusSummary] = useState(false)
  const [navigationBlocked, setNavigationBlocked] = useState(false)
  const submissionHistoryEntryRef = useRef(false)
  const summaryRef = useRef<HTMLDivElement>(null)
  const clientErrors = useMemo(() => validateRequestForm(form), [form])
  const isValid = Object.keys(clientErrors).length === 0
  const blocker = useBlocker(requestSubmission.isSubmitting)

  useBeforeUnload((event) => {
    if (!requestSubmission.isSubmitting) return
    event.preventDefault()
    event.returnValue = ''
  })

  useEffect(() => {
    if (!summary || !shouldFocusSummary) return
    summaryRef.current?.focus()
    setShouldFocusSummary(false)
  }, [shouldFocusSummary, summary])

  useEffect(() => {
    if (blocker.state !== 'blocked') return
    setNavigationBlocked(true)
    blocker.reset()
  }, [blocker])

  useEffect(() => {
    if (!requestSubmission.isSubmitting) setNavigationBlocked(false)
  }, [requestSubmission.isSubmitting])

  useEffect(() => {
    if (!requestSubmission.isSubmitting) {
      submissionHistoryEntryRef.current = false
      return
    }
    if (submissionHistoryEntryRef.current) return

    window.history.pushState(
      { ...window.history.state, deskseedSubmissionGuard: true },
      '',
      window.location.href,
    )
    submissionHistoryEntryRef.current = true
  }, [requestSubmission.isSubmitting])

  useEffect(() => {
    if (!requestSubmission.isSubmitting) return
    let restoringGuardEntry = false
    const restorePendingSubmission = (event: PopStateEvent) => {
      if (restoringGuardEntry) {
        restoringGuardEntry = false
        return
      }
      event.stopImmediatePropagation()
      setNavigationBlocked(true)
      restoringGuardEntry = true
      window.history.go(1)
    }
    window.addEventListener('popstate', restorePendingSubmission, true)
    return () => {
      window.removeEventListener('popstate', restorePendingSubmission, true)
    }
  }, [requestSubmission.isSubmitting])

  const visibleErrors: RequestFieldErrors = { ...serverErrors }
  for (const field of Object.keys(touched) as RequestField[]) {
    if (touched[field] && clientErrors[field]) {
      visibleErrors[field] = clientErrors[field]
    }
  }

  const updateField =
    (field: RequestField) =>
    (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      setForm((current) => ({ ...current, [field]: event.target.value }))
      setServerErrors((current) => {
        const next = { ...current }
        delete next[field]
        return next
      })
      setSummary(null)
      setShouldFocusSummary(false)
    }

  const blurField = (field: RequestField) => {
    setTouched((current) => ({ ...current, [field]: true }))
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!isValid) {
      setTouched({ name: true, email: true, subject: true, message: true })
      setSummary({
        title: '입력 내용을 확인해 주세요',
        detail: '표시된 항목을 고친 뒤 다시 접수해 주세요.',
      })
      setShouldFocusSummary(true)
      return
    }

    setSummary(null)
    setShouldFocusSummary(false)
    setServerErrors({})
    try {
      await requestSubmission.submit(form)
    } catch (error) {
      if (error instanceof ApiError) setServerErrors(toServerFieldErrors(error))
      setSummary(toErrorSummary(error))
      setShouldFocusSummary(true)
    }
  }

  if (requestSubmission.submitted) {
    return (
      <RequestSuccess
        submitted={requestSubmission.submitted}
        onReset={() => {
          setForm(EMPTY_REQUEST_FORM)
          setTouched({})
          setServerErrors({})
          setSummary(null)
          requestSubmission.reset()
        }}
      />
    )
  }

  return (
    <section className="form-layout" aria-labelledby="new-request-title">
      <div>
        <p className="eyebrow">새 문의</p>
        <h1 id="new-request-title">무엇을 도와드릴까요?</h1>
        <p className="muted">
          로그인 없이 접수할 수 있습니다. 답변 확인에 필요한 조회 키는 접수 직후
          한 번만 표시됩니다.
        </p>
      </div>
      <form className="support-form" onSubmit={submit} noValidate>
        {requestSubmission.isSubmitting && (
          <p className="submission-guard" role="status">
            접수를 완료하는 동안 이 화면을 벗어날 수 없습니다.
          </p>
        )}
        {navigationBlocked && (
          <p className="submission-guard" role="status">
            접수 결과를 안전하게 표시한 뒤 이동할 수 있습니다.
          </p>
        )}
        {summary && (
          <Notification
            tone="danger"
            title={summary.title}
            ref={summaryRef}
            tabIndex={-1}
          >
            <p>{summary.detail}</p>
            {summary.requestId ? (
              <p className="ds-request-id">
                요청 ID: <code>{summary.requestId}</code>
              </p>
            ) : null}
          </Notification>
        )}
        <div className="field-grid two-columns">
          <RequestFieldControl
            field="name"
            label="이름"
            value={form.name}
            error={visibleErrors.name}
            maxLength={REQUEST_FIELD_LIMITS.name}
            autoComplete="name"
            onChange={updateField('name')}
            onBlur={() => blurField('name')}
          />
          <RequestFieldControl
            field="email"
            label="이메일"
            type="email"
            value={form.email}
            error={visibleErrors.email}
            maxLength={REQUEST_FIELD_LIMITS.email}
            autoComplete="email"
            onChange={updateField('email')}
            onBlur={() => blurField('email')}
          />
        </div>
        <RequestFieldControl
          field="subject"
          label="제목"
          value={form.subject}
          error={visibleErrors.subject}
          maxLength={REQUEST_FIELD_LIMITS.subject}
          onChange={updateField('subject')}
          onBlur={() => blurField('subject')}
        />
        <label className="form-field" htmlFor="request-message">
          <span>
            문의 내용 <span aria-hidden="true">*</span>
          </span>
          <textarea
            id="request-message"
            required
            maxLength={REQUEST_FIELD_LIMITS.message}
            rows={10}
            value={form.message}
            aria-invalid={visibleErrors.message ? 'true' : undefined}
            aria-describedby="request-message-help request-message-error"
            onChange={updateField('message')}
            onBlur={() => blurField('message')}
          />
          <small id="request-message-help">
            {form.message.length.toLocaleString()} / 20,000
          </small>
          <span className="field-error" id="request-message-error">
            {visibleErrors.message}
          </span>
        </label>
        <p className="required-note">* 필수 입력 항목</p>
        <button
          className="button primary"
          type="submit"
          disabled={requestSubmission.isSubmitting}
          aria-busy={requestSubmission.isSubmitting}
        >
          {requestSubmission.isSubmitting
            ? '안전하게 접수하는 중…'
            : '문의 접수'}
        </button>
      </form>
    </section>
  )
}

interface RequestFieldControlProps {
  field: Exclude<RequestField, 'message'>
  label: string
  value: string
  error?: string
  maxLength: number
  type?: string
  autoComplete?: string
  onChange(event: ChangeEvent<HTMLInputElement>): void
  onBlur(): void
}

function RequestFieldControl({
  field,
  label,
  value,
  error,
  maxLength,
  type = 'text',
  autoComplete,
  onChange,
  onBlur,
}: RequestFieldControlProps) {
  const inputId = `request-${field}`
  const errorId = `${inputId}-error`
  return (
    <label className="form-field" htmlFor={inputId}>
      <span>
        {label} <span aria-hidden="true">*</span>
      </span>
      <input
        id={inputId}
        required
        type={type}
        maxLength={maxLength}
        autoComplete={autoComplete}
        value={value}
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? errorId : undefined}
        onChange={onChange}
        onBlur={onBlur}
      />
      <span className="field-error" id={errorId}>
        {error}
      </span>
    </label>
  )
}
