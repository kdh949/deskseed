import { useQuery } from '@tanstack/react-query'
import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
  type RefObject,
} from 'react'
import {
  ApiError,
  getAuditActivity,
  revealAuditSearchQuery,
} from '../../api/client'
import type {
  AuditActivityDetail,
  SearchQueryRevealResult,
} from '../../api/types'
import { Notification, ScreenState } from '../../shared/ui/system'
import { useStaffSession } from '../staff-auth/StaffSessionContext'
import { createAuditInteractionId } from './auditInteraction'

export function AuditDetailDrawer({
  activityId,
  onClose,
  onSelectActivity,
}: {
  activityId: string
  onClose: () => void
  onSelectActivity: (activityId: string) => void
}) {
  const session = useStaffSession()
  const dialogRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const interactionRef = useRef({
    activityId,
    interactionId: createAuditInteractionId(),
  })
  if (interactionRef.current.activityId !== activityId) {
    interactionRef.current = {
      activityId,
      interactionId: createAuditInteractionId(),
    }
  }
  const detail = useQuery({
    queryKey: [
      'audit-activity-detail',
      activityId,
      interactionRef.current.interactionId,
    ],
    queryFn: () =>
      getAuditActivity(activityId, interactionRef.current.interactionId),
  })

  useEffect(() => {
    const restoreFocus =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null
    closeRef.current?.focus()
    return () => restoreFocus?.focus()
  }, [])

  return (
    <div
      className="audit-drawer-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div
        ref={dialogRef}
        className="audit-detail-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="audit-detail-title"
        onKeyDown={(event) => trapDrawerFocus(event, dialogRef, onClose)}
      >
        <header className="audit-drawer-header">
          <div>
            <p className="agent-page-eyebrow">CANONICAL EVENT DETAIL</p>
            <h2 id="audit-detail-title">활동 상세</h2>
          </div>
          <button
            ref={closeRef}
            className="compact-button"
            type="button"
            onClick={onClose}
          >
            닫기
          </button>
        </header>
        <div className="audit-drawer-body">
          {detail.isPending ? (
            <ScreenState compact kind="loading" title="활동 상세를 불러오는 중입니다." />
          ) : null}
          {detail.isError ? (
            <DetailError error={detail.error} retry={() => detail.refetch()} />
          ) : null}
          {detail.data ? (
            <AuditDetailContent
              detail={detail.data}
              revealAllowed={
                session.staff?.capabilities.includes(
                  'audit:search-query:reveal',
                ) ?? false
              }
              onSelectActivity={onSelectActivity}
            />
          ) : null}
        </div>
      </div>
    </div>
  )
}

