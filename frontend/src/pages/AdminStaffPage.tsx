import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import {
  ApiError,
  createStaff,
  disableStaff,
  grantStaffAuditAuthority,
  listStaff,
  revokeStaffAuditAuthority,
} from '../api/client'
import type {
  GrantableAuditAuthority,
  StaffAccount,
  StaffRole,
} from '../api/types'
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
  if (error.problem?.code === 'AUDIT_AUTHORITY_TARGET_INVALID')
    return '활성 SECURITY_AUDITOR 계정에만 감사 권한을 부여할 수 있습니다.'
  return `${error.message}${error.requestId ? ` 요청 ID: ${error.requestId}` : ''}`
}

const AUDIT_AUTHORITY_OPTIONS: ReadonlyArray<{
  authority: GrantableAuditAuthority
  label: string
}> = [
  {
    authority: 'AUDIT_SEARCH_QUERY_REVEAL',
    label: '검색어 원문 공개',
  },
  { authority: 'AUDIT_EXPORT', label: '감사 내보내기' },
  { authority: 'AUDIT_PROJECTION_REBUILD', label: '감사 투영 재구축' },
]
const STAFF_PAGE_SIZE = 20

export function AdminStaffPage() {
  const [staff, setStaff] = useState<StaffAccount[]>([])
  const [page, setPage] = useState(0)
  const [totalCount, setTotalCount] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [role, setRole] = useState<StaffRole>('AGENT')
  const [password, setPassword] = useState('')
  const [authorityMutation, setAuthorityMutation] = useState<string | null>(
    null,
  )
  const errorRef = useRef<HTMLDivElement>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const result = await listStaff(page, STAFF_PAGE_SIZE)
      setStaff(result.items)
      setTotalCount(result.totalCount)
      setTotalPages(result.totalPages)
    } catch (caught) {
      setError(adminError(caught))
    } finally {
      setLoading(false)
    }
  }, [page])

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

  async function toggleAuditAuthority(
    item: StaffAccount,
    authority: GrantableAuditAuthority,
  ) {
    const mutationKey = `${item.id}:${authority}`
    setAuthorityMutation(mutationKey)
    setError(null)
    try {
      if (item.auditAuthorities.includes(authority)) {
        await revokeStaffAuditAuthority(item.id, authority)
      } else {
        await grantStaffAuditAuthority(item.id, authority)
      }
      await reload()
    } catch (caught) {
      setError(adminError(caught))
    } finally {
      setAuthorityMutation(null)
    }
  }

  return (
    <section aria-labelledby="staff-admin-title">
      <div className="admin-title-row">
        <div>
          <p className="eyebrow">ORGANIZATION</p>
          <h1 id="staff-admin-title">직원 계정</h1>
          <p>ADMIN, AGENT 또는 읽기 전용 SECURITY_AUDITOR 계정을 관리합니다.</p>
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
                <option value="SECURITY_AUDITOR">
                  SECURITY_AUDITOR · 읽기 전용
                </option>
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
            <>
              <div className="admin-table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>직원</th>
                      <th>역할</th>
                      <th>상태</th>
                      <th>그룹</th>
                      <th>감사 고위험 권한</th>
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
                          {item.role === 'SECURITY_AUDITOR' ? (
                            <ul className="audit-authority-list">
                              {AUDIT_AUTHORITY_OPTIONS.map(
                                ({ authority, label }) => {
                                  const granted =
                                    item.auditAuthorities.includes(authority)
                                  const mutationKey = `${item.id}:${authority}`
                                  return (
                                    <li key={authority}>
                                      <span>{label}</span>
                                      <button
                                        className="text-button"
                                        type="button"
                                        aria-pressed={granted}
                                        aria-label={`${label} 권한 ${granted ? '회수' : '부여'}`}
                                        disabled={
                                          item.status !== 'ACTIVE' ||
                                          authorityMutation === mutationKey
                                        }
                                        onClick={() =>
                                          void toggleAuditAuthority(
                                            item,
                                            authority,
                                          )
                                        }
                                      >
                                        {authorityMutation === mutationKey
                                          ? '처리 중…'
                                          : granted
                                            ? '부여됨 · 회수'
                                            : '부여'}
                                      </button>
                                    </li>
                                  )
                                },
                              )}
                            </ul>
                          ) : (
                            '해당 없음'
                          )}
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
              <nav className="admin-pagination" aria-label="직원 목록 페이지">
                <button
                  className="button secondary small"
                  type="button"
                  disabled={page === 0}
                  onClick={() => setPage((current) => Math.max(0, current - 1))}
                >
                  이전
                </button>
                <span>
                  전체 {totalCount}명 · {page + 1}/{Math.max(totalPages, 1)}{' '}
                  페이지
                </span>
                <button
                  className="button secondary small"
                  type="button"
                  disabled={page + 1 >= totalPages}
                  onClick={() => setPage((current) => current + 1)}
                >
                  다음
                </button>
              </nav>
            </>
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
