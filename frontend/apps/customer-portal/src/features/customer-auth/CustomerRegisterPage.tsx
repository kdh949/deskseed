import { useQuery } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  CustomerIcon,
  ConsentDocument,
  DsButton,
  Notification,
  RetryButton,
  ScreenState,
} from '../../design-system'
import registrationImage from '../../assets/deskseed/customer-registration.png'
import {
  CustomerAuthApiError,
  listRegistrationConsentPolicies,
  requestCustomerRegistration,
} from './api/customerAuthClient'
import {
  passwordValidationMessage,
  profileValidationMessage,
  registrationFieldLimits,
} from './customerRegistrationValidation'

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
  const [accepted, setAccepted] = useState<string[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
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
  if (!policies.data.length)
    return (
      <div className="customer-page">
        <ScreenState
          kind="empty"
          title="가입 약관을 준비하고 있습니다."
          description="잠시 후 다시 방문해 주세요."
        />
      </div>
    )
  const requiredAccepted = policies.data
    .filter((policy) => policy.required)
    .every((policy) =>
      accepted.includes(`${policy.policyKey}:${policy.version}`),
    )
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!requiredAccepted || !accepted.length || submitting) return
    if (
      passwordValidationMessage(form.password) ||
      profileValidationMessage('displayName', form.displayName) ||
      profileValidationMessage('companyName', form.companyName)
    )
      return
    setSubmitting(true)
    setFailure(null)
    try {
      await requestCustomerRegistration({
        ...form,
        acceptedPolicies: policies.data
          .filter((policy) =>
            accepted.includes(`${policy.policyKey}:${policy.version}`),
          )
          .map(({ policyKey, version }) => ({
            policyKey,
            version,
          })),
      })
      navigate('/customer/sign-in/check-email', {
        state: { email: form.email, purpose: 'registration' },
      })
    } catch (error) {
      if (
        error instanceof CustomerAuthApiError &&
        [400, 409].includes(error.status)
      ) {
        const previous = policies.data
          .map(
            ({ policyKey, version, required }) =>
              `${policyKey}:${version}:${required}`,
          )
          .sort()
          .join('|')
        const refreshed = await policies.refetch()
        const next = refreshed.data
          ?.map(
            ({ policyKey, version, required }) =>
              `${policyKey}:${version}:${required}`,
          )
          .sort()
          .join('|')
        if (refreshed.isSuccess && next !== previous) {
          setAccepted([])
          setFailure(
            '가입 약관이 변경되었습니다. 최신 내용을 확인하고 다시 동의해 주세요.',
          )
          return
        }
      }
      setFailure(
        error instanceof CustomerAuthApiError && error.status === 429
          ? '가입 요청이 잠시 제한되었습니다. 잠시 후 다시 시도해 주세요.'
          : '입력 내용을 유지했습니다. 가입 요청을 확인한 뒤 다시 시도해 주세요.',
      )
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
        {failure ? (
          <Notification title="가입 요청을 완료할 수 없습니다." tone="danger">
            <p>{failure}</p>
          </Notification>
        ) : null}
        <form onSubmit={(event) => void submit(event)}>
          <RegisterField
            field="displayName"
            label="이름"
            onChange={(displayName) =>
              setForm((current) => ({ ...current, displayName }))
            }
            value={form.displayName}
          />
          <RegisterField
            field="email"
            label="이메일"
            onChange={(email) => setForm((current) => ({ ...current, email }))}
            type="email"
            value={form.email}
          />
          <RegisterField
            field="companyName"
            label="회사명"
            onChange={(companyName) =>
              setForm((current) => ({ ...current, companyName }))
            }
            value={form.companyName}
          />
          <RegisterField
            field="password"
            label="비밀번호"
            minLength={12}
            onChange={(password) =>
              setForm((current) => ({ ...current, password }))
            }
            type="password"
            value={form.password}
          />
          <fieldset className="customer-request-additional">
            <legend>가입 동의 항목</legend>
            {policies.data.map((policy) => {
              const key = `${policy.policyKey}:${policy.version}`
              return (
                <div className="customer-field" key={key}>
                  <details>
                    <summary>{policy.title} 내용 보기</summary>
                    <ConsentDocument blocks={policy.blocks} />
                  </details>
                  <label className="customer-checkbox">
                    <input
                      type="checkbox"
                      checked={accepted.includes(key)}
                      required={policy.required}
                      onChange={(event) =>
                        setAccepted((current) =>
                          event.target.checked
                            ? [...current, key]
                            : current.filter((value) => value !== key),
                        )
                      }
                    />
                    <span>
                      {policy.title}에 동의합니다. (
                      {policy.required ? '필수' : '선택'})
                    </span>
                  </label>
                </div>
              )
            })}
          </fieldset>
          <DsButton
            disabled={!requiredAccepted || !accepted.length || submitting}
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
  field,
  label,
  onChange,
  type = 'text',
  value,
  minLength,
}: {
  field: keyof typeof registrationFieldLimits
  label: string
  onChange: (value: string) => void
  type?: string
  value: string
  minLength?: number
}) {
  const errorId = useId()
  const error = value
    ? field === 'password'
      ? passwordValidationMessage(value)
      : field === 'email'
        ? null
        : profileValidationMessage(field, value)
    : null
  return (
    <label>
      {label}
      <span aria-hidden="true"> *</span>
      <input
        autoComplete={type === 'password' ? 'new-password' : undefined}
        maxLength={registrationFieldLimits[field] * (field === 'email' ? 1 : 2)}
        aria-invalid={!!error}
        aria-describedby={error ? errorId : undefined}
        minLength={minLength}
        onChange={(event) => onChange(event.target.value)}
        required
        type={type}
        value={value}
      />
      {error && <span id={errorId}>{error}</span>}
    </label>
  )
}