function AuditDetailContent({
  detail,
  revealAllowed,
  onSelectActivity,
}: {
  detail: AuditActivityDetail
  revealAllowed: boolean
  onSelectActivity: (activityId: string) => void
}) {
  return (
    <>
      <section className="audit-detail-section">
        <div className="audit-detail-heading">
          <span className={`audit-ledger ledger-${detail.ledger.toLowerCase()}`}>
            {ledgerLabel(detail.ledger)}
          </span>
          <span className={`audit-outcome outcome-${detail.outcome.toLowerCase()}`}>
            {detail.outcome}
          </span>
        </div>
        <h3>{detail.action}</h3>
        <p>{detail.summary}</p>
        <dl className="audit-detail-grid">
          <DetailTerm label="발생 시각" value={formatDateTime(detail.occurredAt)} />
          <DetailTerm
            label="행위자"
            value={`${detail.actor.displayName} · ${detail.actor.type}`}
          />
          <DetailTerm label="원본 event" value={detail.canonicalEventId} mono />
          <DetailTerm label="원본 parent" value={detail.canonicalParentId} mono />
          <DetailTerm label="티켓" value={detail.ticketNumber ? `#${detail.ticketNumber}` : null} />
          <DetailTerm label="소스" value={detail.source} />
          <DetailTerm label="요청 ID" value={detail.requestId} mono />
          <DetailTerm label="상관 ID" value={detail.correlationId} mono />
          <DetailTerm label="interaction" value={detail.interactionId} mono />
          <DetailTerm label="session fingerprint" value={detail.sessionFingerprint} mono />
          <DetailTerm label="인증 방식" value={detail.authType} />
          <DetailTerm label="IP" value={detail.ipAddress} mono />
          <DetailTerm label="User agent" value={detail.userAgent} />
        </dl>
      </section>

      {detail.fieldChange ? (
        <section className="audit-detail-section" aria-labelledby="field-change-title">
          <h3 id="field-change-title">{detail.fieldChange.field} 변경</h3>
          <div className="audit-diff-grid">
            <div>
              <span>BEFORE</span>
              <code>{displayValue(detail.fieldChange.before)}</code>
            </div>
            <span aria-hidden="true">→</span>
            <div>
              <span>AFTER</span>
              <code>{displayValue(detail.fieldChange.after)}</code>
            </div>
          </div>
        </section>
      ) : null}

      {detail.search ? (
        <SearchInvestigation
          detail={detail}
          revealAllowed={revealAllowed}
          onSelectActivity={onSelectActivity}
        />
      ) : null}

      {Object.keys(detail.metadata).length ? (
        <section className="audit-detail-section">
          <h3>허용된 metadata</h3>
          <dl className="audit-metadata-list">
            {Object.entries(detail.metadata).map(([key, value]) => (
              <DetailTerm key={key} label={key} value={displayValue(value)} mono />
            ))}
          </dl>
        </section>
      ) : null}
    </>
  )
}

function SearchInvestigation({
  detail,
  revealAllowed,
  onSelectActivity,
}: {
  detail: AuditActivityDetail
  revealAllowed: boolean
  onSelectActivity: (activityId: string) => void
}) {
  const search = detail.search!
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState(false)
  const [result, setResult] = useState<SearchQueryRevealResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  const reveal = async (event: FormEvent) => {
    event.preventDefault()
    if (!reason.trim() || pending) return
    setPending(true)
    setError(null)
    setResult(null)
    try {
      setResult(
        await revealAuditSearchQuery(
          detail.id,
          reason.trim(),
          createAuditInteractionId(),
        ),
      )
    } catch (cause) {
      const apiError = cause instanceof ApiError ? cause : null
      setError(
        apiError?.status === 403
          ? '최근 인증 또는 MFA 정책을 만족해야 합니다.'
          : apiError?.status === 422
            ? '암호문 인증에 실패했습니다. 원문은 공개되지 않았습니다.'
            : (apiError?.message ?? '보호된 검색어를 공개하지 못했습니다.'),
      )
    } finally {
      setPending(false)
    }
  }

  return (
    <section className="audit-detail-section" aria-labelledby="search-context-title">
      <h3 id="search-context-title">검색 조사 경로</h3>
      <dl className="audit-detail-grid">
        <DetailTerm label="redacted query" value={search.queryRedacted} />
        <DetailTerm label="fingerprint" value={search.queryFingerprint} mono />
        <DetailTerm label="result count" value={String(search.resultCount)} />
        <DetailTerm label="sort" value={search.sort} />
      </dl>
      <div className="audit-search-filters">
        {Object.entries(search.filters).map(([key, value]) => (
          <span key={key}>
            {key}: {value}
          </span>
        ))}
      </div>
      {search.originSearchActivityId ? (
        <button
          className="text-button"
          type="button"
          onClick={() => onSelectActivity(search.originSearchActivityId!)}
        >
          원래 검색 활동 열기
        </button>
      ) : null}
      {search.openedActivities.length ? (
        <div className="audit-opened-path">
          <strong>이 검색에서 연 결과</strong>
          {search.openedActivities.map((opened) => (
            <button
              key={opened.activityId}
              className="compact-button"
              type="button"
              onClick={() => onSelectActivity(opened.activityId)}
            >
              #{opened.ticketNumber} · {formatTime(opened.occurredAt)}
            </button>
          ))}
        </div>
      ) : null}

      {detail.action === 'SEARCH_EXECUTED' ? (
        <div className="audit-reveal-panel">
          <h4>보호된 raw query</h4>
          {!revealAllowed ? (
            <Notification tone="warning" title="Reveal 권한이 없습니다." />
          ) : !detail.protectedContentAvailable ? (
            <Notification
              tone="warning"
              title="보호 원문을 사용할 수 없습니다."
            >
              <p>보존 기간 만료 또는 key 폐기 상태를 확인하세요.</p>
            </Notification>
          ) : (
            <form onSubmit={(event) => void reveal(event)}>
              <label>
                <span>공개 사유</span>
                <textarea
                  value={reason}
                  maxLength={1000}
                  required
                  disabled={pending}
                  onChange={(event) => setReason(event.target.value)}
                />
              </label>
              <button
                className="button primary small"
                type="submit"
                aria-busy={pending}
                disabled={!reason.trim() || pending}
              >
                {pending ? '정책 확인 중…' : '이 event의 raw query 공개'}
              </button>
            </form>
          )}
          {error ? <Notification tone="danger" title={error} /> : null}
          {result?.state === 'AVAILABLE' && result.rawQuery !== null ? (
            <Notification tone="warning" title="민감한 원문이 공개되었습니다.">
              <pre className="audit-raw-query">{result.rawQuery}</pre>
              <p>이 값은 현재 drawer에만 존재하며 닫으면 제거됩니다.</p>
            </Notification>
          ) : null}
          {result && result.state !== 'AVAILABLE' ? (
            <Notification tone="warning" title={`원문 상태: ${result.state}`}>
              <p>원문은 반환되지 않았습니다. Key version: {result.keyVersion ?? 'unknown'}</p>
            </Notification>
          ) : null}
        </div>
      ) : null}
    </section>
  )
}

