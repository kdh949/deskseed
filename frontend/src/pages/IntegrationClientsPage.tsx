import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from 'react'
import {
  ApiError,
  createIntegrationClient,
  disableIntegrationClient,
  listIntegrationClients,
  revokeIntegrationClient,
  rotateIntegrationClientCredential,
} from '../api/client'
import type {
  IntegrationClient,
  IntegrationCredentialIssue,
  IntegrationScope,
  IntegrationTicketField,
  IntegrationTicketKind,
} from '../api/types'
import { Notification, ScreenState } from '../shared/ui/system'

const PAGE_SIZE = 20
const SCOPES: ReadonlyArray<{ value: IntegrationScope; label: string }> = [
  { value: 'tickets:create', label: '티켓 생성' },
  { value: 'tickets:read', label: '티켓 조회' },
  { value: 'tickets:update', label: '티켓 필드 수정' },
  { value: 'tickets:comment:internal', label: '내부 메모 작성' },
]
const FIELDS: ReadonlyArray<IntegrationTicketField> = [
  'status',
  'priority',
  'groupId',
  'assigneeId',
]
const KINDS: ReadonlyArray<IntegrationTicketKind> = [
  'CUSTOMER_REQUEST',
  'INTERNAL_TASK',
]

function futureLocalDate(days: number) {
  const date = new Date()
  date.setDate(date.getDate() + days)
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset())
  return date.toISOString().slice(0, 16)
}

function toIso(localDate: string) {
  return new Date(localDate).toISOString()
}

function integrationError(error: unknown) {
  if (!(error instanceof ApiError))
    return '연동 클라이언트 요청을 처리할 수 없습니다.'
  if (error.status === 403) return '연동 클라이언트 관리 권한이 필요합니다.'
  if (error.problem?.code === 'DUPLICATE_INTEGRATION_CLIENT_NAME')
    return '이미 사용 중인 클라이언트 이름입니다.'
  if (error.problem?.code === 'INTEGRATION_CLIENT_NOT_ACTIVE')
    return '활성 클라이언트만 회전하거나 비활성화할 수 있습니다.'
  return `${error.message}${error.requestId ? ` 요청 ID: ${error.requestId}` : ''}`
}

function handleDialogKeys(
  event: KeyboardEvent<HTMLElement>,
  close: () => void,
) {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    return
  }
  if (event.key !== 'Tab') return
  const focusable = Array.from(
    event.currentTarget.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
    ),
  )
  if (!focusable.length) return
  const first = focusable[0]!
  const last = focusable[focusable.length - 1]!
  const active = document.activeElement
  if (
    event.shiftKey &&
    (active === first || !focusable.includes(active as HTMLElement))
  ) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

