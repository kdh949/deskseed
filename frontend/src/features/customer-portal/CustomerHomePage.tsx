import { Link } from 'react-router'

export function CustomerHomePage() {
  return (
    <main className="customer-page">
      <header className="customer-page-header">
        <p className="customer-page-eyebrow">DESKSEED 고객 지원</p>
        <h1>문의부터 답변 확인까지 한곳에서</h1>
        <p>
          새 문의를 남기거나 이메일 문의 링크로 공개 대화를 확인할 수 있습니다.
          로그인하면 내 문의를 한 번에 볼 수 있습니다.
        </p>
      </header>
      <section aria-label="고객 지원 시작" className="customer-home-actions">
        <Link to="/requests/new">
          <strong>새 문의 접수</strong>
          <span>문의 내용을 남기고 이메일 링크로 진행 상황을 확인합니다.</span>
        </Link>
        <Link to="/requests/lookup">
          <strong>문의 조회</strong>
          <span>
            이 브라우저에서 이전에 연 이메일 문의 링크의 문의를 다시 엽니다.
          </span>
        </Link>
        <Link to="/customer/sign-in">
          <strong>고객 로그인</strong>
          <span>이메일 로그인 링크로 내 문의를 확인합니다.</span>
        </Link>
      </section>
    </main>
  )
}
