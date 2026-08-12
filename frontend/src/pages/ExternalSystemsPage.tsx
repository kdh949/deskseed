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
  createExternalSystem,
  listExternalSystems,
  updateExternalSystem,
} from '../api/client'
import type { ExternalSystem, ExternalSystemStatus } from '../api/types'
import { Notification, ScreenState } from '../shared/ui/system'

function hostnames(value: string) {
  return [
    ...new Set(
      value
        .split(/[\n,]/)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  ]
}

function externalSystemError(error: unknown) {
  if (!(error instanceof ApiError))
    return '외부 시스템 요청을 처리할 수 없습니다.'
  if (error.status === 403) return '외부 시스템 관리 권한이 필요합니다.'
  if (error.status === 412)
    return '다른 관리자가 먼저 변경했습니다. 최신 값을 다시 불러왔습니다.'
  if (error.problem?.code === 'EXTERNAL_SYSTEM_KEY_EXISTS')
    return '이미 등록된 system key입니다.'
  return `${error.message}${error.requestId ? ` 요청 ID: ${error.requestId}` : ''}`
}

export function ExternalSystemsPage() {
  const [systems, setSystems] = useState<ExternalSystem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [systemKey, setSystemKey] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [allowedHosts, setAllowedHosts] = useState('')
  const [editing, setEditing] = useState<ExternalSystem | null>(null)
  const [editName, setEditName] = useState('')
  const [editStatus, setEditStatus] = useState<ExternalSystemStatus>('ACTIVE')
  const [editHosts, setEditHosts] = useState('')
  const errorRef = useRef<HTMLDivElement>(null)
  const editNameRef = useRef<HTMLInputElement>(null)
  const lastEditTriggerRef = useRef<HTMLButtonElement | null>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setSystems(await listExternalSystems())
    } catch (caught) {
      setError(externalSystemError(caught))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => void reload(), [reload])
  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])
  useEffect(() => {
    if (editing) editNameRef.current?.focus()
  }, [editing])

  async function create(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await createExternalSystem({
        systemKey,
        displayName,
        allowedHostnames: hostnames(allowedHosts),
      })
      setSystemKey('')
      setDisplayName('')
      setAllowedHosts('')
      await reload()
    } catch (caught) {
      setError(externalSystemError(caught))
    } finally {
      setSubmitting(false)
    }
  }

  function beginEdit(system: ExternalSystem, trigger: HTMLButtonElement) {
    lastEditTriggerRef.current = trigger
    setEditing(system)
    setEditName(system.displayName)
    setEditStatus(system.status)
    setEditHosts(system.allowedHostnames.join('\n'))
  }

  function closeEdit() {
    setEditing(null)
    window.setTimeout(() => lastEditTriggerRef.current?.focus())
  }

  function handleDialogKeys(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape') {
      event.preventDefault()
      closeEdit()
      return
    }
    if (event.key !== 'Tab') return
    const focusable = Array.from(
      event.currentTarget.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
      ),
    )
    const first = focusable[0]
    const last = focusable.at(-1)
    if (!first || !last) return
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!editing) return
    setSubmitting(true)
    setError(null)
    try {
      await updateExternalSystem(editing.id, {
        displayName: editName,
        status: editStatus,
        allowedHostnames: hostnames(editHosts),
        expectedVersion: editing.version,
      })
      closeEdit()
      await reload()
    } catch (caught) {
      setError(externalSystemError(caught))
      if (caught instanceof ApiError && caught.status === 412) {
        closeEdit()
        await reload()
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section aria-labelledby="external-system-title">
      <div className="admin-title-row">
        <div>
          <p className="eyebrow">INTEGRATIONS · INT-002</p>
          <h1 id="external-system-title">외부 시스템</h1>
          <p>
            티켓에서 참조할 시스템과 정확한 HTTPS hostname만 등록합니다.
            Deskseed 서버는 링크를 조회하지 않습니다.
          </p>
        </div>
      </div>
      {error ? (
        <Notification
          ref={errorRef}
          tabIndex={-1}
          tone="danger"
          title={error}
        />
      ) : null}
      <div className="admin-grid integration-admin-grid">
        <section className="admin-panel" aria-labelledby="create-system-title">
          <h2 id="create-system-title">시스템 등록</h2>
          <form className="admin-form" onSubmit={create}>
            <label>
              System key · 생성 후 변경 불가
              <input
                required
                maxLength={64}
                pattern="[a-z][a-z0-9-]*"
                value={systemKey}
                onChange={(event) => setSystemKey(event.target.value)}
                placeholder="shop-order"
              />
            </label>
            <label>
              표시 이름
              <input
                required
                maxLength={100}
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
              />
            </label>
            <label>
              허용 HTTPS hostname · 줄바꿈 또는 쉼표 구분
              <textarea
                required
                value={allowedHosts}
                onChange={(event) => setAllowedHosts(event.target.value)}
                placeholder="admin.shop.example"
              />
              <small>
                와일드카드, IP, localhost와 private/local 이름은 허용되지
                않습니다.
              </small>
            </label>
            <button
              className="button primary"
              type="submit"
              disabled={submitting || hostnames(allowedHosts).length === 0}
            >
              {submitting ? '등록 중…' : '외부 시스템 등록'}
            </button>
          </form>
        </section>
        <section
          className="admin-panel admin-list-panel"
          aria-labelledby="system-list-title"
        >
          <h2 id="system-list-title">등록된 시스템</h2>
          {loading ? (
            <ScreenState
              kind="loading"
              compact
              title="외부 시스템을 불러오는 중입니다."
            />
          ) : null}
          {!loading && !error && systems.length === 0 ? (
            <ScreenState
              kind="empty"
              compact
              title="등록된 외부 시스템이 없습니다."
              description="먼저 hostname 정책을 가진 시스템을 등록하세요."
            />
          ) : null}
          {!loading && systems.length ? (
            <div className="external-system-list">
              {systems.map((system) => (
                <article className="external-system-card" key={system.id}>
                  <header>
                    <div>
                      <strong>{system.displayName}</strong>
                      <code>{system.systemKey}</code>
                    </div>
                    <span
                      className={`integration-status status-${system.status.toLowerCase()}`}
                    >
                      {system.status}
                    </span>
                  </header>
                  <dl>
                    <div>
                      <dt>허용 hostname</dt>
                      <dd>{system.allowedHostnames.join(', ')}</dd>
                    </div>
                    <div>
                      <dt>마지막 변경</dt>
                      <dd>{new Date(system.updatedAt).toLocaleString()}</dd>
                    </div>
                  </dl>
                  <button
                    className="button secondary small"
                    type="button"
                    onClick={(event) => beginEdit(system, event.currentTarget)}
                    disabled={editing !== null}
                  >
                    정책 편집
                  </button>
                </article>
              ))}
            </div>
          ) : null}
        </section>
      </div>
      {editing ? (
        <div className="integration-dialog-backdrop" role="presentation">
          <section
            className="integration-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="edit-system-title"
            onKeyDown={handleDialogKeys}
          >
            <h2 id="edit-system-title">{editing.systemKey} 정책 편집</h2>
            <form className="admin-form" onSubmit={save}>
              <label>
                표시 이름
                <input
                  ref={editNameRef}
                  required
                  maxLength={100}
                  value={editName}
                  onChange={(event) => setEditName(event.target.value)}
                />
              </label>
              <label>
                상태
                <select
                  value={editStatus}
                  onChange={(event) =>
                    setEditStatus(event.target.value as ExternalSystemStatus)
                  }
                >
                  <option value="ACTIVE">ACTIVE</option>
                  <option value="DISABLED">DISABLED</option>
                </select>
              </label>
              <label>
                허용 HTTPS hostname
                <textarea
                  required
                  value={editHosts}
                  onChange={(event) => setEditHosts(event.target.value)}
                />
              </label>
              <p className="admin-state">
                DISABLED이면 기존 참조는 남지만 링크 열기와 새 연결이
                중단됩니다.
              </p>
              <div className="integration-actions">
                <button
                  className="button secondary"
                  type="button"
                  onClick={closeEdit}
                >
                  취소
                </button>
                <button
                  className="button primary"
                  type="submit"
                  disabled={submitting || hostnames(editHosts).length === 0}
                >
                  {submitting ? '저장 중…' : '정책 저장'}
                </button>
              </div>
            </form>
          </section>
        </div>
      ) : null}
    </section>
  )
}
