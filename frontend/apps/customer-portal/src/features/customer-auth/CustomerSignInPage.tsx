import { useId, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { CustomerIcon, DsButton, Notification } from '../../design-system'
import authImage from '../../assets/deskseed/customer-auth-mail.png'
import {
  CustomerAuthApiError,
  createCustomerPasswordSession,
  requestCustomerMagicLink,
} from './api/customerAuthClient'
import { useOptionalCustomerSession } from './CustomerSessionContext'

import {
  customerAuthDestination,
  rememberCustomerAuthDestination,
} from './customerAuthDestination'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function CustomerSignInPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const session = useOptionalCustomerSession()
  const [mode, setMode] = useState<'magic' | 'password'>('password')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [attempted, setAttempted] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const emailId = useId()
  const passwordId = useId()
  const invalidEmail = attempted && !EMAIL_PATTERN.test(email.trim())

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setAttempted(true)
    const normalizedEmail = email.trim()
    if (!EMAIL_PATTERN.test(normalizedEmail) || submitting) return
    if (mode === 'password' && !password) return
    setSubmitting(true)
    setFailure(null)
    try {
      if (mode === 'magic') {
        await requestCustomerMagicLink(normalizedEmail)
        rememberCustomerAuthDestination(
          (location.state as { from?: unknown } | null)?.from,
        )
        navigate('/customer/sign-in/check-email', {
          state: { email: normalizedEmail },
        })
      } else {
        const customer = await createCustomerPasswordSession(
          normalizedEmail,
          password,
        )
        session?.acceptAuthenticatedCustomer(customer)
        navigate(
          customerAuthDestination(
            customer,
            (location.state as { from?: unknown } | null)?.from,
          ),
          { replace: true },
        )
      }
    } catch (error) {
      setFailure(
        error instanceof CustomerAuthApiError && error.status === 401
          ? '이메일 또는 비밀번호가 올바르지 않습니다.'
          : error instanceof CustomerAuthApiError && error.status === 429
            ? '로그인 요청이 잠시 제한되었습니다. 잠시 후 다시 시도해 주세요.'
            : '입력 내용을 유지했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="customer-auth-page">
      <section className="customer-auth-card">
        <h1>DeskSeed에 로그인</h1>
        <p>이메일과 비밀번호로 로그인하세요.</p>
        <p>
          <Link to="/customer/password-reset">비밀번호를 잊으셨나요?</Link>
        </p>
        <div
          aria-label="로그인 방식"
          className="customer-auth-tabs"
          role="group"
        >
          <button
            aria-pressed={mode === 'magic'}
            onClick={() => setMode('magic')}
            type="button"
          >
            이메일 링크
          </button>
          <button
            aria-pressed={mode === 'password'}
            onClick={() => setMode('password')}
            type="button"
          >
            비밀번호
          </button>
        </div>
        {mode === 'magic' ? (
          <p>비밀번호 없이 문의한 고객은 이메일 링크로 로그인할 수 있습니다.</p>
        ) : null}
        {failure ? (
          <Notification title="로그인 요청을 완료할 수 없습니다." tone="danger">
            <p>{failure}</p>
          </Notification>
        ) : null}
        <form onSubmit={(event) => void submit(event)}>
          <label htmlFor={emailId}>
            이메일 주소<span aria-hidden="true"> *</span>
          </label>
          <div className="customer-input-with-icon">
            <CustomerIcon name="mail" />
            <input
              aria-invalid={invalidEmail || undefined}
              autoComplete="email"
              id={emailId}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="you@company.com"
              required
              type="email"
              value={email}
            />
          </div>
          {attempted && mode === 'password' && !password ? (
            <small role="alert">비밀번호를 입력해 주세요.</small>
          ) : null}
          {invalidEmail ? (
            <small role="alert">올바른 이메일 주소를 입력해 주세요.</small>
          ) : null}
          {mode === 'password' ? (
            <>
              <label htmlFor={passwordId}>
                비밀번호<span aria-hidden="true"> *</span>
              </label>
              <div className="customer-input-with-icon">
                <CustomerIcon name="lock" />
                <input
                  autoComplete="current-password"
                  id={passwordId}
                  onChange={(event) => setPassword(event.target.value)}
                  required
                  aria-invalid={(attempted && !password) || undefined}
                  type="password"
                  value={password}
                />
              </div>
            </>
          ) : null}
          <DsButton
            disabled={submitting}
            icon={mode === 'magic' ? 'mail' : 'lock'}
            tone="primary"
            type="submit"
          >
            {submitting
              ? '요청 중…'
              : mode === 'magic'
                ? '로그인 링크 보내기'
                : '로그인'}
          </DsButton>
        </form>
        <p className="customer-auth-switch">
          아직 계정이 없나요? <Link to="/customer/register">회원가입</Link>
        </p>
      </section>
      <aside className="customer-auth-visual">
        <img
          alt="이메일 로그인 링크를 표현한 노트북 일러스트"
          src={authImage}
        />
        <Feature
          icon="inbox"
          title="문의 상태 확인"
          description="요청 상태와 답변을 한곳에서 확인하세요."
        />
        <Feature
          icon="book"
          title="도움말 문서 탐색"
          description="필요한 답을 빠르게 검색할 수 있어요."
        />
        <Feature
          icon="user"
          title="안전한 로그인"
          description="로그인한 뒤 내 문의와 답변을 확인하세요."
        />
      </aside>
    </div>
  )
}

function Feature({
  icon,
  title,
  description,
}: {
  icon: 'inbox' | 'book' | 'user'
  title: string
  description: string
}) {
  return (
    <div className="customer-auth-feature">
      <span>
        <CustomerIcon name={icon} size="lg" />
      </span>
      <div>
        <h2>{title}</h2>
        <p>{description}</p>
      </div>
    </div>
  )
}
