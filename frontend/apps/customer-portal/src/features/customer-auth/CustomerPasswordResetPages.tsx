import { useLayoutEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { DsButton, Notification, ScreenState } from '../../design-system'
import {
  CustomerAuthApiError,
  requestCustomerPasswordReset,
  resetCustomerPassword,
} from './api/customerAuthClient'
import { consumeMagicLinkFragment } from './magicLinkFragment'
import { useOptionalCustomerSession } from './CustomerSessionContext'

function failureMessage(error: unknown) {
  return error instanceof CustomerAuthApiError && error.status === 429
    ? '요청이 많습니다. 잠시 후 다시 시도해 주세요.'
    : '요청을 완료하지 못했습니다. 입력 내용을 확인하고 다시 시도해 주세요.'
}

export function CustomerPasswordResetRequestPage() {
  const [email, setEmail] = useState('')
  const [pending, setPending] = useState(false)
  const [sent, setSent] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (pending) return
    setPending(true)
    setFailure(null)
    try {
      await requestCustomerPasswordReset(email.trim())
      setSent(true)
    } catch (error) {
      setFailure(failureMessage(error))
    } finally {
      setPending(false)
    }
  }
  return (
    <div className="customer-page customer-auth-page">
      <h1>비밀번호 재설정</h1>
      <p>비밀번호로 가입한 계정의 이메일을 입력해 주세요.</p>
      {sent && (
        <Notification tone="success" title="받은 편지함을 확인해 주세요.">
          <p>
            재설정 가능한 계정이면 이메일이 전송됩니다. 메일의 링크에서 새
            비밀번호를 설정해 주세요.
          </p>
        </Notification>
      )}
      {failure && (
        <Notification tone="danger" title="재설정 링크를 요청하지 못했습니다.">
          <p>{failure}</p>
        </Notification>
      )}
      <form onSubmit={(event) => void submit(event)}>
        <label htmlFor="reset-email">이메일 주소</label>
        <input
          id="reset-email"
          type="email"
          autoComplete="email"
          maxLength={254}
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
        <DsButton type="submit" disabled={pending}>
          {pending
            ? '요청 중…'
            : sent
              ? '재설정 링크 다시 요청'
              : '재설정 링크 요청'}
        </DsButton>
      </form>
      <p>
        <Link to="/customer/sign-in">로그인으로 돌아가기</Link>
      </p>
    </div>
  )
}

export function CustomerPasswordResetPage() {
  const session = useOptionalCustomerSession()
  const started = useRef(false)
  const token = useRef<string | null>(null)
  const [password, setPassword] = useState('')
  const [state, setState] = useState<
    'loading' | 'ready' | 'pending' | 'complete' | 'invalid'
  >('loading')
  const [failure, setFailure] = useState<string | null>(null)
  useLayoutEffect(() => {
    if (started.current) return
    started.current = true
    token.current = consumeMagicLinkFragment({
      history: window.history,
      location: window.location,
    })
    setState(token.current && token.current.length >= 32 ? 'ready' : 'invalid')
  }, [])
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (state !== 'ready' || !token.current) return
    setState('pending')
    setFailure(null)
    try {
      await resetCustomerPassword(token.current, password)
      token.current = null
      setPassword('')
      setState('complete')
      void session?.retry()
    } catch (error) {
      if (
        error instanceof CustomerAuthApiError &&
        [400, 401].includes(error.status)
      ) {
        token.current = null
        setPassword('')
        setState('invalid')
      } else {
        setState('ready')
        setFailure(failureMessage(error))
      }
    }
  }
  if (state === 'loading' || state === 'invalid')
    return (
      <div className="customer-page">
        <ScreenState
          kind={state === 'loading' ? 'loading' : 'denied'}
          title={
            state === 'loading'
              ? '재설정 링크 확인 중'
              : '재설정 링크를 사용할 수 없습니다.'
          }
          description={
            state === 'loading'
              ? '잠시 기다려 주세요.'
              : '만료되었거나 이미 사용한 링크입니다. 새 링크를 요청해 주세요.'
          }
          action={
            state === 'invalid' ? (
              <Link to="/customer/password-reset">재설정 링크 다시 요청</Link>
            ) : undefined
          }
        />
      </div>
    )
  return (
    <div className="customer-page customer-auth-page">
      <h1>
        {state === 'complete' ? '비밀번호를 변경했습니다.' : '새 비밀번호 설정'}
      </h1>
      {state === 'complete' ? (
        <>
          <p>기존 로그인은 종료되었습니다. 새 비밀번호로 로그인해 주세요.</p>
          <Link to="/customer/sign-in">로그인</Link>
        </>
      ) : (
        <>
          <p>
            12~128자의 새 비밀번호를 입력해 주세요. 변경하면 모든 기기의
            로그인이 종료됩니다.
          </p>
          {failure && (
            <Notification tone="danger" title="비밀번호를 변경하지 못했습니다.">
              <p>{failure}</p>
            </Notification>
          )}
          <form onSubmit={(event) => void submit(event)}>
            <label htmlFor="reset-password">새 비밀번호</label>
            <input
              id="reset-password"
              type="password"
              autoComplete="new-password"
              minLength={12}
              maxLength={128}
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
            <DsButton type="submit" disabled={state === 'pending'}>
              {state === 'pending' ? '변경 중…' : '비밀번호 변경'}
            </DsButton>
          </form>
          <p>
            <Link to="/customer/password-reset">새 재설정 링크 요청</Link>
          </p>
        </>
      )}
    </div>
  )
}
