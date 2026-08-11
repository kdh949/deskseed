import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, createStaff, disableStaff, listStaff } from '../api/client'
import type { StaffAccount, StaffRole } from '../api/types'
import { Notification, ScreenState } from '../shared/ui/system'

function adminError(error: unknown): string {
  if (!(error instanceof ApiError)) return '요청을 처리할 수 없습니다.'
  if (error.status === 403) return '관리자 권한이 필요합니다.'
  if (error.problem?.code === 'DUPLICATE_STAFF_EMAIL')
    return '이미 등록된 이메일입니다.'
  if (error.problem?.code === 'SELF_DISABLE_NOT_ALLOWED')
    return '현재 로그인한 계정은 비활성화할 수 없습니다.'
  if (error.problem?.code === 'LAST_ACTIVE_ADMIN')
    return '마지막 활성 관리자는 비활성화할 수 없습니다.'
  if (error.problem?.code === 'STAFF_HAS_ASSIGNED_TICKETS')
    return '현재 배정된 티켓이 있어 비활성화할 수 없습니다.'
  return `${error.message}${error.requestId ? ` 요청 ID: ${error.requestId}` : ''}`
}

export function AdminStaffPage() {
  const [staff, setStaff] = useState<StaffAccount[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [role, setRole] = useState<StaffRole>('AGENT')
  const [password, setPassword] = useState('')
  const errorRef = useRef<HTMLDivElement>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setStaff(await listStaff())
    } catch (caught) {
      setError(adminError(caught))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => void reload(), [reload])
  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await createStaff({ email, displayName, role, password })
      setEmail('')
      setDisplayName('')
      setRole('AGENT')
      setPassword('')
      await reload()
    } catch (caught) {
      setError(adminError(caught))
    } finally {
      setSubmitting(false)
    }
  }

  async function deactivate(staffId: string) {
    setError(null)
    try {
      await disableStaff(staffId)
      await reload()
    } catch (caught) {
      setError(adminError(caught))
    }
  }

  return (
    <section aria-labelledby="staff-admin-title">
      <div className="admin-title-row">
        <div>
          <p className="eyebrow">ORGANIZATION</p>
          <h1 id="staff-admin-title">직원 계정</h1>
          <p>ADMIN 또는 AGENT 역할의 직원을 만들고 접근을 비활성화합니다.</p>
        </div>
      </div>
      {error ? (
        <Notification
          tone="danger"
          title={error}
          tabIndex={-1}
          ref={errorRef}
        />
      ) : null}
      <div className="admin-grid">
        <section className="admin-panel" aria-labelledby="create-staff-title">
          <h2 id="create-staff-title">직원 추가</h2>
          <form className="admin-form" onSubmit={submit}>
            <label>
              이름
              <input
                required
                maxLength={100}
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
              />
            </label>
            <label>
              이메일
              <input
                required
                type="email"
                autoComplete="off"
                maxLength={254}
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              />
            </label>
            <label>
              역할
              <select
                value={role}
                onChange={(event) => setRole(event.target.value as StaffRole)}
              >
                <option value="AGENT">AGENT</option>
                <option value="ADMIN">ADMIN</option>
              </select>
            </label>
            <label>
              초기 비밀번호
              <input
                required
                type="password"
                autoComplete="new-password"
                minLength={12}
                maxLength={128}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
              <small>12자 이상, 응답이나 감사 기록에 저장되지 않습니다.</small>
            </label>
            <button
              className="button primary"
              type="submit"
              disabled={submitting}
            >
              {submitting ? '추가 중…' : '직원 추가'}
            </button>
          </form>
        </section>
        <section
          className="admin-panel admin-list-panel"
          aria-labelledby="staff-list-title"
        >
          <h2 id="staff-list-title">등록된 직원</h2>
          {loading ? (
            <ScreenState
              kind="loading"
              compact
              title="직원 목록을 불러오는 중입니다."
            />
          ) : null}
          {!loading && !error && staff.length === 0 ? (
            <ScreenState kind="empty" compact title="등록된 직원이 없습니다." />
          ) : null}
          {!loading && staff.length > 0 ? (
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
                  {staff.map((item) => (
                    <tr key={item.id}>
                      <td>
                        <strong>{item.displayName}</strong>
                        <small>{item.email}</small>
                      </td>
                      <td>{item.role}</td>
                      <td>{item.status === 'ACTIVE' ? '활성' : '비활성'}</td>
                      <td>
                        {item.memberships
                          .map((group) => group.name)
                          .join(', ') || '없음'}
                      </td>
                      <td>
                        {item.status === 'ACTIVE' ? (
                          <button
                            className="text-button danger"
                            type="button"
                            onClick={() => void deactivate(item.id)}
                          >
                            비활성화
                          </button>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
          {!loading && error ? (
            <button
              className="button secondary small"
              type="button"
              onClick={reload}
            >
              목록 다시 불러오기
            </button>
          ) : null}
        </section>
      </div>
    </section>
  )
}
