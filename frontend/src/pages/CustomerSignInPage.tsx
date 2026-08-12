import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import {
  consumeCustomerMagicLink,
  requestCustomerMagicLink,
} from '../features/customer-auth/customerAuthClient'
import { Notification, ScreenState } from '../shared/ui/system'

export function CustomerSignInPage() {
  const [email, setEmail] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [accepted, setAccepted] = useState(false)
  const [error, setError] = useState(false)
  const statusRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (accepted || error) statusRef.current?.focus()
  }, [accepted, error])

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(false)
    try {
      await requestCustomerMagicLink(email)
      setAccepted(true)
    } catch {
      setError(true)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="page customer-auth-page">
      <section
        className="customer-auth-card"
        aria-labelledby="customer-sign-in-title"
      >
        <p className="eyebrow">DESKSEED CUSTOMER</p>
        <h1 id="customer-sign-in-title">이메일로 로그인</h1>
        <p className="muted">
          이메일로 한 번만 사용할 수 있는 로그인 링크를 보내드립니다.
        </p>
        {accepted ? (
          <Notification
            ref={statusRef}
            tabIndex={-1}
            tone="success"
            title="입력한 이메일로 로그인 링크를 보냈습니다."
          >
            계정이 없거나 요청이 제한된 경우에도 보안을 위해 같은 안내가
            표시됩니다.
          </Notification>
        ) : null}
        {error ? (
          <Notification
            ref={statusRef}
            tabIndex={-1}
            tone="danger"
            title="로그인 링크를 요청할 수 없습니다."
          >
            잠시 후 다시 시도해 주세요.
          </Notification>
        ) : null}
        {!accepted ? (
          <form className="customer-auth-form" onSubmit={submit}>
            <label>
              이메일
              <input
                type="email"
                autoComplete="email"
                required
                maxLength={254}
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              />
            </label>
            <button
              className="button primary"
              type="submit"
              disabled={submitting}
            >
              {submitting ? '요청 중…' : '로그인 링크 받기'}
            </button>
          </form>
        ) : null}
        <Link className="customer-auth-back" to="/">
          고객 지원 홈으로 돌아가기
        </Link>
      </section>
    </main>
  )
}

export function CustomerMagicLinkConsumePage() {
  const tokenRef = useRef<string | null | undefined>(undefined)
  if (tokenRef.current === undefined)
    tokenRef.current = takeAndClearMagicLinkToken()
  const startedRef = useRef(false)
  const [state, setState] = useState<'loading' | 'success' | 'error'>(
    tokenRef.current ? 'loading' : 'error',
  )

  useEffect(() => {
    const token = tokenRef.current
    if (!token || startedRef.current) return
    startedRef.current = true
    void consumeCustomerMagicLink(token)
      .then(() => setState('success'))
      .catch(() => setState('error'))
  }, [])

  return (
    <main className="page customer-auth-page">
      <section className="customer-auth-card" aria-live="polite">
        {state === 'loading' ? (
          <ScreenState
            kind="loading"
            title="로그인 링크를 확인하고 있습니다."
          />
        ) : state === 'success' ? (
          <div>
            <Notification tone="success" title="로그인되었습니다.">
              이 브라우저에 안전한 고객 세션이 만들어졌습니다.
            </Notification>
            <Link className="customer-auth-back" to="/">
              고객 지원 홈으로 이동
            </Link>
          </div>
        ) : (
          <ScreenState
            kind="error"
            title="로그인 링크를 사용할 수 없습니다."
            description="링크가 만료되었거나 이미 사용되었습니다. 새 링크를 요청해 주세요."
            action={<Link to="/customer/sign-in">새 로그인 링크 요청</Link>}
          />
        )}
      </section>
    </main>
  )
}

export function takeAndClearMagicLinkToken(): string | null {
  const parameters = new URLSearchParams(window.location.hash.replace(/^#/, ''))
  const token = parameters.get('token')
  // Clear both fragment and any accidental query before the bearer value is sent over the network.
  window.history.replaceState(
    window.history.state,
    '',
    window.location.pathname,
  )
  return token && /^[A-Za-z0-9_-]{1,256}$/.test(token) ? token : null
}
