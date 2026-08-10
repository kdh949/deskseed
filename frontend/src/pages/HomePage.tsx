import { Link } from 'react-router'

export function HomePage() {
  return (
    <section className="hero-grid">
      <div className="hero-copy">
        <p className="eyebrow">SELF-HOSTED SUPPORT</p>
        <h1>
          문의는 빠르게 접수하고,
          <br />
          처리 과정은 신뢰할 수 있게.
        </h1>
        <p className="lead">
          고객 문의의 첫 본문부터 모든 변경 이력까지 하나의 티켓 흐름으로
          관리하는 오픈소스 지향 고객지원 시스템입니다.
        </p>
        <div className="button-row">
          <Link className="button primary" to="/requests/new">
            새 문의 접수
          </Link>
          <Link className="button secondary" to="/requests/lookup">
            접수한 문의 조회
          </Link>
        </div>
      </div>
      <aside className="principle-card" aria-label="현재 구현 원칙">
        <span className="card-kicker">안전한 문의 접수</span>
        <h2>접수부터 조회까지, 필요한 정보만</h2>
        <ol>
          <li>로그인 없이 문의 접수</li>
          <li>조회 키는 한 번만 발급</li>
          <li>고객에게 공개된 대화만 표시</li>
          <li>접수 번호와 조회 키로 안전하게 확인</li>
        </ol>
        <p>내부 메모와 담당자 정보는 고객 화면에 표시하지 않습니다.</p>
      </aside>
    </section>
  )
}
