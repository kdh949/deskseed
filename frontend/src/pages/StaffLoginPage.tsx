import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import { useStaffSession } from '../features/staff-auth/StaffSessionContext'
import { Notification, ScreenState } from '../shared/ui/system'
import type { StaffRole } from '../api/types'

function defaultDestination(role: StaffRole): string {
  if (role === 'ADMIN') return '/admin/staff'
  if (role === 'SECURITY_AUDITOR') return '/audit/activity'
  return '/agent/home'
}

function safeDestination(value: unknown, role: StaffRole): string {
  const allowedPrefix =
    role === 'ADMIN'
      ? ['/admin', '/agent']
      : role === 'SECURITY_AUDITOR'
        ? ['/audit']
        : ['/agent']
  if (
    typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    allowedPrefix.some((prefix) => value.startsWith(prefix))
  ) {
    return value
  }
  return defaultDestination(role)
}

export function StaffLoginPage() {
  const session = useStaffSession()
  const location = useLocation()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const alertRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (error) alertRef.current?.focus()
  }, [error])

  if (session.status === 'loading') {
    return (
      <main className="staff-login-page">
        <ScreenState
          kind="loading"
          compact
          title="직원 세션을 확인하고 있습니다."
        />
      </main>
    )
  }
  if (session.status === 'authenticated' && session.staff) {
    return (
      <Navigate to={safeDestination(undefined, session.staff.role)} replace />
    )
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      const staff = await session.signIn(email, password)
      const from = (location.state as { from?: unknown } | null)?.from
      navigate(safeDestination(from, staff.role), { replace: true })
    } catch (caught) {
      setError(
        caught instanceof ApiError && caught.status === 429
          ? '로그인 시도가 잠시 제한되었습니다. 잠시 후 다시 시도해 주세요.'
          : caught instanceof ApiError && caught.status === 401
            ? '이메일 또는 비밀번호가 올바르지 않습니다.'
            : '로그인 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="staff-login-page">
      <section className="staff-login-card" aria-labelledby="staff-login-title">
        <p className="eyebrow">DESKSEED STAFF</p>
        <h1 id="staff-login-title">직원 로그인</h1>
        <p className="muted">
          상담사, 관리자 및 보안 감사자 계정으로 허용된 작업 공간에 접속합니다.
        </p>
        {error ? (
          <Notification
            tone="danger"
            title={error}
            tabIndex={-1}
            ref={alertRef}
          />
        ) : null}
        <form className="staff-login-form" onSubmit={submit}>
          <label>
            이메일
            <input
              type="email"
              autoComplete="username"
              required
              maxLength={254}
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <label>
            비밀번호
            <input
              type="password"
              autoComplete="current-password"
              required
              maxLength={128}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <button
            className="button primary"
            type="submit"
            disabled={submitting}
          >
            {submitting ? '로그인 중…' : '로그인'}
          </button>
        </form>
      </section>
    </main>
  )
}
