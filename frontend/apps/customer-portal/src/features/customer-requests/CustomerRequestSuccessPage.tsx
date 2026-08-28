import { Link, useLocation, useParams } from 'react-router'
import { CustomerIcon } from '../../design-system'
import successImage from '../../assets/deskseed/customer-request-success.png'
import type { SubmittedRequest } from '../../api/types'

export function CustomerRequestSuccessPage() {
  const { ticketNumber = '' } = useParams()
  const location = useLocation()
  const submitted =
    typeof location.state === 'object' &&
    location.state &&
    'submitted' in location.state
      ? (location.state.submitted as SubmittedRequest)
      : undefined
  const number = submitted?.ticketNumber ?? Number(ticketNumber)
  return (
    <div className="customer-success-layout">
      <section className="customer-success-card">
        <img alt="문의 접수 완료를 표현한 노트북 일러스트" src={successImage} />
        <span className="customer-success-pill">
          <CustomerIcon name="check" />
          문의가 안전하게 접수되었습니다
        </span>
        <h1>문의 접수가 완료되었습니다</h1>
        <p>지원팀이 내용을 확인한 뒤 가능한 한 빠르게 답변드릴게요.</p>
        <dl>
          <div>
            <dt>문의 번호</dt>
            <dd>#DS-{number}</dd>
          </div>
          <div>
            <dt>현재 상태</dt>
            <dd>접수됨</dd>
          </div>
        </dl>
        <h2>다음 단계</h2>
        <div className="customer-success-actions">
          <SuccessAction
            icon="inbox"
            title="이 문의 확인"
            description="상태와 답변을 한곳에서 확인하세요."
            to={`/requests/${number}`}
            label="문의 보기"
          />
          <SuccessAction
            icon="book"
            title="관련 문서 찾기"
            description="답을 바로 찾을 수 있는 문서를 살펴보세요."
            to="/search"
            label="문서 둘러보기"
          />
          <SuccessAction
            icon="home"
            title="고객 지원 홈"
            description="다른 도움말과 지원 메뉴로 돌아갑니다."
            to="/"
            label="홈으로"
            primary
          />
        </div>
      </section>
      <aside className="customer-aside">
        <section>
          <h2>문의 요약</h2>
          <div className="customer-summary-row">
            <span>문의 번호</span>
            <strong>#DS-{number}</strong>
          </div>
          <div className="customer-summary-row">
            <span>접수일</span>
            <strong>
              {submitted
                ? new Intl.DateTimeFormat('ko-KR').format(
                    new Date(submitted.createdAt),
                  )
                : '방금 전'}
            </strong>
          </div>
          <div className="customer-summary-row">
            <span>상태</span>
            <strong>접수됨</strong>
          </div>
        </section>
      </aside>
    </div>
  )
}

function SuccessAction({
  icon,
  title,
  description,
  to,
  label,
  primary = false,
}: {
  icon: 'inbox' | 'book' | 'home'
  title: string
  description: string
  to: string
  label: string
  primary?: boolean
}) {
  return (
    <article>
      <span>
        <CustomerIcon name={icon} size="lg" />
      </span>
      <h3>{title}</h3>
      <p>{description}</p>
      <Link className={primary ? 'is-primary' : ''} to={to}>
        {label}
      </Link>
    </article>
  )
}
