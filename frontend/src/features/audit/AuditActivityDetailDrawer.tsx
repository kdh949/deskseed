import type { AuditActivityDetail } from '../../api/types'
import {
  DeskseedIcon,
  DsButton,
  DsDrawer,
  ScreenState,
} from '../../design-system'

export type AuditActivityDetailState =
  | { status: 'loading' }
  | { status: 'error'; requestId?: string }
  | { status: 'ready'; detail: AuditActivityDetail }

export interface AuditActivityDetailDrawerProps {
  onClose: () => void
  onOpenActivity: (activityId: string) => void
  onRetry: () => void
  open: boolean
  state: AuditActivityDetailState | null
}

export function AuditActivityDetailDrawer({
  onClose,
  onOpenActivity,
  onRetry,
  open,
  state,
}: AuditActivityDetailDrawerProps) {
  return (
    <DsDrawer
      description="감사 활동의 정규 이벤트 정보와 상관관계를 확인합니다."
      onClose={onClose}
      open={open}
      title="감사 활동 상세"
    >
      {state?.status === 'loading' ? (
        <ScreenState
          compact
          kind="loading"
          title="상세 정보를 불러오고 있습니다."
        />
      ) : null}
      {state?.status === 'error' ? (
        <ScreenState
          action={<DsButton onClick={onRetry}>다시 시도</DsButton>}
          compact
          kind="error"
          requestId={state.requestId}
          title="상세 정보를 불러오지 못했습니다."
        />
      ) : null}
      {state?.status === 'ready' ? (
        <div className="ds-audit-detail">
          <section aria-label="정규 이벤트">
            <dl>
              <div>
                <dt>정규 이벤트 ID</dt>
                <dd>{state.detail.canonicalEventId}</dd>
              </div>
              {state.detail.canonicalParentId ? (
                <div>
                  <dt>상위 이벤트 ID</dt>
                  <dd>{state.detail.canonicalParentId}</dd>
                </div>
              ) : null}
              <div>
                <dt>레저</dt>
                <dd>{state.detail.ledger}</dd>
              </div>
              <div>
                <dt>액션</dt>
                <dd>{state.detail.action}</dd>
              </div>
              <div>
                <dt>요약</dt>
                <dd>{state.detail.summary}</dd>
              </div>
            </dl>
          </section>

          <section aria-label="액터 및 세션">
            <dl>
              <div>
                <dt>액터</dt>
                <dd>
                  {state.detail.actor.displayName} ({state.detail.actor.type})
                </dd>
              </div>
              {state.detail.authType ? (
                <div>
                  <dt>인증 방식</dt>
                  <dd>{state.detail.authType}</dd>
                </div>
              ) : null}
              {state.detail.sessionFingerprint ? (
                <div>
                  <dt>세션 지문</dt>
                  <dd>{state.detail.sessionFingerprint}</dd>
                </div>
              ) : null}
              {state.detail.ipAddress ? (
                <div>
                  <dt>IP 주소</dt>
                  <dd>{state.detail.ipAddress}</dd>
                </div>
              ) : null}
              {state.detail.userAgent ? (
                <div>
                  <dt>User-Agent</dt>
                  <dd>{state.detail.userAgent}</dd>
                </div>
              ) : null}
            </dl>
          </section>

          <section aria-label="상관관계">
            <dl>
              {state.detail.requestId ? (
                <div>
                  <dt>요청 ID</dt>
                  <dd>{state.detail.requestId}</dd>
                </div>
              ) : null}
              {state.detail.correlationId ? (
                <div>
                  <dt>상관관계 ID</dt>
                  <dd>{state.detail.correlationId}</dd>
                </div>
              ) : null}
              {state.detail.interactionId ? (
                <div>
                  <dt>인터랙션 ID</dt>
                  <dd>{state.detail.interactionId}</dd>
                </div>
              ) : null}
            </dl>
          </section>

          {state.detail.fieldChange ? (
            <section aria-label="변경 내용">
              <h3>{state.detail.fieldChange.field}</h3>
              <div className="ds-audit-detail-diff">
                <div>
                  <span>이전</span>
                  <pre>{formatValue(state.detail.fieldChange.before)}</pre>
                </div>
                <div>
                  <span>이후</span>
                  <pre>{formatValue(state.detail.fieldChange.after)}</pre>
                </div>
              </div>
            </section>
          ) : null}

          {state.detail.protectedContentAvailable ? (
            <section aria-label="보호된 내용">
              <p className="ds-audit-detail-protected">
                <DeskseedIcon name="lock" size="sm" />
                보호된 내용이 있습니다. 이 화면에서는 원문을 공개할 수 없습니다.
              </p>
            </section>
          ) : null}

          {state.detail.search ? (
            <section aria-label="검색 컨텍스트">
              <dl>
                <div>
                  <dt>검색어(마스킹됨)</dt>
                  <dd>{state.detail.search.queryRedacted}</dd>
                </div>
                <div>
                  <dt>결과 수</dt>
                  <dd>{state.detail.search.resultCount}</dd>
                </div>
              </dl>
              {state.detail.search.openedActivities.length > 0 ? (
                <div>
                  <p>이 검색에서 열람한 티켓</p>
                  <ul className="ds-audit-detail-opened">
                    {state.detail.search.openedActivities.map((opened) => (
                      <li key={opened.activityId}>
                        <button
                          onClick={() => onOpenActivity(opened.activityId)}
                          type="button"
                        >
                          티켓 #{opened.ticketNumber}
                        </button>
                      </li>
                    ))}
                  </ul>
                  {state.detail.search.openedActivitiesTruncated ? (
                    <small>일부 항목만 표시됩니다.</small>
                  ) : null}
                </div>
              ) : null}
            </section>
          ) : null}
        </div>
      ) : null}
    </DsDrawer>
  )
}

function formatValue(value: unknown) {
  if (value === null || value === undefined) return '(없음)'
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}
