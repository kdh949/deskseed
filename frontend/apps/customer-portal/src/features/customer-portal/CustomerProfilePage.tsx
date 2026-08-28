import { CustomerIcon, DsButton, Notification } from '../../design-system'
import { useCustomerSession } from '../customer-auth/CustomerSessionContext'

export function CustomerProfilePage() {
  const { customer } = useCustomerSession()
  if (!customer) return null
  return (
    <div className="customer-account-layout">
      <aside>
        <nav aria-label="계정 메뉴">
          <strong aria-current="page">
            <CustomerIcon name="user" />
            계정 설정
          </strong>
          <a href="/account/requests">
            <CustomerIcon name="inbox" />내 문의
          </a>
        </nav>
      </aside>
      <div className="customer-account-content">
        <header>
          <h1>계정 설정</h1>
          <p>내 정보와 로그인 방식을 확인하세요.</p>
          <nav>
            <button className="is-active" type="button">
              프로필
            </button>
            <button disabled type="button">
              알림
            </button>
            <button disabled type="button">
              보안
            </button>
          </nav>
        </header>
        <div className="customer-account-grid">
          <section className="customer-account-card">
            <h2>프로필 정보</h2>
            <p>현재 고객 세션에 연결된 정보입니다.</p>
            <div className="customer-profile-heading">
              <span className="customer-avatar customer-avatar--large">
                {(customer.displayName || customer.email)
                  .slice(0, 1)
                  .toUpperCase()}
              </span>
              <DsButton disabled>프로필 사진 변경</DsButton>
            </div>
            <div className="customer-profile-fields">
              <label>
                이름
                <input disabled value={customer.displayName || ''} />
              </label>
              <label>
                이메일
                <input disabled value={customer.email} />
              </label>
              <label>
                회사
                <input disabled value={customer.companyName || ''} />
              </label>
              <label>
                로그인 방식
                <input
                  disabled
                  value={customer.availableAuthenticationMethods.join(', ')}
                />
              </label>
            </div>
            <Notification
              title="프로필 변경은 아직 제공되지 않습니다."
              tone="info"
            >
              <p>
                현재 확정된 고객 API는 본인 정보 조회만 지원합니다. 저장 동작을
                임의로 연결하지 않았습니다.
              </p>
            </Notification>
          </section>
          <div className="customer-account-side">
            <section className="customer-account-card">
              <h2>계정 보안</h2>
              <div className="customer-setting-row">
                <span>
                  <CustomerIcon name="lock" />
                </span>
                <div>
                  <strong>이메일 소유권 확인</strong>
                  <p>
                    {new Intl.DateTimeFormat('ko-KR').format(
                      new Date(customer.verifiedAt),
                    )}{' '}
                    확인됨
                  </p>
                </div>
                <em>사용 중</em>
              </div>
              <div className="customer-setting-row">
                <span>
                  <CustomerIcon name="check" />
                </span>
                <div>
                  <strong>등록 상태</strong>
                  <p>
                    {customer.registrationState === 'COMPLETE'
                      ? '가입 완료'
                      : '추가 정보 필요'}
                  </p>
                </div>
              </div>
            </section>
            <section className="customer-account-card">
              <h2>지원 알림</h2>
              <p>문의 답변은 확인된 이메일 주소로 전송됩니다.</p>
              <div className="customer-setting-row">
                <span>
                  <CustomerIcon name="mail" />
                </span>
                <div>
                  <strong>문의 업데이트</strong>
                  <p>{customer.email}</p>
                </div>
              </div>
            </section>
          </div>
        </div>
      </div>
    </div>
  )
}
