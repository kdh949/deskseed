import { Link } from 'react-router'
import { AppShell as PortalShell } from '../../components/AppShell'
import { StatusBadge } from '../../shared/ui/system'

export function PublicFormFixture() {
  return (
    <PortalShell>
      <div className="form-layout fixture-public-form">
        <section>
          <p className="eyebrow">새 문의</p>
          <h1>무엇을 도와드릴까요?</h1>
          <p className="lead">
            로그인 없이 문의할 수 있습니다. 답변 확인에 필요한 조회 키는 접수
            직후 한 번만 표시됩니다.
          </p>
          <div className="fixture-privacy-note">
            <strong>공개 대화로 처리됩니다.</strong>
            <p>비밀번호, 결제정보 전체 또는 인증번호를 입력하지 마세요.</p>
          </div>
        </section>
        <form
          className="support-form"
          onSubmit={(event) => event.preventDefault()}
        >
          <div className="field-grid two-columns">
            <label className="form-field">
              <span>
                이름 <span aria-hidden="true">*</span>
              </span>
              <input required autoComplete="name" />
            </label>
            <label className="form-field">
              <span>
                이메일 <span aria-hidden="true">*</span>
              </span>
              <input required type="email" autoComplete="email" />
            </label>
          </div>
          <label className="form-field">
            <span>
              제목 <span aria-hidden="true">*</span>
            </span>
            <input required maxLength={200} />
          </label>
          <label className="form-field">
            <span>
              문의 내용 <span aria-hidden="true">*</span>
            </span>
            <textarea required rows={8} />
            <small>0 / 20,000</small>
          </label>
          <p className="required-hint">
            <span aria-hidden="true">*</span> 필수 입력 항목
          </p>
          <button className="button primary" type="submit">
            문의 접수
          </button>
        </form>
      </div>
    </PortalShell>
  )
}

export function PublicDetailFixture() {
  return (
    <PortalShell>
      <article
        className="ticket-page fixture-request-detail"
        aria-labelledby="fixture-request-title"
      >
        <header className="ticket-heading">
          <div>
            <p className="eyebrow">문의 #1042</p>
            <h1 id="fixture-request-title">결제 오류 문의</h1>
            <p>
              접수 2026. 8. 11. 오전 11:00 · 최근 변경 2026. 8. 11. 오후 12:30
            </p>
          </div>
          <StatusBadge status="OPEN" />
        </header>
        <section aria-labelledby="public-conversation-title">
          <h2 id="public-conversation-title">공개 대화</h2>
          <ol className="conversation">
            <li>
              <article className="comment">
                <header>
                  <strong>김민수</strong>
                  <time>2026. 8. 11. 오전 11:00</time>
                </header>
                <p>결제 인증은 완료됐는데 주문 내역이 만들어지지 않았습니다.</p>
              </article>
            </li>
            <li>
              <article className="comment">
                <header>
                  <strong>Deskseed 지원팀</strong>
                  <time>2026. 8. 11. 오후 12:30</time>
                </header>
                <p>
                  확인 중이며 중복 결제는 발생하지 않았습니다. 처리 결과를 이
                  문의에서 안내드리겠습니다.
                </p>
              </article>
            </li>
          </ol>
        </section>
        <aside className="notice-card fixture-public-boundary">
          <strong>
            <span aria-hidden="true">✓</span> 공개 정보만 표시합니다.
          </strong>
          <p>
            내부 메모, 담당 조직과 상담사 정보, 연결된 하위 문의는 이 화면에
            표시되지 않습니다.
          </p>
          <Link to="/requests/lookup">다른 문의 조회</Link>
        </aside>
      </article>
    </PortalShell>
  )
}
