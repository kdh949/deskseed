import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import { ScreenState } from '../../design-system'
import { consumeMagicLinkFragment } from './magicLinkFragment'
import {
  CustomerAuthApiError,
  consumeCustomerMagicLink,
} from './api/customerAuthClient'
import { useCustomerSession } from './CustomerSessionContext'

type ConsumeState = 'consuming' | 'missing' | 'invalid' | 'unavailable'

export function CustomerMagicLinkConsumePage() {
  const navigate = useNavigate()
  const session = useCustomerSession()
  const [state, setState] = useState<ConsumeState>('consuming')
  const started = useRef(false)

  useLayoutEffect(() => {
    if (started.current) return
    started.current = true
    const token = consumeMagicLinkFragment({
      history: window.history,
      location: window.location,
    })
    if (!token) {
      setState('missing')
      return
    }

    void consumeCustomerMagicLink(token)
      .then((customer) => {
        session.acceptAuthenticatedCustomer(customer)
        navigate('/account/requests', { replace: true })
      })
      .catch((error: unknown) => {
        setState(isInvalidLinkError(error) ? 'invalid' : 'unavailable')
      })
  }, [navigate, session])

  if (state === 'consuming') {
    return (
      <CustomerConsumeState
        kind="loading"
        title="로그인 링크를 안전하게 확인하고 있습니다."
      />
    )
  }
  if (state === 'missing') {
    return (
      <CustomerConsumeState
        action={<Link to="/customer/sign-in">새 로그인 링크 요청</Link>}
        description="로그인 링크의 fragment가 없거나 이미 제거되었습니다."
        kind="not-found"
        title="로그인 링크를 찾을 수 없습니다."
      />
    )
  }
  if (state === 'invalid') {
    return (
      <CustomerConsumeState
        action={<Link to="/customer/sign-in">새 로그인 링크 요청</Link>}
        description="링크가 만료되었거나 이미 사용되었습니다. 새 링크를 요청해 주세요."
        kind="denied"
        title="로그인 링크를 사용할 수 없습니다."
      />
    )
  }
  return (
    <CustomerConsumeState
      action={<Link to="/customer/sign-in">새 로그인 링크 요청</Link>}
      description="로그인 링크를 확인하지 못했습니다. 새 링크를 요청한 뒤 다시 시도해 주세요."
      kind="error"
      title="로그인 상태를 만들 수 없습니다."
    />
  )
}

function CustomerConsumeState({
  action,
  description,
  kind,
  title,
}: {
  action?: ReactNode
  description?: string
  kind: 'denied' | 'error' | 'loading' | 'not-found'
  title: string
}) {
  return (
    <main className="customer-page">
      <ScreenState
        action={action}
        description={description}
        kind={kind}
        title={title}
      />
    </main>
  )
}

function isInvalidLinkError(error: unknown) {
  return (
    error instanceof CustomerAuthApiError &&
    (error.status === 400 || error.status === 401)
  )
}
