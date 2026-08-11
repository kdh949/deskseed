import type {
  AgentTicketDetail,
  AgentTicketStatus,
  TicketFieldName,
  TicketPriority,
} from '../../api/types'
import type { RefObject } from 'react'
import { Notification } from '../../shared/ui/system'
import type { EditableTicketFields } from './ticketEditorModel'
import type { TicketConflictState } from './useTicketEditor'

const FIELD_LABELS: Record<TicketFieldName, string> = {
  status: '상태',
  priority: '우선순위',
  groupId: '그룹',
  assigneeId: '담당자',
}

const STATUS_OPTIONS: Array<[AgentTicketStatus, string]> = [
  ['NEW', '신규'],
  ['OPEN', '처리 중'],
  ['PENDING', '고객 답변 대기'],
  ['ON_HOLD', '보류'],
  ['SOLVED', '해결됨'],
]

const PRIORITY_OPTIONS: Array<[TicketPriority, string]> = [
  ['LOW', '낮음'],
  ['NORMAL', '보통'],
  ['HIGH', '높음'],
  ['URGENT', '긴급'],
]

export function TicketPropertiesEditor({
  detail,
  localFields,
  conflict,
  conflictRef,
  disabled,
  onFieldChange,
  onResolveConflict,
  onReloadConflict,
}: {
  detail: AgentTicketDetail
  localFields: EditableTicketFields
  conflict: TicketConflictState | null
  conflictRef: RefObject<HTMLDivElement>
  disabled: boolean
  onFieldChange: (
    field: TicketFieldName,
    value: EditableTicketFields[TicketFieldName],
  ) => void
  onResolveConflict: (
    field: TicketFieldName,
    choice: 'SERVER' | 'LOCAL',
  ) => void
  onReloadConflict: () => void
}) {
  const selectedGroup = detail.assignmentOptions.groups.find(
    (group) => group.id === localFields.groupId,
  )
  return (
    <section className="ticket-properties-panel" aria-label="티켓 속성">
      <header className="workspace-panel-header">
        <h2>속성</h2>
        <span>v{detail.ticket.version}</span>
      </header>
      {conflict ? (
        <Notification
          tone="conflict"
          title="변경 충돌 — 최신 정보 확인 필요"
          className="ticket-conflict-banner"
          ref={conflictRef}
          tabIndex={-1}
        >
          <p>
            {Array.from(conflict.fields, (field) => FIELD_LABELS[field]).join(
              ', ',
            )}{' '}
            필드 저장에 실패했습니다. 최신 티켓을 확인하고 각 값을 선택해
            주세요.
          </p>
          {conflict.requestId ? (
            <p className="ds-request-id">
              요청 ID: <code>{conflict.requestId}</code>
            </p>
          ) : null}
          {conflict.loadingLatest ? (
            <p role="status">최신 티켓 확인 중</p>
          ) : null}
          {conflict.latestError ? (
            <div>
              <p>{conflict.latestError}</p>
              <button
                className="text-button"
                type="button"
                onClick={onReloadConflict}
              >
                최신 정보 다시 확인
              </button>
            </div>
          ) : null}
          {conflict.latestFields ? (
            <ul className="conflict-field-list">
              {Array.from(conflict.fields).map((field) => (
                <li key={field}>
                  <strong>{FIELD_LABELS[field]}</strong>
                  <span>
                    서버:{' '}
                    {formatFieldValue(
                      detail,
                      field,
                      conflict.latestFields![field],
                    )}
                  </span>
                  <span>
                    내 입력:{' '}
                    {formatFieldValue(detail, field, localFields[field])}
                  </span>
                  <div>
                    <button
                      className="compact-button"
                      type="button"
                      onClick={() => onResolveConflict(field, 'SERVER')}
                    >
                      서버 값 사용
                    </button>
                    <button
                      className="compact-button"
                      type="button"
                      onClick={() => onResolveConflict(field, 'LOCAL')}
                    >
                      내 변경으로 재시도
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          ) : null}
        </Notification>
      ) : null}
      <div className="ticket-property-form">
        <label>
          <span>상태</span>
          <select
            aria-label="상태"
            value={localFields.status}
            disabled={disabled}
            onChange={(event) =>
              onFieldChange('status', event.target.value as AgentTicketStatus)
            }
          >
            {STATUS_OPTIONS.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
            {localFields.status === 'CLOSED' ? (
              <option value="CLOSED">종료</option>
            ) : null}
          </select>
        </label>
        <label>
          <span>우선순위</span>
          <select
            aria-label="우선순위"
            value={localFields.priority}
            disabled={disabled}
            onChange={(event) =>
              onFieldChange('priority', event.target.value as TicketPriority)
            }
          >
            {PRIORITY_OPTIONS.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <div className="read-only-property">
          <span>요청자</span>
          <strong>{detail.ticket.requester.displayName}</strong>
        </div>
        <label>
          <span>그룹</span>
          <select
            aria-label="그룹"
            value={localFields.groupId ?? ''}
            disabled={disabled}
            onChange={(event) =>
              onFieldChange('groupId', event.target.value || null)
            }
          >
            <option value="">미배정</option>
            {detail.assignmentOptions.groups.map((group) => (
              <option key={group.id} value={group.id}>
                {group.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>담당자</span>
          <select
            aria-label="담당자"
            value={localFields.assigneeId ?? ''}
            disabled={disabled || !selectedGroup}
            onChange={(event) =>
              onFieldChange('assigneeId', event.target.value || null)
            }
          >
            <option value="">미배정</option>
            {selectedGroup?.members.map((member) => (
              <option key={member.id} value={member.id}>
                {member.displayName}
              </option>
            ))}
          </select>
        </label>
        <div className="read-only-property">
          <span>업데이트</span>
          <strong>{formatDate(detail.ticket.updatedAt)}</strong>
        </div>
        <div className="read-only-property">
          <span>티켓 유형</span>
          <strong>{detail.ticket.isChild ? 'Child task' : '일반 티켓'}</strong>
        </div>
      </div>
      <div className="read-boundary-note">
        <strong>
          {disabled
            ? '읽기 전용 · ALL_TICKETS'
            : '쓰기 범위: GROUP_OR_ASSIGNEE'}
        </strong>
        <p>
          {disabled
            ? '현재 티켓은 읽을 수 있지만 수정 권한은 없습니다.'
            : '그룹·담당자 정책은 저장 시 서버에서 다시 검증합니다.'}
        </p>
      </div>
    </section>
  )
}

function formatFieldValue(
  detail: AgentTicketDetail,
  field: TicketFieldName,
  value: EditableTicketFields[TicketFieldName],
) {
  if (field === 'status') {
    return (
      STATUS_OPTIONS.find(([status]) => status === value)?.[1] ?? String(value)
    )
  }
  if (field === 'priority') {
    return (
      PRIORITY_OPTIONS.find(([priority]) => priority === value)?.[1] ??
      String(value)
    )
  }
  if (field === 'groupId') {
    return (
      detail.assignmentOptions.groups.find((group) => group.id === value)
        ?.name ?? '미배정'
    )
  }
  return (
    detail.assignmentOptions.groups
      .flatMap((group) => group.members)
      .find((member) => member.id === value)?.displayName ?? '미배정'
  )
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
