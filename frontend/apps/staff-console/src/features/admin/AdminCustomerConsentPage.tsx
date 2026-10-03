import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { DsButton, Notification, ScreenState } from '../../design-system'
import {
  ConsentDocumentEditor,
  ConsentDocumentView,
} from './CustomerConsentDocumentFields'
import {
  CONSENT_CONTEXTS,
  CONSENT_LIFECYCLES,
  createConsentPolicy,
  getConsentPolicy,
  listConsentPolicies,
  transitionConsentPolicy,
  updateConsentPolicy,
  validConsentDraft,
  type ConsentContext,
  type ConsentDraft,
  type ConsentPolicy,
} from './customerConsentApi'

const emptyDraft = (): ConsentDraft => ({
  title: '',
  required: false,
  displayOrder: 0,
  document: { schemaVersion: 1, blocks: [{ type: 'paragraph', text: '' }] },
})
const draftOf = (policy: ConsentPolicy): ConsentDraft => ({
  title: policy.draft.title,
  required: policy.draft.required,
  displayOrder: policy.draft.displayOrder,
  document: policy.draft.document,
})

export function AdminCustomerConsentPage() {
  const [context, setContext] = useState('')
  const [lifecycle, setLifecycle] = useState('')
  const [page, setPage] = useState(0)
  const policies = useQuery({
    queryKey: ['admin-consent-policies', context, lifecycle, page],
    queryFn: () => listConsentPolicies(context, lifecycle, page),
    retry: false,
  })
  const [selected, setSelected] = useState<ConsentPolicy | 'new' | null>(null)
  const [key, setKey] = useState('')
  const [draftContext, setDraftContext] =
    useState<ConsentContext>('REGISTRATION')
  const [draft, setDraft] = useState(emptyDraft)
  const [saved, setSaved] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [message, setMessage] = useState('')
  const [mustRefresh, setMustRefresh] = useState(false)
  const [latest, setLatest] = useState<ConsentPolicy | null>(null)
  const [confirmation, setConfirmation] = useState<
    'publish' | 'archive' | null
  >(null)
  const dirty =
    !!selected &&
    (JSON.stringify(draft) !== saved ||
      (selected === 'new' && (key !== '' || draftContext !== 'REGISTRATION')))
  const archived =
    selected !== null && selected !== 'new' && selected.lifecycle === 'ARCHIVED'
  const editing = busy || dirty || mustRefresh

  function acceptPolicy(policy: ConsentPolicy) {
    const next = draftOf(policy)
    setSelected(policy)
    setKey(policy.policyKey)
    setDraftContext(policy.context)
    setDraft(next)
    setSaved(JSON.stringify(next))
    setMustRefresh(false)
    setLatest(null)
    setConfirmation(null)
  }
  function failure(value: unknown) {
    setError(value)
    setMustRefresh(
      !(value instanceof ApiError) ||
        [409, 412].includes(value.status) ||
        value.status >= 500,
    )
  }
  async function openPolicy(id: string) {
    setBusy(true)
    setError(null)
    setMessage('')
    try {
      acceptPolicy(await getConsentPolicy(id))
    } catch (value) {
      setError(value)
    } finally {
      setBusy(false)
    }
  }
  async function save() {
    if (
      !selected ||
      busy ||
      mustRefresh ||
      archived ||
      !validConsentDraft(draft)
    )
      return
    setBusy(true)
    setError(null)
    setMessage('')
    try {
      const policy =
        selected === 'new'
          ? await createConsentPolicy(draft, key, draftContext)
          : await updateConsentPolicy(selected, draft)
      acceptPolicy(policy)
      setMessage('초안을 저장했습니다. 발행 전까지 고객에게 표시되지 않습니다.')
      void policies.refetch()
    } catch (value) {
      failure(value)
    } finally {
      setBusy(false)
    }
  }
  async function transition(action: 'publish' | 'archive') {
    if (
      !selected ||
      selected === 'new' ||
      busy ||
      mustRefresh ||
      dirty ||
      archived
    )
      return
    setBusy(true)
    setError(null)
    setMessage('')
    setConfirmation(null)
    try {
      acceptPolicy(await transitionConsentPolicy(selected, action))
      setMessage(
        action === 'publish'
          ? '정책을 발행했습니다. 지금부터 고객에게 표시됩니다.'
          : '정책을 보관했습니다. 기존 동의 기록은 유지됩니다.',
      )
      void policies.refetch()
    } catch (value) {
      failure(value)
    } finally {
      setBusy(false)
    }
  }
  async function refreshForComparison() {
    if (!selected || busy) return
    setBusy(true)
    setError(null)
    try {
      if (selected === 'new') {
        const result = await policies.refetch()
        if (result.isError) throw result.error
        setMessage(
          '목록에서 생성 결과를 확인해 주세요. 같은 정책 키로 중복 생성할 수 없습니다.',
        )
        setMustRefresh(false)
      } else setLatest(await getConsentPolicy(selected.id))
    } catch (value) {
      setError(value)
    } finally {
      setBusy(false)
    }
  }
  function change(next: ConsentDraft) {
    setDraft(next)
    setMessage('')
    setConfirmation(null)
  }

  if (policies.isPending)
    return (
      <main className="admin-page">
        <ScreenState kind="loading" title="고객 동의 정책을 불러오는 중" />
      </main>
    )
  if (policies.isError)
    return (
      <main className="admin-page">
        <ScreenState
          kind={
            policies.error instanceof ApiError && policies.error.status === 403
              ? 'denied'
              : 'error'
          }
          title="고객 동의 정책을 불러올 수 없습니다."
          description={
            policies.error instanceof ApiError && policies.error.status === 403
              ? '고객 동의 정책 관리 권한이 필요합니다.'
              : '잠시 후 다시 확인해 주세요.'
          }
          requestId={
            policies.error instanceof ApiError
              ? policies.error.requestId
              : undefined
          }
          action={
            <DsButton onClick={() => void policies.refetch()}>
              다시 확인
            </DsButton>
          }
        />
      </main>
    )
  return (
    <main className="admin-page" aria-label="고객 동의 정책 관리">
      <header className="admin-page-header">
        <div>
          <h1>고객 동의 정책</h1>
          <p>
            가입과 문의 접수에 표시할 정책을 작성하고 발행 이력을 관리합니다.
          </p>
        </div>
        <DsButton
          disabled={editing}
          onClick={() => {
            setSelected('new')
            setKey('')
            setDraftContext('REGISTRATION')
            const next = emptyDraft()
            setDraft(next)
            setSaved(JSON.stringify(next))
            setError(null)
            setMessage('')
            setLatest(null)
            setConfirmation(null)
          }}
        >
          정책 만들기
        </DsButton>
      </header>
      {message && (
        <Notification title="처리 결과" tone="success">
          <p>{message}</p>
        </Notification>
      )}
      {error != null && (
        <Notification
          title="요청을 완료하지 못했습니다."
          tone={mustRefresh ? 'conflict' : 'danger'}
        >
          <p>
            {error instanceof ApiError && error.status === 403
              ? '정책을 변경할 권한이 없습니다.'
              : mustRefresh
                ? '작성 내용은 유지했습니다. 최신 정책을 확인하고 비교한 뒤 계속해 주세요.'
                : '정책의 입력값을 확인한 뒤 다시 시도해 주세요.'}
          </p>
          {error instanceof ApiError && error.requestId && (
            <p>요청 ID: {error.requestId}</p>
          )}
        </Notification>
      )}
      <section className="admin-surface" aria-label="정책 목록">
        <div className="admin-form">
          <label className="admin-field">
            <span>적용 위치</span>
            <select
              disabled={editing}
              value={context}
              onChange={(event) => {
                setContext(event.target.value)
                setPage(0)
              }}
            >
              <option value="">전체</option>
              {Object.entries(CONSENT_CONTEXTS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="admin-field">
            <span>정책 상태</span>
            <select
              disabled={editing}
              value={lifecycle}
              onChange={(event) => {
                setLifecycle(event.target.value)
                setPage(0)
              }}
            >
              <option value="">전체</option>
              {Object.entries(CONSENT_LIFECYCLES).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
        </div>
        {!policies.data.items.length ? (
          <ScreenState
            kind="empty"
            title="표시할 정책이 없습니다."
            description="새 정책을 만들거나 목록 조건을 변경해 주세요."
          />
        ) : (
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th>정책 키</th>
                  <th>적용 위치</th>
                  <th>상태</th>
                  <th>현재 발행 버전</th>
                  <th>관리</th>
                </tr>
              </thead>
              <tbody>
                {policies.data.items.map((policy) => (
                  <tr key={policy.id}>
                    <td>{policy.policyKey}</td>
                    <td>{CONSENT_CONTEXTS[policy.context]}</td>
                    <td>{CONSENT_LIFECYCLES[policy.lifecycle]}</td>
                    <td>{policy.publishedVersion ?? '없음'}</td>
                    <td>
                      <DsButton
                        disabled={editing}
                        onClick={() => void openPolicy(policy.id)}
                      >
                        {policy.policyKey} 열기
                      </DsButton>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="admin-form-actions">
          <DsButton
            disabled={editing || page === 0}
            onClick={() => setPage(page - 1)}
          >
            이전 정책 목록
          </DsButton>
          <span>
            {page + 1} / {Math.max(1, policies.data.totalPages)}
          </span>
          <DsButton
            disabled={editing || page + 1 >= policies.data.totalPages}
            onClick={() => setPage(page + 1)}
          >
            다음 정책 목록
          </DsButton>
        </div>
      </section>
      {selected && (
        <section className="admin-surface" aria-label="정책 편집">
          <h2>
            {selected === 'new'
              ? '새 정책 초안'
              : `${selected.policyKey} · ${CONSENT_LIFECYCLES[selected.lifecycle]}`}
          </h2>
          <p>
            저장한 초안을 검토한 뒤 발행하면 즉시 적용됩니다. 기존 발행 문서와
            동의 기록은 바뀌지 않습니다.
          </p>
          {mustRefresh && (
            <DsButton
              disabled={busy}
              onClick={() => void refreshForComparison()}
            >
              {selected === 'new' ? '정책 목록 다시 확인' : '최신 정책과 비교'}
            </DsButton>
          )}
          {latest && (
            <section className="admin-surface" aria-label="최신 서버 초안 비교">
              <h3>최신 서버 초안 · {CONSENT_LIFECYCLES[latest.lifecycle]}</h3>
              <p>
                {latest.draft.title} ·{' '}
                {latest.draft.required ? '필수 동의' : '선택 동의'} · 표시 순서{' '}
                {latest.draft.displayOrder}
              </p>
              <ConsentDocumentView blocks={latest.draft.document.blocks} />
              <div className="admin-form-actions">
                <DsButton
                  onClick={() => {
                    acceptPolicy(latest)
                    setError(null)
                  }}
                >
                  서버 초안 사용
                </DsButton>
                <DsButton
                  disabled={latest.lifecycle === 'ARCHIVED'}
                  onClick={() => {
                    setSelected(latest)
                    setSaved(JSON.stringify(draftOf(latest)))
                    setLatest(null)
                    setMustRefresh(false)
                    setError(null)
                    setMessage(
                      '현재 작성안을 유지했습니다. 검토 후 초안을 저장해 주세요.',
                    )
                  }}
                >
                  현재 작성안 유지
                </DsButton>
              </div>
            </section>
          )}
          <form
            className="admin-form"
            onSubmit={(event) => {
              event.preventDefault()
              void save()
            }}
          >
            <fieldset className="admin-form" disabled={busy || archived}>
              <label className="admin-field">
                <span>정책 키</span>
                <input
                  required
                  maxLength={80}
                  pattern="[a-z][a-z0-9]*(-[a-z0-9]+)*"
                  readOnly={selected !== 'new'}
                  value={key}
                  onChange={(event) => setKey(event.target.value)}
                />
                <small>
                  소문자·숫자·하이픈으로 입력합니다. 만든 뒤에는 변경할 수
                  없습니다.
                </small>
              </label>
              <label className="admin-field">
                <span>정책 적용 위치</span>
                <select
                  disabled={selected !== 'new'}
                  value={draftContext}
                  onChange={(event) =>
                    setDraftContext(event.target.value as ConsentContext)
                  }
                >
                  {Object.entries(CONSENT_CONTEXTS).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="admin-field">
                <span>정책 제목</span>
                <input
                  required
                  maxLength={200}
                  value={draft.title}
                  onChange={(event) =>
                    change({ ...draft, title: event.target.value })
                  }
                />
              </label>
              <label className="admin-field">
                <span>표시 순서</span>
                <input
                  type="number"
                  min={0}
                  max={10000}
                  step={1}
                  required
                  value={
                    Number.isFinite(draft.displayOrder)
                      ? draft.displayOrder
                      : ''
                  }
                  onChange={(event) =>
                    change({
                      ...draft,
                      displayOrder: event.target.valueAsNumber,
                    })
                  }
                />
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={draft.required}
                  onChange={(event) =>
                    change({ ...draft, required: event.target.checked })
                  }
                />
                필수 동의
              </label>
              <h3>정책 문서</h3>
              <p>
                검토된 정책 문구를 입력해 주세요. 문단은 항목별로 나누어
                작성합니다.
              </p>
              <ConsentDocumentEditor
                blocks={draft.document.blocks}
                change={(blocks) =>
                  change({ ...draft, document: { schemaVersion: 1, blocks } })
                }
              />
            </fieldset>
            {!archived && (
              <div className="admin-form-actions">
                <DsButton
                  type="submit"
                  tone="primary"
                  disabled={
                    busy ||
                    mustRefresh ||
                    !validConsentDraft(draft) ||
                    (selected !== 'new' && !dirty)
                  }
                >
                  {busy ? '처리 중…' : '초안 저장'}
                </DsButton>
                <DsButton
                  disabled={busy}
                  onClick={() => {
                    if (selected === 'new') {
                      setSelected(null)
                      setMustRefresh(false)
                      setLatest(null)
                    } else acceptPolicy(selected)
                    setError(null)
                    setMessage('')
                  }}
                >
                  변경 취소
                </DsButton>
              </div>
            )}
          </form>
          {selected !== 'new' && !archived && (
            <div className="admin-form-actions">
              <DsButton
                disabled={busy || dirty || mustRefresh}
                onClick={() => setConfirmation('publish')}
              >
                정책 발행
              </DsButton>
              <DsButton
                disabled={busy || dirty || mustRefresh}
                onClick={() => setConfirmation('archive')}
              >
                정책 보관
              </DsButton>
            </div>
          )}
          {confirmation && (
            <Notification
              title={
                confirmation === 'publish'
                  ? '이 초안을 지금 발행할까요?'
                  : '이 정책의 신규 사용을 중단할까요?'
              }
              tone="warning"
            >
              <p>
                {confirmation === 'publish'
                  ? '새 버전이 즉시 고객에게 표시됩니다. 제목, 적용 위치, 필수 여부와 문서를 확인해 주세요.'
                  : '새 가입과 문의에서 정책이 제외됩니다. 기존 발행 문서와 동의 기록은 유지됩니다.'}
              </p>
              <DsButton
                disabled={busy}
                onClick={() => void transition(confirmation)}
              >
                {confirmation === 'publish' ? '확인 후 발행' : '확인 후 보관'}
              </DsButton>
              <DsButton onClick={() => setConfirmation(null)}>취소</DsButton>
            </Notification>
          )}
          {selected !== 'new' && (
            <section aria-label="정책 발행 이력">
              <h3>발행 이력</h3>
              {!selected.versions.length ? (
                <p>아직 발행한 버전이 없습니다.</p>
              ) : (
                [...selected.versions].reverse().map((version) => (
                  <details key={version.version}>
                    <summary>
                      버전 {version.version} · {version.title}
                      {selected.publishedVersion?.version === version.version
                        ? ' · 현재 적용'
                        : ''}
                    </summary>
                    <p>
                      발행자: {version.publishedByDisplayName} · 발행:{' '}
                      {version.publishedAt} ·{' '}
                      {version.required ? '필수 동의' : '선택 동의'}
                    </p>
                    <ConsentDocumentView blocks={version.document.blocks} />
                  </details>
                ))
              )}
            </section>
          )}
        </section>
      )}
    </main>
  )
}
