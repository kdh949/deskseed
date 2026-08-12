import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import {
  ApiError,
  addCustomerFollowUp,
  claimCustomerRequest,
  getCustomerRequest,
  listCustomerRequests,
  type CustomerClaimProof,
  type CustomerRequestStatus,
} from '../features/customer-portal/customerPortalClient'
import { Notification, ScreenState } from '../shared/ui/system'

const STATUS_LABELS: Record<CustomerRequestStatus, string> = {
  NEW: '신규',
  OPEN: '처리 중',
  PENDING: '고객 답변 대기',
  SOLVED: '해결됨',
}

export function CustomerRequestsPage() {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<CustomerRequestStatus | ''>('')
  const [ticketNumber, setTicketNumber] = useState('')
  const [proof, setProof] = useState('')
  const [proofType, setProofType] = useState<
    'requestAccessToken' | 'claimToken'
  >('requestAccessToken')
  const resultRef = useRef<HTMLDivElement>(null)
  const requests = useQuery({
    queryKey: ['customer-requests', status || 'ALL'],
    queryFn: () => listCustomerRequests(status || undefined),
  })
  const claim = useMutation({
    mutationFn: ({
      number,
      claimProof,
    }: {
      number: number
      claimProof: CustomerClaimProof
    }) => claimCustomerRequest(number, claimProof),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['customer-requests'] })
      setTicketNumber('')
      setProof('')
      resultRef.current?.focus()
    },
    onError: () => resultRef.current?.focus(),
  })

  function submitClaim(event: FormEvent) {
    event.preventDefault()
    const number = Number(ticketNumber)
    if (!Number.isSafeInteger(number) || number < 1 || proof.length < 32) return
    const claimProof: CustomerClaimProof =
      proofType === 'requestAccessToken'
        ? { requestAccessToken: proof }
        : { claimToken: proof }
    claim.mutate({ number, claimProof })
  }

  return (
    <section
      className="customer-portal-page"
      aria-labelledby="my-requests-title"
    >
      <header className="customer-portal-heading">
        <div>
          <p className="eyebrow">MY REQUESTS</p>
          <h1 id="my-requests-title">내 문의</h1>
          <p className="muted">
            로그인한 계정에 명시적으로 연결된 문의만 표시됩니다.
          </p>
        </div>
        <Link className="button primary" to="/requests/new">
          새 문의 접수
        </Link>
      </header>

      <label className="customer-filter">
        상태 필터
        <select
          value={status}
          onChange={(event) =>
            setStatus(event.target.value as CustomerRequestStatus | '')
          }
        >
          <option value="">전체</option>
          {Object.entries(STATUS_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </label>

      {requests.isPending ? (
        <ScreenState
          kind="loading"
          ariaLabel="내 문의를 불러오는 중"
          title="내 문의를 불러오고 있습니다."
        />
      ) : requests.isError ? (
        <ScreenState
          kind="error"
          title="내 문의를 불러오지 못했습니다."
          action={
            <button
              className="button primary"
              type="button"
              onClick={() => void requests.refetch()}
            >
              다시 시도
            </button>
          }
        />
      ) : requests.data.items.length === 0 ? (
        <ScreenState
          kind="empty"
          title="아직 연결된 문의가 없습니다."
          description="새 문의를 접수하거나 아래에서 기존 익명 문의를 연결하세요."
        />
      ) : (
        <ul className="customer-request-list" aria-label="내 문의 목록">
          {requests.data.items.map((request) => (
            <li key={request.ticketNumber}>
              <Link to={`/account/requests/${request.ticketNumber}`}>
                <span className="customer-request-number">
                  #{request.ticketNumber}
                </span>
                <strong>{request.subject}</strong>
                <span
                  className={`customer-status status-${request.status.toLowerCase()}`}
                >
                  {STATUS_LABELS[request.status]}
                </span>
                <time dateTime={request.updatedAt}>
                  {new Date(request.updatedAt).toLocaleDateString('ko-KR')}
                </time>
              </Link>
            </li>
          ))}
        </ul>
      )}

      <section className="claim-card" aria-labelledby="claim-title">
        <div>
          <p className="eyebrow">EXPLICIT CLAIM</p>
          <h2 id="claim-title">기존 익명 문의 연결</h2>
          <p className="muted">
            이메일이 같아도 자동으로 연결하지 않습니다. 문의별 증명이
            필요합니다.
          </p>
        </div>
        {claim.isSuccess ? (
          <Notification
            ref={resultRef}
            tabIndex={-1}
            tone="success"
            title="문의를 계정에 연결했습니다."
          />
        ) : claim.isError ? (
          <div ref={resultRef} tabIndex={-1}>
            <ScreenState
              kind={
                claim.error instanceof ApiError && claim.error.status === 403
                  ? 'denied'
                  : 'not-found'
              }
              title={
                claim.error instanceof ApiError && claim.error.status === 403
                  ? '이 계정으로 연결할 수 없습니다.'
                  : '연결 증명을 사용할 수 없습니다.'
              }
              description="증명이 만료되었거나 이미 사용되었을 수 있습니다. 입력은 다시 확인할 수 있도록 보존했습니다."
            />
          </div>
        ) : null}
        <form className="claim-form" onSubmit={submitClaim}>
          <label>
            접수 번호
            <input
              type="number"
              min={1}
              required
              value={ticketNumber}
              onChange={(event) => setTicketNumber(event.target.value)}
            />
          </label>
          <fieldset>
            <legend>연결 증명 종류</legend>
            <label>
              <input
                type="radio"
                name="claim-proof-type"
                checked={proofType === 'requestAccessToken'}
                onChange={() => setProofType('requestAccessToken')}
              />
              조회 키
            </label>
            <label>
              <input
                type="radio"
                name="claim-proof-type"
                checked={proofType === 'claimToken'}
                onChange={() => setProofType('claimToken')}
              />
              서명된 연결 증명
            </label>
          </fieldset>
          <label>
            연결 증명
            <input
              type="text"
              autoComplete="off"
              minLength={32}
              maxLength={1000}
              required
              value={proof}
              onChange={(event) => setProof(event.target.value)}
            />
          </label>
          <button
            className="button secondary"
            type="submit"
            disabled={claim.isPending}
          >
            {claim.isPending ? '연결 중…' : '문의 연결'}
          </button>
        </form>
      </section>
    </section>
  )
}

export function CustomerRequestDetailPage() {
  const queryClient = useQueryClient()
  const ticketNumber = Number(useParams().ticketNumber)
  const [draft, setDraft] = useState('')
  const commandId = useRef<string | null>(null)
  const statusRef = useRef<HTMLDivElement>(null)
  const request = useQuery({
    queryKey: ['customer-request', ticketNumber],
    queryFn: () => getCustomerRequest(ticketNumber),
    enabled: Number.isSafeInteger(ticketNumber) && ticketNumber > 0,
  })
  const followUp = useMutation({
    mutationFn: () => {
      commandId.current ??= crypto.randomUUID()
      return addCustomerFollowUp(ticketNumber, draft, commandId.current)
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['customer-request', ticketNumber],
        }),
        queryClient.invalidateQueries({ queryKey: ['customer-requests'] }),
      ])
      setDraft('')
      commandId.current = null
      statusRef.current?.focus()
    },
    onError: () => statusRef.current?.focus(),
  })

  if (request.isPending)
    return <ScreenState kind="loading" title="문의 내용을 불러오고 있습니다." />
  if (request.isError) {
    const notFound =
      request.error instanceof ApiError && request.error.status === 404
    return (
      <ScreenState
        kind={notFound ? 'not-found' : 'error'}
        ariaLabel={notFound ? '문의를 확인할 수 없습니다.' : undefined}
        title={notFound ? '문의를 확인할 수 없습니다.' : '문의 조회 오류'}
        requestId={
          notFound
            ? undefined
            : request.error instanceof ApiError
              ? request.error.requestId
              : undefined
        }
        action={
          notFound ? (
            <Link to="/account/requests">내 문의로 돌아가기</Link>
          ) : (
            <button
              type="button"
              className="button primary"
              onClick={() => void request.refetch()}
            >
              다시 시도
            </button>
          )
        }
      />
    )
  }
  const detail = request.data
  return (
    <article className="customer-request-detail">
      <Link className="customer-back-link" to="/account/requests">
        ← 내 문의
      </Link>
      <header>
        <p className="customer-request-number">#{detail.ticketNumber}</p>
        <h1>{detail.subject}</h1>
        <span
          className={`customer-status status-${detail.status.toLowerCase()}`}
        >
          {STATUS_LABELS[detail.status]}
        </span>
      </header>
      <ol className="customer-conversation" aria-label="공개 대화">
        {detail.comments.map((comment) => (
          <li key={comment.id}>
            <article>
              <header>
                <strong>{comment.authorDisplayName}</strong>
                <time dateTime={comment.createdAt}>
                  {new Date(comment.createdAt).toLocaleString('ko-KR')}
                </time>
              </header>
              <p>{comment.body}</p>
            </article>
          </li>
        ))}
      </ol>
      {followUp.isError ? (
        <Notification
          ref={statusRef}
          tabIndex={-1}
          urgent
          tone="danger"
          title="후속 답변을 보내지 못했습니다."
        >
          입력은 보존했습니다. 다시 시도해 주세요.
          {followUp.error instanceof ApiError && followUp.error.requestId ? (
            <span className="ds-request-id">
              {' '}
              요청 ID: {followUp.error.requestId}
            </span>
          ) : null}
        </Notification>
      ) : followUp.isSuccess ? (
        <Notification
          ref={statusRef}
          tabIndex={-1}
          tone="success"
          title="공개 후속 답변을 보냈습니다."
        />
      ) : null}
      <form
        className="customer-follow-up"
        onSubmit={(event) => {
          event.preventDefault()
          followUp.mutate()
        }}
      >
        <label htmlFor="customer-follow-up-body">공개 후속 답변</label>
        <p id="customer-follow-up-help">
          작성한 내용은 고객과 상담팀 모두에게 공개됩니다.
        </p>
        <textarea
          id="customer-follow-up-body"
          aria-describedby="customer-follow-up-help"
          required
          maxLength={20_000}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
        />
        <button
          className="button primary"
          type="submit"
          disabled={followUp.isPending || !draft.trim()}
        >
          {followUp.isPending ? '전송 중…' : '공개 답변 보내기'}
        </button>
      </form>
    </article>
  )
}
