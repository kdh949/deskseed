import { useId, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { CustomerIcon, DsButton, Notification } from '../../design-system'
import authImage from '../../assets/deskseed/customer-auth-mail.png'
import {
  createCustomerPasswordSession,
  requestCustomerMagicLink,
} from './api/customerAuthClient'
import { useOptionalCustomerSession } from './CustomerSessionContext'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function CustomerSignInPage() {
  const navigate = useNavigate()
  const session = useOptionalCustomerSession()
  const [mode, setMode] = useState<'magic' | 'password'>('magic')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [attempted, setAttempted] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [failed, setFailed] = useState(false)
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
    setFailed(false)
    try {
      if (mode === 'magic') {
        await requestCustomerMagicLink(normalizedEmail)
        navigate('/customer/sign-in/check-email', {
          state: { email: normalizedEmail },
        })
      } else {
        const customer = await createCustomerPasswordSession(
          normalizedEmail,
          password,
        )
        session?.acceptAuthenticatedCustomer(customer)
        navigate('/account/requests', { replace: true })
      }
    } catch {
      setFailed(true)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="customer-auth-page">
      <section className="customer-auth-card">
        <h1>DeskSeed에 로그인</h1>
        <p>이메일로 안전한 일회성 링크를 받거나 비밀번호로 로그인하세요.</p>
        <div
          aria-label="로그인 방식"
          className="customer-auth-tabs"
          role="tablist"
        >
          <button
            aria-selected={mode === 'magic'}
            onClick={() => setMode('magic')}
            role="tab"
            type="button"
          >
            이메일 링크
          </button>
          <button
            aria-selected={mode === 'password'}
            onClick={() => setMode('password')}
            role="tab"
            type="button"
          >
            비밀번호
          </button>
        </div>
        {failed ? (
          <Notification title="로그인 요청을 완료할 수 없습니다." tone="danger">
            <p>입력 내용을 유지했습니다. 잠시 후 다시 시도해 주세요.</p>
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
              type="email"
              value={email}
            />
          </div>
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
                  type="password"
                  value={password}
                />
              </div>
            </>
          ) : null}
          <DsButton
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
        <div className="customer-auth-note">
          <CustomerIcon name="check" />
          <p>
            비밀번호와 로그인 링크는 브라우저 기록이나 일반 로그에 남기지
            않습니다.
          </p>
        </div>
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
          title="계정 정보 관리"
          description="프로필과 알림 설정을 관리하세요."
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
