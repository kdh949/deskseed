import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import {
  ApiError,
  getCustomerAccessModeSetting,
  updateCustomerAccessModeSetting,
} from '../api/client'
import type { CustomerAccessMode } from '../api/types'
import { Notification, ScreenState } from '../shared/ui/system'

const MODES: Array<{
  value: CustomerAccessMode
  title: string
  description: string
  impact: string
}> = [
  {
    value: 'ANONYMOUS_ALLOWED',
    title: '익명 허용',
    description: '로그인하지 않은 고객도 문의를 접수할 수 있습니다.',
    impact: '기존 익명 접수와 로그인 고객 접수를 모두 유지합니다.',
  },
  {
    value: 'REGISTRATION_OPTIONAL',
    title: '가입 선택',
    description: '익명 접수와 로그인 후 My Requests 사용을 함께 제공합니다.',
    impact: '익명 고객에게 계정 연결 선택지를 안내하되 접수를 막지 않습니다.',
  },
  {
    value: 'REGISTRATION_REQUIRED',
    title: '가입 필수',
    description: '새 문의 접수 전에 email magic-link 로그인이 필요합니다.',
    impact:
      '로그인하지 않은 고객은 새 문의를 접수할 수 없습니다. 기존 조회 키 조회는 유지됩니다.',
  },
]

export function AdminCustomerAccessPage() {
  const queryClient = useQueryClient()
  const resultRef = useRef<HTMLDivElement>(null)
  const setting = useQuery({
    queryKey: ['admin-customer-access-mode'],
    queryFn: getCustomerAccessModeSetting,
  })
  const [selected, setSelected] = useState<CustomerAccessMode | null>(null)
  useEffect(() => {
    if (setting.data && selected === null) setSelected(setting.data.mode)
  }, [selected, setting.data])
  const update = useMutation({
    mutationFn: () =>
      updateCustomerAccessModeSetting({
        mode: selected ?? setting.data!.mode,
        expectedVersion: setting.data!.version,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: ['admin-customer-access-mode'],
      })
      resultRef.current?.focus()
    },
    onError: () => resultRef.current?.focus(),
  })

  if (setting.isPending)
    return (
      <ScreenState kind="loading" title="고객 접근 설정을 불러오고 있습니다." />
    )
  if (setting.isError)
    return (
      <ScreenState
        kind="error"
        title="고객 접근 설정을 불러오지 못했습니다."
        action={
          <button
            className="button primary"
            type="button"
            onClick={() => void setting.refetch()}
          >
            다시 시도
          </button>
        }
      />
    )
  const selectedMode =
    MODES.find((mode) => mode.value === selected) ?? MODES[0]!
  return (
    <section
      className="admin-page admin-customer-access"
      aria-labelledby="customer-access-title"
    >
      <header className="admin-page-header">
        <div>
          <p className="eyebrow">CUSTOMER ACCESS</p>
          <h1 id="customer-access-title">고객 접근 모드</h1>
        </div>
      </header>
      {update.isSuccess ? (
        <Notification
          ref={resultRef}
          tabIndex={-1}
          tone="success"
          title="고객 접근 모드를 저장했습니다."
        />
      ) : null}
      {update.isError ? (
        <Notification
          ref={resultRef}
          tabIndex={-1}
          urgent
          tone={
            update.error instanceof ApiError && update.error.status === 409
              ? 'conflict'
              : 'danger'
          }
          title={
            update.error instanceof ApiError && update.error.status === 409
              ? '설정이 다른 관리자에 의해 변경되었습니다.'
              : '고객 접근 모드를 저장하지 못했습니다.'
          }
        >
          최신 설정을 다시 불러온 뒤 확인해 주세요.
        </Notification>
      ) : null}
      <fieldset className="access-mode-options">
        <legend>문의 접수와 계정 정책</legend>
        {MODES.map((mode) => (
          <label
            key={mode.value}
            className={selected === mode.value ? 'is-selected' : ''}
          >
            <input
              type="radio"
              name="customer-access-mode"
              value={mode.value}
              checked={selected === mode.value}
              onChange={() => {
                setSelected(mode.value)
                update.reset()
              }}
            />
            <span>
              <strong>{mode.title}</strong>
              <small>{mode.description}</small>
            </span>
          </label>
        ))}
      </fieldset>
      <Notification
        tone="info"
        title="변경 영향 미리보기"
        className="access-impact"
      >
        {selectedMode.impact}
      </Notification>
      <div className="admin-form-actions">
        <button
          className="button primary"
          type="button"
          disabled={update.isPending || selected === setting.data.mode}
          onClick={() => update.mutate()}
        >
          {update.isPending ? '저장 중…' : '접근 모드 저장'}
        </button>
        <span className="muted">현재 설정 버전 {setting.data.version}</span>
      </div>
    </section>
  )
}
