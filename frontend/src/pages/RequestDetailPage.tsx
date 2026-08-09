import { useQuery } from '@tanstack/react-query'
import { type FormEvent, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, getPublicRequest } from '../api/client'
import { loadRequestToken, removeRequestToken, saveRequestToken } from '../api/tokenStore'
import { StatusBadge } from '../components/StatusBadge'

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export function RequestDetailPage() {
  const params = useParams()
  const ticketNumber = Number(params.ticketNumber)
  const initialToken = useMemo(
    () => (Number.isSafeInteger(ticketNumber) ? loadRequestToken(ticketNumber) ?? '' : ''),
    [ticketNumber],
  )
  const [token, setToken] = useState(initialToken)
  const [tokenDraft, setTokenDraft] = useState(initialToken)

  const query = useQuery({
    queryKey: ['public-request', ticketNumber, token],
    queryFn: () => getPublicRequest(ticketNumber, token),
    enabled: Number.isSafeInteger(ticketNumber) && ticketNumber > 0 && token.length > 0,
    retry: false,
  })

  const saveToken = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const cleanToken = tokenDraft.trim()
    saveRequestToken(ticketNumber, cleanToken)
    setToken(cleanToken)
  }

  if (!Number.isSafeInteger(ticketNumber) || ticketNumber <= 0) {
    return (
      <section className="narrow-panel">
        <h1>올바르지 않은 접수 번호입니다.</h1>
        <Link className="button primary" to="/lookup">문의 조회로 이동</Link>
      </section>
    )
  }

  if (!token) {
    return (
      <section className="narrow-panel">
        <p className="eyebrow">문의 #{ticketNumber}</p>
        <h1>조회 키가 필요합니다.</h1>
        <form className="support-form" onSubmit={saveToken}>
          <label>
            조회 키
            <textarea
              required
              rows={3}
              autoComplete="off"
              spellCheck={false}
              value={tokenDraft}
              onChange={(event) => setTokenDraft(event.target.value)}
            />
          </label>
          <button className="button primary" type="submit">확인</button>
        </form>
      </section>
    )
  }

  if (query.isPending) {
    return <section className="narrow-panel"><p>문의 내용을 불러오는 중입니다…</p></section>
  }

  if (query.isError) {
    const error = query.error instanceof ApiError ? query.error : null
    return (
      <section className="narrow-panel">
        <div className="error-banner" role="alert">
          <strong>{error?.problem?.title ?? '문의 내용을 불러오지 못했습니다.'}</strong>
          <span>{error?.message ?? '잠시 후 다시 시도해 주세요.'}</span>
          {error?.problem?.requestId && <small>요청 ID: {error.problem.requestId}</small>}
        </div>
        <div className="button-row">
          <button
            className="button secondary"
            type="button"
            onClick={() => {
              removeRequestToken(ticketNumber)
              setToken('')
              setTokenDraft('')
            }}
          >
            다른 조회 키 입력
          </button>
          <button className="button secondary" type="button" onClick={() => query.refetch()}>
            다시 시도
          </button>
        </div>
      </section>
    )
  }

  const request = query.data
  if (!request) {
    return <section className="narrow-panel"><p>표시할 문의 데이터가 없습니다.</p></section>
  }

  return (
    <section className="ticket-page">
      <header className="ticket-heading">
        <div>
          <p className="eyebrow">문의 #{request.ticketNumber}</p>
          <h1>{request.subject}</h1>
          <p className="muted">접수 {formatDate(request.createdAt)} · 최근 변경 {formatDate(request.updatedAt)}</p>
        </div>
        <StatusBadge status={request.status} />
      </header>

      <div className="conversation" aria-label="공개 대화">
        {request.comments.map((comment) => (
          <article className={`comment comment-${comment.authorType.toLowerCase()}`} key={comment.id}>
            <header>
              <strong>{comment.authorType === 'CUSTOMER' ? '고객' : '상담팀'}</strong>
              <time dateTime={comment.createdAt}>{formatDate(comment.createdAt)}</time>
            </header>
            <p>{comment.body}</p>
          </article>
        ))}
      </div>

      <aside className="notice-card">
        <strong>현재 데모 범위</strong>
        <p>상담사 공개 답변과 고객 추가 댓글은 M2/P1에서 연결됩니다. 내부 메모는 이 화면과 API에 노출되지 않습니다.</p>
      </aside>
    </section>
  )
}
