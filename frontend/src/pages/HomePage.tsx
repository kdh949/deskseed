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
          <Link className="button secondary" to="/lookup">
            접수한 문의 조회
          </Link>
        </div>
      </div>
      <aside className="principle-card" aria-label="현재 구현 원칙">
        <span className="card-kicker">M1 vertical slice</span>
        <h2>한 번의 요청, 하나의 원자적 기록</h2>
        <ol>
          <li>고객 프로필 생성 또는 재사용</li>
          <li>티켓과 첫 공개 코멘트 생성</li>
          <li>변경 audit과 event 기록</li>
          <li>공개 대화 전용 조회 키 발급</li>
        </ol>
        <p>내부 메모와 직원 정보는 고객 API 경계 밖에 남습니다.</p>
      </aside>
    </section>
  )
}