function DetailTerm({
  label,
  value,
  mono = false,
}: {
  label: string
  value: string | null
  mono?: boolean
}) {
  return (
    <div>
      <dt>{label}</dt>
      <dd className={mono ? 'is-mono' : undefined}>{value || '—'}</dd>
    </div>
  )
}

function DetailError({ error, retry }: { error: Error; retry: () => void }) {
  const apiError = error instanceof ApiError ? error : null
  return (
    <ScreenState
      compact
      kind={apiError?.status === 404 ? 'not-found' : 'error'}
      title={
        apiError?.status === 404
          ? '활동을 찾을 수 없습니다.'
          : '활동 상세를 불러오지 못했습니다.'
      }
      requestId={apiError?.requestId}
      action={
        apiError?.status === 404 ? null : (
          <button className="compact-button" type="button" onClick={retry}>
            다시 시도
          </button>
        )
      }
    />
  )
}

function trapDrawerFocus(
  event: KeyboardEvent<HTMLElement>,
  ref: RefObject<HTMLElement | null>,
  onClose: () => void,
) {
  if (event.key === 'Escape') {
    event.preventDefault()
    onClose()
    return
  }
  if (event.key !== 'Tab') return
  const focusable = Array.from(
    ref.current?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), a[href]',
    ) ?? [],
  )
  if (!focusable.length) return
  const first = focusable[0]!
  const last = focusable[focusable.length - 1]!
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function ledgerLabel(ledger: AuditActivityDetail['ledger']) {
  if (ledger === 'TICKET_CHANGE') return 'Ticket Change'
  if (ledger === 'ACCESS_SEARCH') return 'Access / Search'
  return 'Admin / Security'
}

function displayValue(value: unknown): string {
  if (typeof value === 'string') return value
  if (value === null || value === undefined) return '—'
  return JSON.stringify(value, null, 2)
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(new Date(value))
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value))
}
