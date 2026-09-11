import { useLayoutEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { ScreenState } from '../../design-system'
import { consumeMagicLinkFragment } from './magicLinkFragment'
import {
  CustomerAuthApiError,
  verifyCustomerRegistration,
} from './api/customerAuthClient'

export function CustomerRegistrationVerifyPage() {
  const started = useRef(false)
  const [state, setState] = useState<
    'loading' | 'complete' | 'invalid' | 'conflict' | 'error'
  >('loading')
  useLayoutEffect(() => {
    if (started.current) return
    started.current = true
    const token = consumeMagicLinkFragment({
      history: window.history,
      location: window.location,
    })
    if (!token) {
      setState('invalid')
      return
    }
    void verifyCustomerRegistration(token)
      .then(() => setState('complete'))
      .catch((error: unknown) => {
        setState(
          error instanceof CustomerAuthApiError && error.status === 409
            ? 'conflict'
            : error instanceof CustomerAuthApiError &&
                [400, 401].includes(error.status)
              ? 'invalid'
              : 'error',
        )
      })
  }, [])
  const content = (
    {
      loading: ['가입을 확인하고 있습니다.', '잠시 기다려 주세요.'],
      complete: [
        '가입이 완료되었습니다.',
        '이메일과 비밀번호로 로그인해 주세요.',
      ],
      invalid: [
        '가입 확인 링크를 사용할 수 없습니다.',
        '가입을 요청한 브라우저에서 링크를 열어 주세요. 링크가 만료되었거나 이미 사용되었다면 로그인하거나 가입을 다시 요청해 주세요.',
      ],
      conflict: [
        '가입 정보를 다시 확인해 주세요.',
        '가입 약관 또는 계정 상태가 변경되었습니다. 최신 내용을 확인하고 다시 요청해 주세요.',
      ],
      error: [
        '가입 확인을 완료하지 못했습니다.',
        '이미 확인을 마쳤다면 로그인해 주세요. 로그인할 수 없다면 가입을 다시 요청해 주세요.',
      ],
    } as const
  )[state]
  if (state === 'complete')
    return (
      <div className="customer-page">
        <h1>{content[0]}</h1>
        <p>{content[1]}</p>
        <Link to="/customer/sign-in">로그인</Link>
      </div>
    )
  return (
    <div className="customer-page">
      <ScreenState
        kind={
          state === 'loading'
            ? 'loading'
            : state === 'invalid'
              ? 'denied'
              : 'error'
        }
        title={content[0]}
        description={content[1]}
        action={
          state === 'loading' ? undefined : (
            <>
              <Link to="/customer/sign-in">로그인</Link>
              <p>
                <Link to="/customer/register">가입 다시 요청하기</Link>
              </p>
            </>
          )
        }
      />
    </div>
  )
}
