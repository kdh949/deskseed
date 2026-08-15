import { useId, useState, type FormEvent } from 'react'
import { DsButton, Notification } from '../../design-system'
import { requestCustomerMagicLink } from './api/customerAuthClient'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function CustomerSignInPage() {
  const [email, setEmail] = useState('')
  const [attempted, setAttempted] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<'accepted' | 'error' | null>(null)
  const emailId = useId()
  const errorId = `${emailId}-error`
  const emailError =
    attempted && !EMAIL_PATTERN.test(email.trim())
      ? '올바른 이메일 주소를 입력해 주세요.'
      : null

  const requestLink = async () => {
    setAttempted(true)
    const normalizedEmail = email.trim()
    if (!EMAIL_PATTERN.test(normalizedEmail) || submitting) return
    setSubmitting(true)
    setResult(null)
    try {
      await requestCustomerMagicLink(normalizedEmail)
      setResult('accepted')
    } catch {
      setResult('error')
    } finally {
      setSubmitting(false)
    }
  }

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    void requestLink()
  }

  return (
    <main className="customer-page">
      <section
        aria-labelledby="customer-sign-in-title"
        className="customer-sign-in-card"
      >
        <p className="customer-page-eyebrow">고객 로그인</p>
        <h1 id="customer-sign-in-title">이메일로 로그인 링크 받기</h1>
        <p>
          입력한 이메일 주소가 유효하면 일회성 로그인 링크를 보냅니다. 계정 존재
          여부는 안내하지 않습니다.
        </p>
        {result === 'accepted' ? (
          <Notification title="로그인 링크 요청을 받았습니다." tone="success">
            <p>입력한 이메일 주소가 유효하면 로그인 링크를 보냈습니다.</p>
          </Notification>
        ) : result === 'error' ? (
          <Notification title="로그인 링크를 요청할 수 없습니다." tone="danger">
            <p>
              입력한 이메일 주소를 유지했습니다. 잠시 후 다시 시도해 주세요.
            </p>
          </Notification>
        ) : null}
        <form className="customer-sign-in-form" onSubmit={handleSubmit}>
          <label htmlFor={emailId}>
            이메일
            <input
              aria-describedby={emailError ? errorId : undefined}
              aria-invalid={emailError ? 'true' : undefined}
              autoComplete="email"
              id={emailId}
              inputMode="email"
              maxLength={254}
              onChange={(event) => {
                setEmail(event.target.value)
                setResult(null)
              }}
              type="email"
              value={email}
            />
          </label>
          {emailError ? (
            <small id={errorId} role="alert">
              {emailError}
            </small>
          ) : null}
          <DsButton
            disabled={submitting}
            onClick={() => void requestLink()}
            tone="primary"
          >
            {submitting ? '로그인 링크 요청 중…' : '로그인 링크 보내기'}
          </DsButton>
        </form>
      </section>
    </main>
  )
}
