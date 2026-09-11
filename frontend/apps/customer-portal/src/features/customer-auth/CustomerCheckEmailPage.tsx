import { useState } from 'react'
import { Link, useLocation } from 'react-router'
import { CustomerIcon, DsButton, Notification } from '../../design-system'
import checkEmailImage from '../../assets/deskseed/customer-check-email.png'
import {
  CustomerAuthApiError,
  requestCustomerMagicLink,
} from './api/customerAuthClient'

export function CustomerCheckEmailPage() {
  const location = useLocation()
  const state = location.state as { email?: unknown; purpose?: unknown } | null
  const email = typeof state?.email === 'string' ? state.email : ''
  const registration = state?.purpose === 'registration'
  const [sending, setSending] = useState(false)
  const [notice, setNotice] = useState<{
    message: string
    failed: boolean
  } | null>(null)
  const resend = async () => {
    if (!email || sending || registration) return
    setSending(true)
    setNotice(null)
    try {
      await requestCustomerMagicLink(email)
      setNotice({
        message:
          '로그인 링크를 다시 요청했습니다. 받은 편지함을 확인해 주세요.',
        failed: false,
      })
    } catch (error) {
      setNotice({
        message:
          error instanceof CustomerAuthApiError && error.status === 429
            ? '요청이 잠시 제한되었습니다. 잠시 후 다시 시도해 주세요.'
            : '다시 요청하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        failed: true,
      })
    } finally {
      setSending(false)
    }
  }
  return (
    <div className="customer-check-email-page">
      <ol aria-label={registration ? '가입 진행 단계' : '로그인 진행 단계'}>
        <li className="is-done">
          <CustomerIcon name="check" />
          {registration ? '가입 요청' : '로그인'}
        </li>
        <li className="is-current">
          <span>2</span>이메일 확인
        </li>
        <li>
          <span>3</span>
          {registration ? '가입 완료' : 'DeskSeed 접속'}
        </li>
      </ol>
      <section>
        <img alt="이메일 확인 안내" src={checkEmailImage} />
        <h1>받은 편지함을 확인해 주세요</h1>
        <p>
          {email ? <strong>{email}</strong> : '입력한 이메일 주소'}로{' '}
          {registration ? '가입 확인' : '로그인 링크'} 요청을 보냈습니다.
        </p>
        <p>
          {registration
            ? '가입을 요청한 브라우저에서 이메일 링크를 열어 주세요. 확인을 마치면 비밀번호로 로그인할 수 있습니다.'
            : '이메일 링크를 누르면 로그인할 수 있습니다.'}
        </p>
        <p>메일이 보이지 않으면 스팸함을 확인해 주세요.</p>
        {notice ? (
          <Notification
            title={
              notice.failed ? '다시 요청할 수 없습니다.' : '요청을 보냈습니다.'
            }
            tone={notice.failed ? 'danger' : 'success'}
          >
            <p role={notice.failed ? 'alert' : 'status'}>{notice.message}</p>
          </Notification>
        ) : null}
        {registration ? (
          <Link to="/customer/register">가입 정보 다시 입력하기</Link>
        ) : (
          <DsButton
            disabled={!email || sending}
            icon="reload"
            onClick={() => void resend()}
            tone="ghost"
          >
            {sending ? '요청 중…' : '링크 다시 보내기'}
          </DsButton>
        )}
      </section>
    </div>
  )
}
