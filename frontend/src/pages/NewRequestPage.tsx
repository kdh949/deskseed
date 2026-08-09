import { useMutation } from '@tanstack/react-query'
import { type FormEvent, useState } from 'react'
import { Link } from 'react-router'
import { ApiError, submitRequest } from '../api/client'
import { saveRequestToken } from '../api/tokenStore'
import type { SubmitRequestInput } from '../api/types'

const EMPTY_FORM: SubmitRequestInput = {
  name: '',
  email: '',
  subject: '',
  message: '',
}

export function NewRequestPage() {
  const [form, setForm] = useState<SubmitRequestInput>(EMPTY_FORM)
  const mutation = useMutation({
    mutationFn: submitRequest,
    onSuccess: (result) =>
      saveRequestToken(result.ticketNumber, result.accessToken),
  })

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    mutation.mutate(form)
  }

  if (mutation.data) {
    const submitted = mutation.data
    return (
      <section className="narrow-panel success-panel">
        <p className="eyebrow">접수가 완료되었습니다</p>
        <h1>문의 #{submitted.ticketNumber}</h1>
        <p>
          이 브라우저에는 조회 키를 저장했습니다. 아래 키는 다른 기기에서 조회할
          때 필요하며, 서버에서 원문을 다시 복구할 수 없습니다.
        </p>
        <div className="token-box">
          <code>{submitted.accessToken}</code>
          <button
            className="button small secondary"
            type="button"
            onClick={() => navigator.clipboard.writeText(submitted.accessToken)}
          >
            키 복사
          </button>
        </div>
        <div className="button-row">
          <Link
            className="button primary"
            to={`/requests/${submitted.ticketNumber}`}
          >
            문의 내용 보기
          </Link>
          <button
            className="button secondary"
            type="button"
            onClick={() => {
              setForm(EMPTY_FORM)
              mutation.reset()
            }}
          >
            다른 문의 접수
          </button>
        </div>
      </section>
    )
  }

  const apiError = mutation.error instanceof ApiError ? mutation.error : null

  return (
    <section className="form-layout">
      <div>
        <p className="eyebrow">새 문의</p>
        <h1>무엇을 도와드릴까요?</h1>
        <p className="muted">
          현재는 로그인 없이 접수할 수 있습니다. 답변 확인을 위한 조회 키가
          발급됩니다.
        </p>
      </div>
      <form className="support-form" onSubmit={submit}>
        {apiError && (
          <div className="error-banner" role="alert">
            <strong>{apiError.problem?.title ?? '접수하지 못했습니다.'}</strong>
            <span>{apiError.message}</span>
            {apiError.problem?.requestId && (
              <small>요청 ID: {apiError.problem.requestId}</small>
            )}
          </div>
        )}
        <div className="field-grid two-columns">
          <label>
            이름
            <input
              required
              maxLength={100}
              autoComplete="name"
              value={form.name}
              onChange={(event) =>
                setForm({ ...form, name: event.target.value })
              }
            />
          </label>
          <label>
            이메일
            <input
              required
              type="email"
              maxLength={320}
              autoComplete="email"
              value={form.email}
              onChange={(event) =>
                setForm({ ...form, email: event.target.value })
              }
            />
          </label>
        </div>
        <label>
          제목
          <input
            required
            maxLength={200}
            value={form.subject}
            onChange={(event) =>
              setForm({ ...form, subject: event.target.value })
            }
          />
        </label>
        <label>
          문의 내용
          <textarea
            required
            maxLength={10_000}
            rows={10}
            value={form.message}
            onChange={(event) =>
              setForm({ ...form, message: event.target.value })
            }
          />
          <small>{form.message.length.toLocaleString()} / 10,000</small>
        </label>
        <button
          className="button primary"
          type="submit"
          disabled={mutation.isPending}
        >
          {mutation.isPending ? '접수하는 중…' : '문의 접수'}
        </button>
      </form>
    </section>
  )
}