export function IntegrationClientsPage() {
  const [clients, setClients] = useState<IntegrationClient[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [page, setPage] = useState(0)
  const [totalCount, setTotalCount] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [submitting, setSubmitting] = useState(false)
  const [mutationId, setMutationId] = useState<string | null>(null)
  const [issue, setIssue] = useState<IntegrationCredentialIssue | null>(null)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [scopes, setScopes] = useState<IntegrationScope[]>(['tickets:read'])
  const [groupIds, setGroupIds] = useState('')
  const [ticketKinds, setTicketKinds] = useState<IntegrationTicketKind[]>([])
  const [fields, setFields] = useState<IntegrationTicketField[]>([])
  const [ipAllowlist, setIpAllowlist] = useState('')
  const [expiresAt, setExpiresAt] = useState(() => futureLocalDate(90))
  const [rotateClient, setRotateClient] = useState<IntegrationClient | null>(
    null,
  )
  const [rotateExpiresAt, setRotateExpiresAt] = useState(() =>
    futureLocalDate(90),
  )
  const [overlapHours, setOverlapHours] = useState(1)
  const errorRef = useRef<HTMLDivElement>(null)
  const createButtonRef = useRef<HTMLButtonElement>(null)
  const lastActionRef = useRef<HTMLElement | null>(null)
  const issueHeadingRef = useRef<HTMLHeadingElement>(null)
  const rotateHeadingRef = useRef<HTMLHeadingElement>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const result = await listIntegrationClients(page, PAGE_SIZE)
      setClients(result.items)
      setTotalCount(result.totalCount)
      setTotalPages(result.totalPages)
    } catch (caught) {
      setError(integrationError(caught))
    } finally {
      setLoading(false)
    }
  }, [page])

  useEffect(() => void reload(), [reload])
  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])
  useEffect(() => {
    if (issue) issueHeadingRef.current?.focus()
  }, [issue])
  useEffect(() => {
    if (rotateClient) rotateHeadingRef.current?.focus()
  }, [rotateClient])

  function toggleValue<T>(values: T[], value: T, checked: boolean) {
    return checked
      ? [...values, value]
      : values.filter((item) => item !== value)
  }

  async function create(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      const created = await createIntegrationClient({
        name,
        description,
        scopes,
        resourceConstraints: {
          ...(groupIds.trim()
            ? {
                allowedGroupIds: groupIds
                  .split(',')
                  .map((value) => value.trim())
                  .filter(Boolean),
              }
            : {}),
          ...(ticketKinds.length ? { allowedTicketKinds: ticketKinds } : {}),
          ...(fields.length ? { allowedFields: fields } : {}),
          ...(ipAllowlist.trim()
            ? {
                ipAllowlist: ipAllowlist
                  .split(',')
                  .map((value) => value.trim())
                  .filter(Boolean),
              }
            : {}),
        },
        expiresAt: toIso(expiresAt),
      })
      setIssue(created)
      setName('')
      setDescription('')
      setScopes(['tickets:read'])
      setGroupIds('')
      setTicketKinds([])
      setFields([])
      setIpAllowlist('')
      await reload()
    } catch (caught) {
      setError(integrationError(caught))
    } finally {
      setSubmitting(false)
    }
  }

  async function mutate(
    client: IntegrationClient,
    action: 'disable' | 'revoke',
  ) {
    setMutationId(client.id)
    setError(null)
    try {
      if (action === 'disable') await disableIntegrationClient(client.id)
      else await revokeIntegrationClient(client.id)
      await reload()
    } catch (caught) {
      setError(integrationError(caught))
    } finally {
      setMutationId(null)
    }
  }

  async function rotate(event: FormEvent) {
    event.preventDefault()
    if (!rotateClient) return
    setMutationId(rotateClient.id)
    setError(null)
    try {
      const rotated = await rotateIntegrationClientCredential(rotateClient.id, {
        expiresAt: toIso(rotateExpiresAt),
        overlapSeconds: overlapHours * 3600,
      })
      setRotateClient(null)
      setIssue(rotated)
      await reload()
    } catch (caught) {
      setError(integrationError(caught))
    } finally {
      setMutationId(null)
    }
  }

  function closeIssue() {
    setIssue(null)
    ;(lastActionRef.current ?? createButtonRef.current)?.focus()
  }

  function closeRotation() {
    setRotateClient(null)
    lastActionRef.current?.focus()
  }

  return (
    <section aria-labelledby="integration-client-title">
      <div className="admin-title-row">
        <div>
          <p className="eyebrow">INTEGRATIONS · INT-001</p>
          <h1 id="integration-client-title">API 클라이언트</h1>
          <p>
            직원 계정과 분리된 machine principal의 key lifecycle과 최소 권한을
            관리합니다.
          </p>
        </div>
      </div>
      {error ? (
        <Notification
          tone="danger"
          title={error}
          tabIndex={-1}
          ref={errorRef}
        />
      ) : null}
      <div className="admin-grid integration-admin-grid">
        <section className="admin-panel" aria-labelledby="create-client-title">
          <h2 id="create-client-title">클라이언트 발급</h2>
          <form className="admin-form" onSubmit={create}>
            <label>
              이름
              <input
                required
                maxLength={100}
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </label>
            <label>
              설명
              <textarea
                maxLength={500}
                value={description}
                onChange={(event) => setDescription(event.target.value)}
              />
            </label>
            <fieldset>
              <legend>Scope · 허용할 동작</legend>
              {SCOPES.map(({ value, label }) => (
                <label className="choice-row" key={value}>
                  <input
                    type="checkbox"
                    checked={scopes.includes(value)}
                    onChange={(event) =>
                      setScopes(
                        toggleValue(scopes, value, event.target.checked),
                      )
                    }
                  />
                  {label} <code>{value}</code>
                </label>
              ))}
            </fieldset>
            <label>
              허용 그룹 UUID · 쉼표 구분
              <input
                value={groupIds}
                onChange={(event) => setGroupIds(event.target.value)}
                placeholder="비우면 모든 그룹"
              />
            </label>
            <fieldset>
              <legend>허용 티켓 종류 · 비우면 모두</legend>
              {KINDS.map((kind) => (
                <label className="choice-row" key={kind}>
                  <input
                    type="checkbox"
                    checked={ticketKinds.includes(kind)}
                    onChange={(event) =>
                      setTicketKinds(
                        toggleValue(ticketKinds, kind, event.target.checked),
                      )
                    }
                  />
                  {kind}
                </label>
              ))}
            </fieldset>
            <fieldset>
              <legend>수정 가능한 필드 · 비우면 모두</legend>
              {FIELDS.map((field) => (
                <label className="choice-row" key={field}>
                  <input
                    type="checkbox"
                    checked={fields.includes(field)}
                    onChange={(event) =>
                      setFields(
                        toggleValue(fields, field, event.target.checked),
                      )
                    }
                  />
                  {field}
                </label>
              ))}
            </fieldset>
            <label>
              IP/CIDR allowlist · 쉼표 구분
              <input
                value={ipAllowlist}
                onChange={(event) => setIpAllowlist(event.target.value)}
                placeholder="예: 10.20.0.0/16, 2001:db8::/32"
              />
            </label>
            <label>
              Key 만료
              <input
                required
                type="datetime-local"
                value={expiresAt}
                onChange={(event) => setExpiresAt(event.target.value)}
              />
            </label>
            <button
              ref={createButtonRef}
              className="button primary"
              type="submit"
              disabled={submitting || scopes.length === 0}
            >
              {submitting ? '발급 중…' : '클라이언트와 key 발급'}
            </button>
          </form>
        </section>
        <section
          className="admin-panel admin-list-panel"
          aria-labelledby="client-list-title"
        >
          <h2 id="client-list-title">등록된 클라이언트</h2>
          {loading ? (
            <ScreenState
              kind="loading"
              compact
              title="API 클라이언트를 불러오는 중입니다."
            />
          ) : null}
          {!loading && !error && clients.length === 0 ? (
            <ScreenState
              kind="empty"
              compact
              title="등록된 API 클라이언트가 없습니다."
            />
          ) : null}
          {!loading && clients.length ? (
            <>
              <div className="integration-client-list">
                {clients.map((client) => (
                  <article className="integration-client-card" key={client.id}>
                    <header>
                      <div>
                        <strong>{client.name}</strong>
                        <small>{client.description || '설명 없음'}</small>
                      </div>
                      <span
                        className={`integration-status status-${client.status.toLowerCase()}`}
                      >
                        {client.status}
                      </span>
                    </header>
                    <dl>
                      <div>
                        <dt>Scope</dt>
                        <dd>{client.scopes.join(', ')}</dd>
                      </div>
                      <div>
                        <dt>만료</dt>
                        <dd>
                          {client.expiresAt
                            ? new Date(client.expiresAt).toLocaleString()
                            : '없음'}
                        </dd>
                      </div>
                      <div>
                        <dt>마지막 사용</dt>
                        <dd>
                          {client.lastUsedAt
                            ? `${new Date(client.lastUsedAt).toLocaleString()} · ${client.lastUsedIp ?? ''}`
                            : '사용 기록 없음'}
                        </dd>
                      </div>
                      <div>
                        <dt>Key</dt>
                        <dd>
                          {client.credentials
                            .map(
                              (credential) =>
                                `v${credential.sequence} ${credential.status} (${credential.publicKeyId})`,
                            )
                            .join(' · ')}
                        </dd>
                      </div>
                    </dl>
                    <div className="integration-actions">
                      <button
                        className="button secondary small"
                        type="button"
                        disabled={
                          client.status !== 'ACTIVE' || mutationId === client.id
                        }
                        onClick={(event) => {
                          lastActionRef.current = event.currentTarget
                          setRotateExpiresAt(futureLocalDate(90))
                          setRotateClient(client)
                        }}
                      >
                        Key 회전
                      </button>
                      <button
                        className="text-button"
                        type="button"
                        disabled={
                          client.status !== 'ACTIVE' || mutationId === client.id
                        }
                        onClick={() => void mutate(client, 'disable')}
                      >
                        비활성화
                      </button>
                      <button
                        className="text-button danger"
                        type="button"
                        disabled={
                          client.status === 'REVOKED' ||
                          mutationId === client.id
                        }
                        onClick={() => void mutate(client, 'revoke')}
                      >
                        영구 폐기
                      </button>
                    </div>
                  </article>
                ))}
              </div>
              <nav
                className="admin-pagination"
                aria-label="API 클라이언트 목록 페이지"
              >
                <button
                  className="button secondary small"
                  type="button"
                  disabled={page === 0}
                  onClick={() => setPage((current) => Math.max(0, current - 1))}
                >
                  이전
                </button>
                <span>
                  전체 {totalCount}개 · {page + 1}/{Math.max(totalPages, 1)}{' '}
                  페이지
                </span>
                <button
                  className="button secondary small"
                  type="button"
                  disabled={page + 1 >= totalPages}
                  onClick={() => setPage((current) => current + 1)}
                >
                  다음
                </button>
              </nav>
            </>
          ) : null}
          {!loading && error ? (
            <button
              className="button secondary small"
              type="button"
              onClick={reload}
            >
              목록 다시 불러오기
            </button>
          ) : null}
        </section>
      </div>
      {rotateClient ? (
        <div className="modal-backdrop" role="presentation">
          <section
            className="integration-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="rotate-title"
            onKeyDown={(event) => handleDialogKeys(event, closeRotation)}
          >
            <h2 id="rotate-title" ref={rotateHeadingRef} tabIndex={-1}>
              Key 회전 · {rotateClient.name}
            </h2>
            <p>
              기존 key overlap은 최대 24시간입니다. 새 key는 응답에서 한 번만
              표시됩니다.
            </p>
            <form className="admin-form" onSubmit={rotate}>
              <label>
                새 key 만료
                <input
                  required
                  type="datetime-local"
                  value={rotateExpiresAt}
                  onChange={(event) => setRotateExpiresAt(event.target.value)}
                />
              </label>
              <label>
                기존 key overlap 시간
                <input
                  required
                  type="number"
                  min={0}
                  max={24}
                  value={overlapHours}
                  onChange={(event) =>
                    setOverlapHours(Number(event.target.value))
                  }
                />
              </label>
              <div className="dialog-actions">
                <button
                  className="button secondary"
                  type="button"
                  onClick={closeRotation}
                >
                  취소
                </button>
                <button className="button primary" type="submit">
                  회전하고 새 key 보기
                </button>
              </div>
            </form>
          </section>
        </div>
      ) : null}
      {issue ? (
        <div className="modal-backdrop" role="presentation">
          <section
            className="integration-dialog secret-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="secret-title"
            onKeyDown={(event) => handleDialogKeys(event, closeIssue)}
          >
            <h2 id="secret-title" ref={issueHeadingRef} tabIndex={-1}>
              API key를 지금 복사하세요
            </h2>
            <Notification
              tone="warning"
              title="이 key는 이 창을 닫으면 다시 볼 수 없습니다."
            />
            <label>
              발급된 API key
              <input
                aria-label="발급된 API key"
                readOnly
                value={issue.apiKey}
                onFocus={(event) => event.currentTarget.select()}
              />
            </label>
            <p>
              브라우저 저장소에는 보관하지 않습니다. 서버 측 secret manager로
              바로 옮기세요.
            </p>
            <div className="dialog-actions">
              <button
                className="button primary"
                type="button"
                onClick={closeIssue}
              >
                복사 완료 · 닫기
              </button>
            </div>
          </section>
        </div>
      ) : null}
    </section>
  )
}
