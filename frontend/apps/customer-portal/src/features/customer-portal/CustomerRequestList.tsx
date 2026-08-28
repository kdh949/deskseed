import { Link } from 'react-router'
import { DsButton, DsStatusIndicator, ScreenState } from '../../design-system'

export interface CustomerVisibleRequestSummary {
  createdAt: string
  status: 'NEW' | 'OPEN' | 'PENDING' | 'SOLVED'
  subject: string
  ticketNumber: number
  updatedAt: string
}

export function CustomerRequestList({
  items,
  loadingMore,
  nextCursor,
  onLoadMore,
}: {
  items: CustomerVisibleRequestSummary[]
  loadingMore: boolean
  nextCursor: string | null
  onLoadMore: () => void
}) {
  return (
    <div className="customer-page">
      <header className="customer-page-header">
        <h1>내 문의</h1>
        <p>로그인한 계정에 연결된 공개 문의만 표시합니다.</p>
      </header>
      {items.length === 0 ? (
        <ScreenState
          description="새 문의를 접수하면 이곳에서 진행 상황과 공개 답변을 확인할 수 있습니다."
          kind="empty"
          title="표시할 문의가 없습니다."
        />
      ) : (
        <section aria-label="내 문의 목록" className="customer-request-list">
          <ol>
            {items.map((request) => (
              <li key={request.ticketNumber}>
                <Link
                  aria-label={`#${request.ticketNumber} ${request.subject} 문의 열기`}
                  to={`/account/requests/${request.ticketNumber}`}
                >
                  <article>
                    <div>
                      <h2>{`#${request.ticketNumber} ${request.subject}`}</h2>
                      <p>
                        최근 업데이트{' '}
                        <time dateTime={request.updatedAt}>
                          {formatTimestamp(request.updatedAt)}
                        </time>
                      </p>
                    </div>
                    <DsStatusIndicator tone={statusTone(request.status)}>
                      {statusLabel(request.status)}
                    </DsStatusIndicator>
                  </article>
                </Link>
              </li>
            ))}
          </ol>
          {nextCursor ? (
            <DsButton
              disabled={loadingMore}
              onClick={onLoadMore}
              tone="secondary"
            >
              {loadingMore ? '문의 더 불러오는 중…' : '문의 더 보기'}
            </DsButton>
          ) : null}
        </section>
      )}
    </div>
  )
}

function statusTone(status: CustomerVisibleRequestSummary['status']) {
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

function statusLabel(status: CustomerVisibleRequestSummary['status']) {
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
