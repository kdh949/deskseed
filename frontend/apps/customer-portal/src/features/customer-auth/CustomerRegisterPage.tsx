import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  CustomerIcon,
  DsButton,
  Notification,
  RetryButton,
  ScreenState,
} from '../../design-system'
import registrationImage from '../../assets/deskseed/customer-registration.png'
import {
  listRegistrationConsentPolicies,
  requestCustomerRegistration,
} from './api/customerAuthClient'

export function CustomerRegisterPage() {
  const navigate = useNavigate()
  const policies = useQuery({
    queryKey: ['customer', 'consent', 'registration'],
    queryFn: listRegistrationConsentPolicies,
  })
  const [form, setForm] = useState({
    displayName: '',
    email: '',
    companyName: '',
    password: '',
  })
  const [accepted, setAccepted] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [failed, setFailed] = useState(false)
  if (policies.isPending)
    return (
      <div className="customer-page">
        <ScreenState kind="loading" title="가입 약관을 확인하고 있습니다." />
      </div>
    )
  if (policies.isError)
    return (
      <div className="customer-page">
        <ScreenState
          action={<RetryButton onClick={() => void policies.refetch()} />}
          kind="error"
          title="가입 약관을 불러올 수 없습니다."
        />
      </div>
    )
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!accepted || submitting) return
    setSubmitting(true)
    setFailed(false)
    try {
      await requestCustomerRegistration({
        ...form,
        acceptedPolicies: policies.data.map(({ policyKey, version }) => ({
          policyKey,
          version,
        })),
      })
      navigate('/customer/sign-in/check-email', {
        state: { email: form.email },
      })
    } catch {
      setFailed(true)
    } finally {
      setSubmitting(false)
    }
  }
  return (
    <div className="customer-register-page">
      <section className="customer-register-card">
        <span className="customer-breadcrumb">
          <Link to="/">홈</Link> / 회원가입
        </span>
        <h1>DeskSeed 계정 만들기</h1>
        <p>문의 접수와 답변 확인을 더 빠르고 안전하게 이용하세요.</p>
        {failed ? (
          <Notification title="가입 요청을 완료할 수 없습니다." tone="danger">
            <p>입력 내용을 유지했습니다. 잠시 후 다시 시도해 주세요.</p>
          </Notification>
        ) : null}
        <form onSubmit={(event) => void submit(event)}>
          <RegisterField
            label="이름"
            onChange={(displayName) =>
              setForm((current) => ({ ...current, displayName }))
            }
            value={form.displayName}
          />
          <RegisterField
            label="이메일"
            onChange={(email) => setForm((current) => ({ ...current, email }))}
            type="email"
            value={form.email}
          />
          <RegisterField
            label="회사명"
            onChange={(companyName) =>
              setForm((current) => ({ ...current, companyName }))
            }
            value={form.companyName}
          />
          <RegisterField
            label="비밀번호"
            minLength={12}
            onChange={(password) =>
              setForm((current) => ({ ...current, password }))
            }
            type="password"
            value={form.password}
          />
          <label className="customer-checkbox">
            <input
              checked={accepted}
              onChange={(event) => setAccepted(event.target.checked)}
              type="checkbox"
            />
            <span>
              {policies.data.length
                ? policies.data.map((policy) => policy.title).join(', ')
                : '이용약관 및 개인정보 처리방침'}
              에 동의합니다.
            </span>
          </label>
          <DsButton
            disabled={!accepted || submitting}
            tone="primary"
            type="submit"
          >
            {submitting ? '가입 요청 중…' : '계정 만들기'}
          </DsButton>
        </form>
        <p className="customer-auth-switch">
          이미 계정이 있나요? <Link to="/customer/sign-in">로그인</Link>
        </p>
      </section>
      <aside className="customer-register-benefits">
        <img
          alt="새 고객 계정을 표현한 노트북 일러스트"
          src={registrationImage}
        />
        <h2>DeskSeed에 오신 것을 환영합니다!</h2>
        <p>문의부터 업데이트까지 한곳에서 관리할 수 있어요.</p>
        {[
          ['plus', '더 빠른 문의 접수'],
          ['speechBubble', '답변 업데이트 확인'],
          ['inbox', '문의와 계정 정보 보관'],
          ['book', '새 소식과 도움말 탐색'],
        ].map(([icon, title]) => (
          <div key={title}>
            <span>
              <CustomerIcon name={icon as 'plus'} size="lg" />
            </span>
            <strong>{title}</strong>
          </div>
        ))}
      </aside>
    </div>
  )
}

function RegisterField({
  label,
  onChange,
  type = 'text',
  value,
  minLength,
}: {
  label: string
  onChange: (value: string) => void
  type?: string
  value: string
  minLength?: number
}) {
  return (
    <label>
      {label}
      <span aria-hidden="true"> *</span>
      <input
        autoComplete={type === 'password' ? 'new-password' : undefined}
        minLength={minLength}
        onChange={(event) => onChange(event.target.value)}
        required
        type={type}
        value={value}
      />
    </label>
  )
}
