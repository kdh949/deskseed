import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import {
  ApiError,
  createTicketExternalReference,
  deleteTicketExternalReference,
  listTicketExternalReferences,
} from '../../api/client'
import type { ExternalObjectType } from '../../api/types'
import { Notification, ScreenState } from '../../shared/ui/system'

const OBJECT_TYPES: ExternalObjectType[] = [
  'ORDER',
  'PAYMENT',
  'REFUND',
  'USER',
  'STORE',
  'OPS_CASE',
  'CUSTOM',
]

export function TicketExternalReferences({
  ticketNumber,
  canUpdate,
  active,
  onCommandCompleted,
}: {
  ticketNumber: number
  canUpdate: boolean
  active: boolean
  onCommandCompleted: () => Promise<unknown>
}) {
  const interactionId = useMemo(createInteractionId, [ticketNumber])
  const query = useQuery({
    queryKey: ['ticket-external-references', ticketNumber, interactionId],
    queryFn: () => listTicketExternalReferences(ticketNumber, interactionId),
    enabled: active,
  })
  const [systemId, setSystemId] = useState('')
  const [objectType, setObjectType] = useState<ExternalObjectType>('ORDER')
  const [externalId, setExternalId] = useState('')
  const [displayLabel, setDisplayLabel] = useState('')
  const [safeDeepLink, setSafeDeepLink] = useState('')
  const [status, setStatus] = useState('')
  const [storeName, setStoreName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [mutationError, setMutationError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null)
  const feedbackRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!systemId && query.data?.availableSystems[0])
      setSystemId(query.data.availableSystems[0].id)
  }, [query.data?.availableSystems, systemId])
  useEffect(() => {
    if (mutationError || success) feedbackRef.current?.focus()
  }, [mutationError, success])

  async function create(event: FormEvent) {
    event.preventDefault()
    if (!query.data) return
    setSubmitting(true)
    setMutationError(null)
    setSuccess(null)
    try {
      await createTicketExternalReference(ticketNumber, {
        externalSystemId: systemId,
        objectType,
        externalId,
        displayLabel,
        safeDeepLink,
        metadata: {
          ...(status.trim() ? { status: status.trim() } : {}),
          ...(storeName.trim() ? { storeName: storeName.trim() } : {}),
        },
        metadataObservedAt: new Date().toISOString(),
        expectedVersion: query.data.ticketVersion,
      })
      setExternalId('')
      setDisplayLabel('')
      setSafeDeepLink('')
      setStatus('')
      setStoreName('')
      setSuccess('외부 참조를 연결했습니다.')
      await onCommandCompleted()
      await query.refetch()
    } catch (caught) {
      setMutationError(referenceError(caught))
      if (caught instanceof ApiError && caught.status === 412)
        await query.refetch()
    } finally {
      setSubmitting(false)
    }
  }

  async function remove(referenceId: string) {
    if (!query.data) return
    setSubmitting(true)
    setMutationError(null)
    setSuccess(null)
    try {
      await deleteTicketExternalReference(
        ticketNumber,
        referenceId,
        query.data.ticketVersion,
      )
      setConfirmDeleteId(null)
      setSuccess(
        'Deskseed의 외부 참조만 해제했습니다. 외부 원본은 변경하지 않았습니다.',
      )
      await onCommandCompleted()
      await query.refetch()
    } catch (caught) {
      setMutationError(referenceError(caught))
      if (caught instanceof ApiError && caught.status === 412)
        await query.refetch()
    } finally {
      setSubmitting(false)
    }
  }

  if (query.isPending)
    return (
      <ScreenState
        kind="loading"
        compact
        title="외부 참조를 감사 조회하는 중입니다."
      />
    )
  if (query.isError) {
    const denied = query.error instanceof ApiError && query.error.status === 403
    return (
      <ScreenState
        kind={denied ? 'denied' : 'error'}
        compact
        title={
          denied
            ? '외부 참조를 볼 권한이 없습니다.'
            : '외부 참조를 불러오지 못했습니다.'
        }
        description="필수 resource-read 감사가 저장되지 않으면 결과를 표시하지 않습니다."
        requestId={
          query.error instanceof ApiError ? query.error.requestId : undefined
        }
        action={
          <button
            className="compact-button"
            type="button"
            onClick={() => void query.refetch()}
          >
            다시 시도
          </button>
        }
      />
    )
  }

  const data = query.data
  const canManage = canUpdate && data.canManage
  return (
    <div className="external-reference-context">
      <header>
        <div>
          <h2>외부 참조</h2>
          <p>
            원본을 복제하지 않고 안전한 식별자와 작은 snapshot만 표시합니다.
          </p>
        </div>
        <span>{data.items.length}개</span>
      </header>
      {mutationError ? (
        <Notification
          ref={feedbackRef}
          tabIndex={-1}
          tone="danger"
          title={mutationError}
        />
      ) : null}
      {success ? (
        <Notification
          ref={feedbackRef}
          tabIndex={-1}
          tone="success"
          title={success}
        />
      ) : null}
      {data.items.length === 0 ? (
        <ScreenState
          kind="empty"
          compact
          title="연결된 외부 참조가 없습니다."
        />
      ) : (
        <ul className="external-reference-list">
          {data.items.map((reference) => (
            <li key={reference.id}>
              <div className="external-reference-card-title">
                <span className="external-object-type">
                  {reference.objectType}
                </span>
                <strong>{reference.displayLabel}</strong>
              </div>
              <p>
                <span>{reference.system.displayName}</span> ·{' '}
                <code>{reference.externalId}</code>
              </p>
              {Object.keys(reference.metadata).length ? (
                <dl className="external-metadata">
                  {Object.entries(reference.metadata).map(([key, value]) => (
                    <div key={key}>
                      <dt>{metadataLabel(key)}</dt>
                      <dd>{String(value)}</dd>
                    </div>
                  ))}
                </dl>
              ) : null}
              <small>
                snapshot {formatDate(reference.metadataObservedAt)} ·{' '}
                {reference.createdBy.displayName}
              </small>
              <div className="external-reference-actions">
                {reference.linkState === 'AVAILABLE' &&
                reference.safeDeepLink ? (
                  <a
                    className="button secondary small"
                    href={reference.safeDeepLink}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    원본 새 창에서 열기
                  </a>
                ) : (
                  <span className="external-link-disabled">
                    {reference.linkState === 'SYSTEM_DISABLED'
                      ? '시스템이 비활성화되어 링크가 중단됨'
                      : '현재 hostname 정책에서 링크가 중단됨'}
                  </span>
                )}
                {canManage && confirmDeleteId !== reference.id ? (
                  <button
                    className="button danger small"
                    type="button"
                    onClick={() => setConfirmDeleteId(reference.id)}
                  >
                    연결 해제
                  </button>
                ) : null}
              </div>
              {confirmDeleteId === reference.id ? (
                <div
                  className="external-reference-confirm"
                  role="group"
                  aria-label={`${reference.displayLabel} 연결 해제 확인`}
                >
                  <p>Deskseed의 연결만 해제할까요?</p>
                  <button
                    className="button secondary small"
                    type="button"
                    onClick={() => setConfirmDeleteId(null)}
                  >
                    취소
                  </button>
                  <button
                    className="button danger small"
                    type="button"
                    disabled={submitting}
                    onClick={() => void remove(reference.id)}
                  >
                    {submitting ? '해제 중…' : '연결 해제 확인'}
                  </button>
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {canManage ? (
        data.availableSystems.length ? (
          <details className="external-reference-create">
            <summary>외부 참조 연결</summary>
            <form onSubmit={create}>
              <label>
                외부 시스템
                <select
                  required
                  value={systemId}
                  onChange={(event) => setSystemId(event.target.value)}
                >
                  {data.availableSystems.map((system) => (
                    <option key={system.id} value={system.id}>
                      {system.displayName}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                객체 종류
                <select
                  value={objectType}
                  onChange={(event) =>
                    setObjectType(event.target.value as ExternalObjectType)
                  }
                >
                  {OBJECT_TYPES.map((type) => (
                    <option key={type}>{type}</option>
                  ))}
                </select>
              </label>
              <label>
                외부 ID
                <input
                  required
                  maxLength={200}
                  value={externalId}
                  onChange={(event) => setExternalId(event.target.value)}
                />
              </label>
              <label>
                표시 이름
                <input
                  required
                  maxLength={200}
                  value={displayLabel}
                  onChange={(event) => setDisplayLabel(event.target.value)}
                />
              </label>
              <label>
                HTTPS 원본 링크
                <input
                  required
                  type="url"
                  maxLength={2048}
                  pattern="https://.*"
                  value={safeDeepLink}
                  onChange={(event) => setSafeDeepLink(event.target.value)}
                />
              </label>
              <fieldset>
                <legend>
                  선택 snapshot · 원본 데이터 전체를 붙여넣지 마세요
                </legend>
                <label>
                  상태
                  <input
                    maxLength={200}
                    value={status}
                    onChange={(event) => setStatus(event.target.value)}
                  />
                </label>
                <label>
                  매장 이름
                  <input
                    maxLength={200}
                    value={storeName}
                    onChange={(event) => setStoreName(event.target.value)}
                  />
                </label>
              </fieldset>
              <button
                className="button primary"
                type="submit"
                disabled={submitting}
              >
                {submitting ? '연결 중…' : '참조 연결'}
              </button>
            </form>
          </details>
        ) : (
          <ScreenState
            kind="empty"
            compact
            title="활성 외부 시스템이 없습니다."
            description="관리자가 외부 시스템과 hostname 정책을 먼저 등록해야 합니다."
          />
        )
      ) : (
        <p className="related-write-note">
          이 티켓은 읽을 수 있지만 외부 참조를 변경할 쓰기 권한은 없습니다.
        </p>
      )}
    </div>
  )
}

function referenceError(error: unknown) {
  if (!(error instanceof ApiError))
    return '외부 참조 요청을 처리할 수 없습니다.'
  if (error.status === 412)
    return '티켓이 변경되었습니다. 최신 참조를 불러왔으니 다시 확인해 주세요.'
  if (error.status === 403)
    return '이 티켓의 외부 참조를 변경할 권한이 없습니다.'
  if (error.problem?.code === 'EXTERNAL_REFERENCE_EXISTS')
    return '같은 외부 객체가 이미 이 티켓에 연결되어 있습니다.'
  if (error.problem?.code === 'EXTERNAL_SYSTEM_INACTIVE')
    return '비활성 외부 시스템에는 새 참조를 연결할 수 없습니다.'
  return `${error.message}${error.requestId ? ` 요청 ID: ${error.requestId}` : ''}`
}

function metadataLabel(key: string) {
  return (
    (
      {
        status: '상태',
        storeName: '매장',
        amountDisplay: '금액',
        currency: '통화',
        occurredAt: '발생 시각',
        ownerLabel: '담당',
        channel: '채널',
      } as Record<string, string>
    )[key] ?? key
  )
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function createInteractionId() {
  return (
    globalThis.crypto?.randomUUID?.() ?? '00000000-0000-4000-8000-000000000001'
  )
}
