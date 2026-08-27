import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent, type ReactNode } from 'react'
import {
  ApiError,
  createStaff,
  disableStaff,
  grantStaffAuditAuthority,
  listStaff,
  revokeStaffAuditAuthority,
} from '../../api/client'
import type {
  GrantableAuditAuthority,
  StaffAccount,
  StaffRole,
} from '../../api/types'
import {
  DsButton,
  Notification,
  RetryButton,
  ScreenState,
} from '../../design-system'

const ROLE_LABELS: Record<StaffRole, string> = {
  ADMIN: '관리자',
  AGENT: '상담사',
  SECURITY_AUDITOR: '보안 감사자',
}

const AUDIT_AUTHORITIES: Array<{
  label: string
  value: GrantableAuditAuthority
}> = [
  { value: 'AUDIT_SEARCH_QUERY_REVEAL', label: '검색어 공개' },
  { value: 'AUDIT_EXPORT', label: '감사 내보내기' },
  { value: 'AUDIT_PROJECTION_REBUILD', label: '감사 projection 재구성' },
]

export function AdminStaffPage() {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [role, setRole] = useState<StaffRole>('AGENT')
  const [password, setPassword] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)
  const [disableCandidate, setDisableCandidate] = useState<StaffAccount | null>(
    null,
  )
  const [authorityStaff, setAuthorityStaff] = useState<StaffAccount | null>(
    null,
  )

  const staffQuery = useQuery({
    queryKey: ['admin-staff', page],
    queryFn: () => listStaff(page),
    retry: false,
  })
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['admin-staff'] })
  }
  const createMutation = useMutation({
    mutationFn: createStaff,
    onSuccess: async () => {
      setEmail('')
      setDisplayName('')
      setPassword('')
      setRole('AGENT')
      setValidationError(null)
      await refresh()
    },
  })
  const disableMutation = useMutation({
    mutationFn: disableStaff,
    onSuccess: async () => {
      setDisableCandidate(null)
      await refresh()
    },
  })
  const authorityMutation = useMutation({
    mutationFn: ({
      staffId,
      authority,
      grant,
    }: {
      authority: GrantableAuditAuthority
      grant: boolean
      staffId: string
    }) =>
      grant
        ? grantStaffAuditAuthority(staffId, authority)
        : revokeStaffAuditAuthority(staffId, authority),
    onSuccess: async () => {
      setAuthorityStaff(null)
      await refresh()
    },
  })

  if (staffQuery.isPending) {
    return (
      <AdminStaffScreenState kind="loading" title="직원 계정을 불러오는 중" />
    )
  }
  if (staffQuery.isError) {
    const denied =
      staffQuery.error instanceof ApiError && staffQuery.error.status === 403
    return (
      <AdminStaffScreenState
        action={
          denied ? undefined : (
            <RetryButton onClick={() => void staffQuery.refetch()} />
          )
        }
        description={
          denied
            ? '직원 계정 관리는 ADMIN만 수행할 수 있습니다.'
            : '잠시 후 안전한 직원 목록을 다시 요청해 주세요.'
        }
        kind={denied ? 'denied' : 'error'}
        title={
          denied
            ? '직원 관리 권한이 없습니다.'
            : '직원 계정을 불러오지 못했습니다.'
        }
      />
    )
  }

  const submitCreate = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!email.trim() || !displayName.trim() || !password) {
      setValidationError(
        '이메일, 표시 이름, 초기 비밀번호를 모두 입력해 주세요.',
      )
      return
    }
    setValidationError(null)
    createMutation.mutate({
      email: email.trim(),
      displayName: displayName.trim(),
      password,
      role,
    })
  }

  const staffPage = staffQuery.data
  return (
    <main aria-label="직원 관리" className="admin-page">
      <header className="admin-page-header">
        <div>
          <h1>직원</h1>
          <p>직원 계정과 보안 감사 권한을 ADMIN 정책 안에서 관리합니다.</p>
        </div>
        <DsButton onClick={() => void staffQuery.refetch()} tone="secondary">
          직원 목록 새로고침
        </DsButton>
      </header>

      <section aria-labelledby="create-staff-heading" className="admin-surface">
        <h2 id="create-staff-heading">직원 계정 생성</h2>
        <p>
          초기 비밀번호는 생성 요청에만 사용되며, 성공 후 브라우저 양식에서
          지웁니다.
        </p>
        <form className="admin-form" onSubmit={submitCreate}>
          <div className="admin-form-grid">
            <label className="admin-field" htmlFor="staff-email">
              <span>이메일</span>
              <input
                autoComplete="off"
                id="staff-email"
                maxLength={254}
                onChange={(event) => setEmail(event.target.value)}
                type="email"
                value={email}
              />
            </label>
            <label className="admin-field" htmlFor="staff-display-name">
              <span>표시 이름</span>
              <input
                id="staff-display-name"
                maxLength={100}
                onChange={(event) => setDisplayName(event.target.value)}
                value={displayName}
              />
            </label>
            <label className="admin-field" htmlFor="staff-role">
              <span>역할</span>
              <select
                id="staff-role"
                onChange={(event) => setRole(event.target.value as StaffRole)}
                value={role}
              >
                {Object.entries(ROLE_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <label className="admin-field" htmlFor="staff-initial-password">
              <span>초기 비밀번호</span>
              <input
                autoComplete="new-password"
                id="staff-initial-password"
                maxLength={256}
                onChange={(event) => setPassword(event.target.value)}
                type="password"
                value={password}
              />
            </label>
          </div>
          {validationError ? (
            <Notification title={validationError} tone="warning" />
          ) : null}
          {createMutation.isError ? (
            <MutationNotification
              action="직원 계정을 생성"
              error={createMutation.error}
            />
          ) : null}
          {createMutation.isSuccess ? (
            <Notification title="직원 계정을 만들었습니다." tone="success">
              <p>서버의 현재 직원 목록을 다시 확인했습니다.</p>
            </Notification>
          ) : null}
          <div className="admin-form-actions">
            <DsButton
              disabled={createMutation.isPending}
              tone="primary"
              type="submit"
            >
              {createMutation.isPending
                ? '직원 계정 생성 중…'
                : '직원 계정 생성'}
            </DsButton>
          </div>
        </form>
      </section>

      <section aria-labelledby="staff-list-heading" className="admin-surface">
        <h2 id="staff-list-heading">직원 계정</h2>
        {staffPage.items.length === 0 ? (
          <ScreenState
            compact
            description="새 직원 계정을 만들면 이 목록에서 권한과 상태를 확인할 수 있습니다."
            kind="empty"
            title="등록된 직원 계정이 없습니다."
          />
        ) : (
          <div className="admin-table-wrap">
            <table className="admin-table">
              <caption className="sr-only">직원 계정 목록</caption>
              <thead>
                <tr>
                  <th scope="col">표시 이름</th>
                  <th scope="col">이메일</th>
                  <th scope="col">역할</th>
                  <th scope="col">상태</th>
                  <th scope="col">소속 그룹</th>
                  <th scope="col">감사 권한</th>
                  <th scope="col">작업</th>
                </tr>
              </thead>
              <tbody>
                {staffPage.items.map((staff) => (
                  <tr key={staff.id}>
                    <td>{staff.displayName}</td>
                    <td>{staff.email}</td>
                    <td>{ROLE_LABELS[staff.role]}</td>
                    <td>{staff.status === 'ACTIVE' ? '활성' : '비활성'}</td>
                    <td>
                      {staff.memberships
                        .map((group) => group.name)
                        .join(', ') || '—'}
                    </td>
                    <td>
                      {staff.auditAuthorities.length > 0
                        ? staff.auditAuthorities.join(', ')
                        : '—'}
                    </td>
                    <td>
                      <div className="admin-inline-actions">
                        <DsButton
                          aria-expanded={authorityStaff?.id === staff.id}
                          onClick={() => setAuthorityStaff(staff)}
                          tone="secondary"
                        >
                          감사 권한
                        </DsButton>
                        {staff.status === 'ACTIVE' ? (
                          <DsButton
                            aria-expanded={disableCandidate?.id === staff.id}
                            onClick={() => setDisableCandidate(staff)}
                            tone="secondary"
                          >
                            직원 비활성화
                          </DsButton>
                        ) : null}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {staffPage.totalPages > 1 ? (
          <div className="admin-inline-actions">
            <DsButton
              disabled={page === 0}
              onClick={() => setPage((current) => current - 1)}
              tone="secondary"
            >
              이전 페이지
            </DsButton>
            <span className="admin-muted">{`${page + 1} / ${staffPage.totalPages} 페이지`}</span>
            <DsButton
              disabled={page + 1 >= staffPage.totalPages}
              onClick={() => setPage((current) => current + 1)}
              tone="secondary"
            >
              다음 페이지
            </DsButton>
          </div>
        ) : null}
      </section>

      {authorityStaff ? (
        <section
          aria-labelledby="staff-authorities-heading"
          className="admin-surface"
        >
          <div className="admin-page-header">
            <div>
              <h2 id="staff-authorities-heading">{`${authorityStaff.displayName} 감사 권한`}</h2>
              <p>
                권한 부여/회수는 서버에서 ADMIN 보안 감사와 함께 처리됩니다.
              </p>
            </div>
            <DsButton onClick={() => setAuthorityStaff(null)} tone="secondary">
              닫기
            </DsButton>
          </div>
          <div
            className="admin-check-list"
            role="group"
            aria-label="감사 권한 목록"
          >
            {AUDIT_AUTHORITIES.map((authority) => {
              const granted = authorityStaff.auditAuthorities.includes(
                authority.value,
              )
              return (
                <label key={authority.value}>
                  <input
                    checked={granted}
                    disabled={authorityMutation.isPending}
                    onChange={() =>
                      authorityMutation.mutate({
                        staffId: authorityStaff.id,
                        authority: authority.value,
                        grant: !granted,
                      })
                    }
                    type="checkbox"
                  />
                  {authority.label}
                </label>
              )
            })}
          </div>
          {authorityMutation.isError ? (
            <MutationNotification
              action="감사 권한을 변경"
              error={authorityMutation.error}
            />
          ) : null}
        </section>
      ) : null}

      {disableCandidate ? (
        <section aria-label="직원 비활성화 확인" className="admin-confirmation">
          <p>
            {`${disableCandidate.displayName} 계정을 비활성화하면 직원 세션과 배정 제약을 서버가 다시 검증합니다.`}
          </p>
          <DsButton
            disabled={disableMutation.isPending}
            onClick={() => disableMutation.mutate(disableCandidate.id)}
            tone="primary"
          >
            {disableMutation.isPending ? '비활성화 중…' : '비활성화 확정'}
          </DsButton>
          <DsButton
            disabled={disableMutation.isPending}
            onClick={() => setDisableCandidate(null)}
            tone="secondary"
          >
            취소
          </DsButton>
          {disableMutation.isError ? (
            <MutationNotification
              action="직원을 비활성화"
              error={disableMutation.error}
            />
          ) : null}
        </section>
      ) : null}
    </main>
  )
}

function MutationNotification({
  action,
  error,
}: {
  action: string
  error: unknown
}) {
  const conflict = error instanceof ApiError && error.status === 409
  return (
    <Notification
      title={
        conflict ? `${action}할 수 없습니다.` : `${action}하지 못했습니다.`
      }
      tone={conflict ? 'conflict' : 'danger'}
    >
      <p>
        {conflict
          ? '서버가 현재 상태 제약을 확인했습니다. 최신 목록을 확인한 뒤 다시 결정해 주세요.'
          : '입력은 유지됩니다. 잠시 후 다시 시도해 주세요.'}
      </p>
    </Notification>
  )
}

function AdminStaffScreenState({
  action,
  description,
  kind,
  title,
}: {
  action?: ReactNode
  description?: string
  kind: 'denied' | 'error' | 'loading'
  title: string
}) {
  return (
    <main className="admin-page">
      <ScreenState
        action={action}
        description={description}
        kind={kind}
        title={title}
      />
    </main>
  )
}
