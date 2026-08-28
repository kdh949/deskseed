import { useLocation } from 'react-router'
import { CustomerIcon, DsButton } from '../../design-system'
import checkEmailImage from '../../assets/deskseed/customer-check-email.png'
import { requestCustomerMagicLink } from './api/customerAuthClient'

export function CustomerCheckEmailPage() {
  const location = useLocation()
  const email =
    typeof location.state === 'object' &&
    location.state &&
    'email' in location.state
      ? String(location.state.email)
      : ''
  return (
    <div className="customer-check-email-page">
      <ol aria-label="로그인 진행 단계">
        <li className="is-done">
          <CustomerIcon name="check" />
          로그인
        </li>
        <li className="is-current">
          <span>2</span>이메일 확인
        </li>
        <li>
          <span>3</span>DeskSeed 접속
        </li>
      </ol>
      <section>
        <img alt="로그인 링크가 담긴 이메일 일러스트" src={checkEmailImage} />
        <h1>받은 편지함을 확인해 주세요</h1>
        <p>
          {email ? (
            <>
              <strong>{email}</strong> 주소로 로그인 링크 요청을 보냈습니다.
            </>
          ) : (
            '입력한 이메일 주소로 로그인 링크 요청을 보냈습니다.'
          )}
        </p>
        <p>메일의 링크를 누르면 안전하게 계정에 로그인됩니다.</p>
        <div className="customer-check-email-action">
          <CustomerIcon name="clock" />
          링크는 15분 후 만료됩니다.
        </div>
        <hr />
        <p>메일이 보이지 않나요? 스팸함을 확인하거나 다시 요청하세요.</p>
        <DsButton
          disabled={!email}
          icon="reload"
          onClick={() => email && void requestCustomerMagicLink(email)}
          tone="ghost"
        >
          링크 다시 보내기
        </DsButton>
      </section>
    </div>
  )
}
