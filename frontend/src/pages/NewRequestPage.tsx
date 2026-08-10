import {
  type ChangeEvent,
  type FormEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { ApiError, submitRequest } from '../api/client'
import type { SubmitRequestInput, SubmittedRequest } from '../api/types'
import { useRequestAccess } from '../features/customer-requests/RequestAccessContext'
import {
  EMPTY_REQUEST_FORM,
  isRequestField,
  REQUEST_FIELD_LIMITS,
  type RequestField,
  type RequestFieldErrors,
  validateRequestForm,
} from '../features/customer-requests/requestForm'
import { RequestSuccess } from '../features/customer-requests/RequestSuccess'

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
    return {
      title: '잠시 후 다시 시도해 주세요',
      detail: error.retryAfter
        ? `${error.retryAfter}초 뒤에 다시 접수해 주세요.`
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

export function NewRequestPage() {
  const requestAccess = useRequestAccess()
  const [form, setForm] = useState<SubmitRequestInput>(EMPTY_REQUEST_FORM)
  const [touched, setTouched] = useState<TouchedFields>({})
  const [serverErrors, setServerErrors] = useState<RequestFieldErrors>({})
  const [summary, setSummary] = useState<ErrorSummary | null>(null)
  const [submitted, setSubmitted] = useState<SubmittedRequest | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const pendingRef = useRef(false)
  const summaryRef = useRef<HTMLDivElement>(null)
  const clientErrors = useMemo(() => validateRequestForm(form), [form])
  const isValid = Object.keys(clientErrors).length === 0

  useEffect(() => {
    if (summary) summaryRef.current?.focus()
  }, [summary])

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
    }

  const blurField = (field: RequestField) => {
    setTouched((current) => ({ ...current, [field]: true }))
    if (clientErrors[field]) {
      setSummary({
        title: '입력 내용을 확인해 주세요',
        detail: '표시된 항목을 고친 뒤 다시 접수해 주세요.',
      })
    }
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (pendingRef.current) return
    if (!isValid) {
      setTouched({ name: true, email: true, subject: true, message: true })
      setSummary({
        title: '입력 내용을 확인해 주세요',
        detail: '표시된 항목을 고친 뒤 다시 접수해 주세요.',
      })
      return
    }

    pendingRef.current = true
    setIsSubmitting(true)
    setSummary(null)
    setServerErrors({})
    try {
      const result = await submitRequest(form)
      requestAccess.setAccessToken(result.ticketNumber, result.accessToken)
      setSubmitted(result)
    } catch (error) {
      if (error instanceof ApiError) setServerErrors(toServerFieldErrors(error))
      setSummary(toErrorSummary(error))
    } finally {
      pendingRef.current = false
      setIsSubmitting(false)
    }
  }

  if (submitted) {
    return (
      <RequestSuccess
        submitted={submitted}
        onReset={() => {
          setForm(EMPTY_REQUEST_FORM)
          setTouched({})
          setServerErrors({})
          setSummary(null)
          setSubmitted(null)
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
        {summary && (
          <div
            className="error-banner"
            role="alert"
            aria-labelledby="request-error-title"
            ref={summaryRef}
            tabIndex={-1}
          >
            <strong id="request-error-title">{summary.title}</strong>
            <span>{summary.detail}</span>
            {summary.requestId && <small>요청 ID: {summary.requestId}</small>}
          </div>
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
          disabled={!isValid || isSubmitting}
          aria-busy={isSubmitting}
        >
          {isSubmitting ? '안전하게 접수하는 중…' : '문의 접수'}
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
