import { useQuery, useQueryClient } from '@tanstack/react-query'
import { type FormEvent, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, getPublicRequest } from '../api/client'
import { StatusBadge } from '../components/StatusBadge'
import { useRequestAccess } from '../features/customer-requests/RequestAccessContext'

const TOKEN_MIN_LENGTH = 32
const TOKEN_MAX_LENGTH = 256

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export function RequestDetailPage() {
  const params = useParams()
  const ticketNumber = Number(params.ticketNumber)
  const validTicketNumber =
    Number.isSafeInteger(ticketNumber) && ticketNumber > 0
  const requestAccess = useRequestAccess()
  const queryClient = useQueryClient()
  const initialGrant = useMemo(
    () =>
      validTicketNumber
        ? {
            token: requestAccess.getAccessToken(ticketNumber) ?? '',
            revision: requestAccess.getGrantRevision(ticketNumber) ?? 0,
          }
        : { token: '', revision: 0 },
    [requestAccess, ticketNumber, validTicketNumber],
  )
  const [token, setToken] = useState(initialGrant.token)
  const [tokenDraft, setTokenDraft] = useState(initialGrant.token)
  const [grantRevision, setGrantRevision] = useState(initialGrant.revision)
  const tokenIsValid =
    tokenDraft.trim().length >= TOKEN_MIN_LENGTH &&
    tokenDraft.trim().length <= TOKEN_MAX_LENGTH

  const query = useQuery({
    queryKey: ['public-request', ticketNumber, grantRevision],
    queryFn: () => getPublicRequest(ticketNumber, token),
    enabled: validTicketNumber && token.length > 0,
    retry: false,
  })

  const saveToken = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const cleanToken = tokenDraft.trim()
    if (
      cleanToken.length < TOKEN_MIN_LENGTH ||
      cleanToken.length > TOKEN_MAX_LENGTH
    )
      return
    queryClient.removeQueries({ queryKey: ['public-request', ticketNumber] })
    const revision = requestAccess.setAccessToken(ticketNumber, cleanToken)
    setToken(cleanToken)
    setGrantRevision(revision)
  }

  const resetToken = () => {
    queryClient.removeQueries({ queryKey: ['public-request', ticketNumber] })
    requestAccess.clearAccessToken(ticketNumber)
    setToken('')
    setTokenDraft('')
    setGrantRevision(0)
  }

  if (!validTicketNumber) {
    return (
      <section className="narrow-panel">
        <h1>문의 정보를 확인할 수 없습니다.</h1>
        <p>접수 번호와 조회 키를 다시 확인해 주세요.</p>
        <Link className="button primary" to="/requests/lookup">
          문의 조회로 이동
        </Link>
      </section>
    )
  }

  if (!token) {
    return (
      <AccessKeyForm
        ticketNumber={ticketNumber}
        tokenDraft={tokenDraft}
        tokenIsValid={tokenIsValid}
        onTokenChange={setTokenDraft}
        onSubmit={saveToken}
      />
    )
  }

  if (query.isPending) {
    return (
      <section
        className="narrow-panel loading-state"
        role="status"
        aria-label="문의 내용을 불러오는 중"
        aria-busy="true"
      >
        <div className="loading-line" aria-hidden="true" />
        <div className="loading-line short" aria-hidden="true" />
        <p>문의 내용을 불러오는 중입니다…</p>
      </section>
    )
  }

  if (query.isError) {
    const error = query.error instanceof ApiError ? query.error : null
    const isDenied = error?.status === 404
    return (
      <section className="narrow-panel">
        <div
          className="error-banner"
          role="alert"
          aria-labelledby="request-read-error-title"
        >
          <strong id="request-read-error-title">
            {isDenied ? '문의 정보를 확인할 수 없습니다' : '문의 조회 오류'}
          </strong>
          <span>
            {isDenied
              ? '접수 번호와 조회 키를 확인해 주세요.'
              : '잠시 후 다시 시도해 주세요.'}
          </span>
          {!isDenied && error?.requestId && (
            <small>요청 ID: {error.requestId}</small>
          )}
        </div>
        <div className="button-row">
          <button
            className="button secondary"
            type="button"
            onClick={resetToken}
          >
            다른 조회 키 입력
          </button>
          {!isDenied && (
            <button
              className="button secondary"
              type="button"
              onClick={() => query.refetch()}
            >
              다시 시도
            </button>
          )}
        </div>
      </section>
    )
  }

  const request = query.data
  if (!request) {
    return (
      <section className="narrow-panel" role="status">
        <h1>표시할 문의 내용이 없습니다.</h1>
        <button className="button secondary" type="button" onClick={resetToken}>
          다른 조회 키 입력
        </button>
      </section>
    )
  }

  return (
    <section className="ticket-page" aria-labelledby="request-subject">
      <header className="ticket-heading">
        <div>
          <p className="eyebrow">문의 #{request.ticketNumber}</p>
          <h1 id="request-subject">{request.subject}</h1>
          <p className="muted">
            접수 {formatDate(request.createdAt)} · 최근 변경{' '}
            {formatDate(request.updatedAt)}
          </p>
        </div>
        <StatusBadge status={request.status} />
      </header>

      <section aria-labelledby="public-conversation-title">
        <h2 id="public-conversation-title">공개 대화</h2>
        {request.comments.length === 0 ? (
          <div className="empty-state" role="status">
            <strong>공개 대화가 아직 없습니다.</strong>
            <p>공개 답변이 등록되면 이곳에서 확인할 수 있습니다.</p>
          </div>
        ) : (
          <ol className="conversation">
            {request.comments.map((comment) => (
              <li key={comment.id}>
                <article className="comment">
                  <header>
                    <strong>{comment.authorDisplayName}</strong>
                    <time dateTime={comment.createdAt}>
                      {formatDate(comment.createdAt)}
                    </time>
                  </header>
                  <p>{comment.body}</p>
                </article>
              </li>
            ))}
          </ol>
        )}
      </section>

      <aside className="notice-card">
        <strong>공개 정보만 표시합니다</strong>
        <p>
          내부 메모, 담당 조직과 상담사 정보, 연결된 하위 문의는 이 화면에
          표시되지 않습니다.
        </p>
      </aside>
    </section>
  )
}

