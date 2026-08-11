import { NavLink } from 'react-router'
import { AppShell, BrandMark, Notification } from '../../shared/ui/system'

const staff = [
  [
    '한서윤',
    'han.fixture@example.com',
    'ADMIN',
    '활성',
    '고객 지원, 결제 지원',
  ],
  ['박서연', 'park.fixture@example.com', 'AGENT', '활성', '결제 지원'],
  ['정유나', 'jung.fixture@example.com', 'AGENT', '활성', '청구 지원'],
] as const

export function AdminFixture() {
  return (
    <AppShell
      className="admin-shell"
      contentId="fixture-admin-main"
      skipLabel="관리자 설정 내용으로 건너뛰기"
    >
      <header className="admin-header">
        <NavLink
          className="admin-brand"
          to="/__fixtures__/frontend-system/admin"
        >
          <BrandMark compact />
          <strong>Deskseed 설정</strong>
        </NavLink>
        <nav aria-label="관리자 설정 메뉴">
          <a aria-current="page" href="#staff">
            직원
          </a>
          <a href="#groups">그룹</a>
          <a href="#access">고객 접근</a>
        </nav>
        <div className="admin-identity">
          <span>한서윤</span>
          <button className="button secondary small" type="button">
            상담사 화면
          </button>
        </div>
      </header>
      <main className="admin-main" id="fixture-admin-main" tabIndex={-1}>
        <section aria-labelledby="fixture-admin-title">
          <div className="admin-title-row">
            <div>
              <p className="eyebrow">ORGANIZATION</p>
              <h1 id="fixture-admin-title">직원 계정</h1>
              <p>
                직원 역할과 활성 상태를 관리합니다. 비밀번호 원문은 다시
                표시하지 않습니다.
              </p>
            </div>
            <button className="button primary small" type="button">
              직원 추가
            </button>
          </div>
          <Notification tone="info" title="관리 작업은 기록됩니다.">
            <p>직원·역할·그룹 변경은 Admin Security Audit에 남습니다.</p>
          </Notification>
          <div className="admin-grid fixture-admin-grid">
            <aside
              className="admin-panel fixture-admin-nav"
              aria-label="조직 설정 섹션"
            >
              <h2>사람</h2>
              <a className="active" href="#staff">
                직원 <span>3</span>
              </a>
              <a href="#roles">역할</a>
              <a href="#customers">고객</a>
              <h2 id="groups">조직</h2>
              <a href="#groups">
                그룹 <span>3</span>
              </a>
              <a href="#access">접근 정책</a>
            </aside>
            <section
              className="admin-panel admin-list-panel"
              id="staff"
              aria-labelledby="fixture-staff-list-title"
            >
              <header className="fixture-panel-heading">
                <div>
                  <h2 id="fixture-staff-list-title">등록된 직원</h2>
                  <p>활성 3명 · 관리자 1명</p>
                </div>
                <label>
                  직원 검색
                  <input type="search" placeholder="이름 또는 이메일" />
                </label>
              </header>
              <div className="admin-table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>직원</th>
                      <th>역할</th>
                      <th>상태</th>
                      <th>그룹</th>
                      <th>
                        <span className="visually-hidden">작업</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {staff.map(([name, email, role, status, groups]) => (
                      <tr key={email}>
                        <td>
                          <strong>{name}</strong>
                          <small>{email}</small>
                        </td>
                        <td>{role}</td>
                        <td>
                          <span className="admin-status">
                            <span aria-hidden="true">●</span> {status}
                          </span>
                        </td>
                        <td>{groups}</td>
                        <td>
                          <button className="text-button" type="button">
                            세부 정보
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          </div>
        </section>
      </main>
    </AppShell>
  )
}
