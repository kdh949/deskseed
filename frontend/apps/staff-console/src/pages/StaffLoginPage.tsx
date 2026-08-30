import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import { useStaffSession } from '../features/staff-auth/StaffSessionContext'
import {
  SeedAvatar,
  SeedButton,
  SeedFeedbackState,
  SeedIcon,
  SeedLoginShell,
  SeedNotice,
  SeedStatusBadge,
  SeedTextField,
} from '../design-system/canonical'

function safeDestination(value: unknown): string {
  if (
    typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    (value.startsWith('/agent/views/') ||
      value.startsWith('/agent/tickets/') ||
      value === '/agent/search')
  ) {
    return value
  }
  return '/agent/views/my-open'
}

function LoginWorkspacePreview() {
  return (
    <div className="seed-login-demo">
      <aside>
        <SeedIcon name="leaf" size="large" />
        <SeedIcon name="home" />
        <SeedIcon name="ticket" />
        <SeedIcon name="users" />
        <SeedIcon name="settings" />
      </aside>
      <header>
        <strong>DS-48219</strong>
        <span>비밀번호 재설정 후 로그인할 수 없습니다</span>
      </header>
      <section>
        <label>
          상태 <SeedStatusBadge tone="positive">처리 중</SeedStatusBadge>
        </label>
        <label>
          담당자{' '}
          <span>
            <SeedAvatar initials="AR" label="상담사" size="small" /> Alex Rivera
          </span>
        </label>
        <label>
          우선순위 <strong>높음</strong>
        </label>
      </section>
      <div className="seed-login-demo__conversation">
        <span>
          <SeedAvatar initials="JW" label="고객" size="small" />
          <i />
        </span>
        <span>
          <SeedAvatar initials="AR" label="상담사" size="small" />
          <i />
        </span>
        <span>
          <SeedAvatar initials="JW" label="고객" size="small" />
          <i />
        </span>
      </div>
    </div>
  )
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
      <main className="seed-login-loading">
        <SeedFeedbackState
          compact
          kind="loading"
          title="직원 세션을 확인하고 있습니다."
        />
      </main>
    )
  }
  if (session.status === 'authenticated' && session.staff) {
    return <Navigate to={safeDestination(undefined)} replace />
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await session.signIn(email, password)
      const from = (location.state as { from?: unknown } | null)?.from
      navigate(safeDestination(from), { replace: true })
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
    <SeedLoginShell
      description="티켓을 확인하고 팀과 협업하며 고객에게 정확한 답변을 전달하세요."
      footer="Deskseed 직원 계정은 조직 관리자가 발급합니다."
      preview={<LoginWorkspacePreview />}
      title="좋은 지원은 여기서 시작됩니다"
    >
      <h2 id="staff-login-title">Deskseed 로그인</h2>
      <p>직원 계정으로 계속하세요.</p>
      {error && (
        <div ref={alertRef} tabIndex={-1}>
          <SeedNotice title="로그인 실패" tone="danger">
            {error}
          </SeedNotice>
        </div>
      )}
      <form
        className="seed-login-form"
        onSubmit={submit}
        aria-labelledby="staff-login-title"
      >
        <SeedTextField
          autoComplete="username"
          label="이메일"
          leadingIcon="mail"
          maxLength={254}
          onChange={(event) => setEmail(event.target.value)}
          required
          type="email"
          value={email}
        />
        <SeedTextField
          autoComplete="current-password"
          label="비밀번호"
          leadingIcon="lock"
          maxLength={128}
          onChange={(event) => setPassword(event.target.value)}
          required
          type="password"
          value={password}
        />
        <SeedButton disabled={submitting} type="submit" variant="primary">
          {submitting ? '로그인 중…' : '로그인'}
        </SeedButton>
      </form>
      <p className="seed-login-account-copy">
        처음 이용하시나요? 조직 관리자에게 계정을 요청하세요.
      </p>
    </SeedLoginShell>
  )
}
