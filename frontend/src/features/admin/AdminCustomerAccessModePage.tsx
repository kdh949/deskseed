import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type ReactNode } from 'react'
import {
  ApiError,
  getCustomerAccessModeSetting,
  updateCustomerAccessModeSetting,
} from '../../api/client'
import type { CustomerAccessMode } from '../../api/types'
import {
  DsButton,
  Notification,
  RetryButton,
  ScreenState,
} from '../../design-system'

const MODE_OPTIONS: Array<{
  description: string
  label: string
  value: CustomerAccessMode
}> = [
  {
    value: 'ANONYMOUS_ALLOWED',
    label: '익명 접수 허용',
    description:
      '고객은 계정 없이 새 문의를 접수하고, 별도 access link로 해당 문의만 확인합니다.',
  },
  {
    value: 'REGISTRATION_OPTIONAL',
    label: '가입 선택',
    description:
      '고객은 익명 접수 또는 이메일 access link 기반 계정 로그인을 선택할 수 있습니다.',
  },
  {
    value: 'REGISTRATION_REQUIRED',
    label: '로그인 필수',
    description: '고객은 로그인한 계정에서만 새 문의를 접수합니다.',
  },
]

export function AdminCustomerAccessModePage() {
  const queryClient = useQueryClient()
  const settingsQuery = useQuery({
    queryKey: ['admin-customer-access-mode'],
    queryFn: getCustomerAccessModeSetting,
    retry: false,
  })
  const [localMode, setLocalMode] = useState<CustomerAccessMode | null>(null)
  const [dirty, setDirty] = useState(false)
  const updateMutation = useMutation({
    mutationFn: updateCustomerAccessModeSetting,
    onSuccess: (setting) => {
      queryClient.setQueryData(['admin-customer-access-mode'], setting)
      setLocalMode(setting.mode)
      setDirty(false)
    },
  })

  useEffect(() => {
    if (settingsQuery.data && !dirty) setLocalMode(settingsQuery.data.mode)
  }, [dirty, settingsQuery.data])

  if (settingsQuery.isPending) {
    return (
      <AdminAccessModeScreenState
        kind="loading"
        title="고객 접근 모드를 불러오는 중"
      />
    )
  }
  if (settingsQuery.isError) {
    const denied =
      settingsQuery.error instanceof ApiError &&
      settingsQuery.error.status === 403
    return (
      <AdminAccessModeScreenState
        action={
          denied ? undefined : (
            <RetryButton onClick={() => void settingsQuery.refetch()} />
          )
        }
        description={
          denied
            ? '고객 접근 정책은 ADMIN만 변경할 수 있습니다.'
            : '잠시 후 현재 정책을 다시 요청해 주세요.'
        }
        kind={denied ? 'denied' : 'error'}
        title={
          denied
            ? '고객 접근 정책 권한이 없습니다.'
            : '고객 접근 모드를 불러오지 못했습니다.'
        }
      />
    )
  }
  if (localMode === null) {
    return (
      <AdminAccessModeScreenState
        kind="loading"
        title="고객 접근 모드를 준비하는 중"
      />
    )
  }

  const setting = settingsQuery.data
  const selected = MODE_OPTIONS.find((option) => option.value === localMode)!
  const conflict =
    updateMutation.error instanceof ApiError &&
    updateMutation.error.status === 409

  return (
    <main aria-label="고객 접근 모드 설정" className="admin-page">
      <header className="admin-page-header">
        <div>
          <h1>고객 접근 모드</h1>
          <p>
            새 문의 접수와 고객 계정 로그인의 허용 방식을 운영 정책으로
            선택합니다.
          </p>
        </div>
        <DsButton
          disabled={settingsQuery.isFetching}
          onClick={() => void settingsQuery.refetch()}
          tone="secondary"
        >
          서버 값 새로고침
        </DsButton>
      </header>

      <section
        aria-labelledby="customer-access-mode-heading"
        className="admin-surface"
      >
        <h2 id="customer-access-mode-heading">현재 정책</h2>
        <form
          className="admin-form"
          onSubmit={(event) => {
            event.preventDefault()
            updateMutation.mutate({
              mode: localMode,
              expectedVersion: setting.version,
            })
          }}
        >
          <label className="admin-field" htmlFor="customer-access-mode">
            <span>고객 접근 모드</span>
            <select
              id="customer-access-mode"
              onChange={(event) => {
                setLocalMode(event.target.value as CustomerAccessMode)
                setDirty(event.target.value !== setting.mode)
                updateMutation.reset()
              }}
              value={localMode}
            >
              {MODE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <Notification title={selected.label} tone="info">
            <p>{selected.description}</p>
          </Notification>
          <dl className="admin-definition-list">
            <div>
              <dt>서버 version</dt>
              <dd>{setting.version}</dd>
            </div>
            <div>
              <dt>마지막 갱신</dt>
              <dd>{formatTimestamp(setting.updatedAt)}</dd>
            </div>
          </dl>
          {conflict ? (
            <Notification
              title="다른 관리자가 고객 접근 정책을 변경했습니다."
              tone="conflict"
            >
              <p>
                선택한 값은 보존했습니다. 서버 값을 새로고침한 뒤 현재
                version으로 다시 저장하거나, 아래에서 서버 값을 적용해 주세요.
              </p>
            </Notification>
          ) : updateMutation.isError ? (
            <Notification
              title="고객 접근 정책을 저장하지 못했습니다."
              tone="danger"
            >
              <p>선택한 값은 보존했습니다. 잠시 후 다시 시도해 주세요.</p>
            </Notification>
          ) : null}
          {updateMutation.isSuccess ? (
            <Notification title="고객 접근 정책을 저장했습니다." tone="success">
              <p>서버가 새 version을 확정했습니다.</p>
            </Notification>
          ) : null}
          <div className="admin-form-actions">
            <DsButton
              disabled={updateMutation.isPending || !dirty}
              tone="primary"
              type="submit"
            >
              {updateMutation.isPending ? '정책 저장 중…' : '정책 저장'}
            </DsButton>
            <DsButton
              disabled={!dirty || updateMutation.isPending}
              onClick={() => {
                setLocalMode(setting.mode)
                setDirty(false)
                updateMutation.reset()
              }}
              tone="secondary"
              type="button"
            >
              서버 값 적용
            </DsButton>
          </div>
        </form>
      </section>
    </main>
  )
}

function AdminAccessModeScreenState({
  action,
  description,
  kind,
  title,
}: {
  action?: ReactNode
  description?: string
  kind: 'denied' | 'error' | 'loading'
  title: string
}) {
  return (
    <main className="admin-page">
      <ScreenState
        action={action}
        description={description}
        kind={kind}
        title={title}
      />
    </main>
  )
}

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