interface AccessKeyFormProps {
  ticketNumber: number
  tokenDraft: string
  tokenIsValid: boolean
  onTokenChange(value: string): void
  onSubmit(event: FormEvent<HTMLFormElement>): void
}

function AccessKeyForm({
  ticketNumber,
  tokenDraft,
  tokenIsValid,
  onTokenChange,
  onSubmit,
}: AccessKeyFormProps) {
  return (
    <section className="narrow-panel" aria-labelledby="access-key-title">
      <p className="eyebrow">문의 #{ticketNumber}</p>
      <h1 id="access-key-title">조회 키가 필요합니다.</h1>
      <p className="muted">
        조회 키는 이 탭의 메모리에서만 사용하며 브라우저에 저장하지 않습니다.
      </p>
      <form className="support-form" onSubmit={onSubmit} noValidate>
        <label className="form-field" htmlFor="request-access-key">
          <span>
            조회 키 <span aria-hidden="true">*</span>
          </span>
          <textarea
            id="request-access-key"
            required
            rows={3}
            minLength={TOKEN_MIN_LENGTH}
            maxLength={TOKEN_MAX_LENGTH}
            autoComplete="off"
            spellCheck={false}
            value={tokenDraft}
            aria-describedby="request-access-key-help"
            onChange={(event) => onTokenChange(event.target.value)}
          />
          <small id="request-access-key-help">
            접수 완료 화면에서 발급된 32자 이상의 키를 입력하세요.
          </small>
        </label>
        <button
          className="button primary"
          type="submit"
          disabled={!tokenIsValid}
        >
          문의 확인
        </button>
      </form>
    </section>
  )
}
