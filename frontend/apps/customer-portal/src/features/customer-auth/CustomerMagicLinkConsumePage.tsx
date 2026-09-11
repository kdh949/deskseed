import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { ScreenState } from '../../design-system'
import { consumeMagicLinkFragment } from './magicLinkFragment'
import {
  CustomerAuthApiError,
  consumeCustomerMagicLink,
} from './api/customerAuthClient'
import { useCustomerSession } from './CustomerSessionContext'

import {
  customerAuthDestination,
  readCustomerAuthContinuation,
  takeCustomerAuthDestination,
} from './customerAuthDestination'

type ConsumeState = 'consuming' | 'missing' | 'invalid' | 'unavailable'

export function CustomerMagicLinkConsumePage() {
  const navigate = useNavigate()
  const location = useLocation()
  const session = useCustomerSession()
  const [state, setState] = useState<ConsumeState>('consuming')
  const started = useRef(false)
  const [continuation] = useState(readCustomerAuthContinuation)

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
        const remembered = continuation
          ? takeCustomerAuthDestination(continuation.id)
          : null
        const from =
          (location.state as { from?: unknown } | null)?.from ?? remembered
        navigate(customerAuthDestination(customer, from), {
          replace: true,
          state: { from },
        })
      })
      .catch((error: unknown) => {
        setState(isInvalidLinkError(error) ? 'invalid' : 'unavailable')
      })
  }, [navigate, session, location.state, continuation])

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
        action={
          <Link
            to="/customer/sign-in"
            state={{
              from:
                continuation && continuation.expiresAt > Date.now()
                  ? continuation.from
                  : undefined,
            }}
          >
            새 로그인 링크 요청
          </Link>
        }
        description="이 링크로 로그인할 수 없습니다. 이메일 주소를 입력해 새 링크를 받아 주세요."
        kind="not-found"
        title="로그인 링크를 찾을 수 없습니다."
      />
    )
  }
  if (state === 'invalid') {
    return (
      <CustomerConsumeState
        action={
          <Link
            to="/customer/sign-in"
            state={{
              from:
                continuation && continuation.expiresAt > Date.now()
                  ? continuation.from
                  : undefined,
            }}
          >
            새 로그인 링크 요청
          </Link>
        }
        description="링크가 만료되었거나 이미 사용되었습니다. 새 링크를 요청해 주세요."
        kind="denied"
        title="로그인 링크를 사용할 수 없습니다."
      />
    )
  }
  return (
    <CustomerConsumeState
      action={
        <Link
          to="/customer/sign-in"
          state={{
            from:
              continuation && continuation.expiresAt > Date.now()
                ? continuation.from
                : undefined,
          }}
        >
          새 로그인 링크 요청
        </Link>
      }
      description="로그인 링크를 확인하지 못했습니다. 새 링크를 요청한 뒤 다시 시도해 주세요."
      kind="error"
      title="로그인할 수 없습니다."
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
    <div className="customer-page">
      <ScreenState
        action={action}
        description={description}
        kind={kind}
        title={title}
      />
    </div>
  )
}

function isInvalidLinkError(error: unknown) {
  return (
    error instanceof CustomerAuthApiError &&
    (error.status === 400 || error.status === 401)
  )
}
