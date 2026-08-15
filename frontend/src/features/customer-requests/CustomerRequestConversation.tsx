import { DsStatusIndicator, ScreenState } from '../../design-system'
import { CustomerFollowUpForm } from './CustomerFollowUpForm'

export interface CustomerVisibleComment {
  authorDisplayName: string
  body: string
  createdAt: string
  id: string
}

export interface CustomerVisibleRequest {
  comments: CustomerVisibleComment[]
  createdAt: string
  status: 'NEW' | 'OPEN' | 'PENDING' | 'SOLVED'
  subject: string
  ticketNumber: number
  updatedAt: string
}

export function CustomerRequestConversation({
  onFollowUpConflict,
  onFollowUpSubmitted,
  onSubmitFollowUp,
  request,
}: {
  onFollowUpConflict?: () => void
  onFollowUpSubmitted?: () => void
  onSubmitFollowUp?: (body: string, clientCommandId: string) => Promise<unknown>
  request: CustomerVisibleRequest
}) {
  return (
    <main
      aria-label={`문의 #${request.ticketNumber}`}
      className="customer-page"
    >
      <header className="customer-page-header customer-request-header">
        <p className="customer-page-eyebrow">문의 #{request.ticketNumber}</p>
        <h1>{`#${request.ticketNumber} ${request.subject}`}</h1>
        <div className="customer-request-meta">
          <DsStatusIndicator tone={statusTone(request.status)}>
            {statusLabel(request.status)}
          </DsStatusIndicator>
          <p>
            최근 업데이트{' '}
            <time dateTime={request.updatedAt}>
              {formatTimestamp(request.updatedAt)}
            </time>
          </p>
        </div>
      </header>

      <section
        aria-labelledby="customer-conversation-title"
        className="customer-conversation"
      >
        <h2 id="customer-conversation-title">공개 대화</h2>
        {request.comments.length === 0 ? (
          <ScreenState
            compact
            description="아직 표시할 공개 대화가 없습니다."
            kind="empty"
            title="공개 대화가 비어 있습니다."
          />
        ) : (
          <ol>
            {request.comments.map((comment) => (
              <li key={comment.id}>
                <article>
                  <header>
                    <strong>{comment.authorDisplayName}</strong>
                    <time dateTime={comment.createdAt}>
                      {formatTimestamp(comment.createdAt)}
                    </time>
                  </header>
                  <p>{comment.body}</p>
                </article>
              </li>
            ))}
          </ol>
        )}
      </section>

      {onSubmitFollowUp ? (
        <CustomerFollowUpForm
          onConflict={onFollowUpConflict}
          onSubmitted={onFollowUpSubmitted}
          onSubmit={onSubmitFollowUp}
        />
      ) : null}
    </main>
  )
}

function statusTone(status: CustomerVisibleRequest['status']) {
  switch (status) {
    case 'NEW':
      return 'new' as const
    case 'OPEN':
      return 'open' as const
    case 'PENDING':
      return 'pending' as const
    case 'SOLVED':
      return 'solved' as const
  }
}

function statusLabel(status: CustomerVisibleRequest['status']) {
  switch (status) {
    case 'NEW':
      return '신규'
    case 'OPEN':
      return '처리 중'
    case 'PENDING':
      return '고객 답변 대기'
    case 'SOLVED':
      return '해결됨'
  }
}

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
