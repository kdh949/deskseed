import { useState, type ReactNode } from 'react'
import type {
  AgentComment,
  AgentTicketDetail,
  AgentTicketStatus,
  TicketAssignmentGroupOption,
  TicketFieldName,
  TicketPriority,
  TicketVisibility,
} from '../../api/types'
import {
  DsButton,
  DsPropertyField,
  DsTabs,
  Notification,
  RetryButton,
  ScreenState,
  StatusBadge,
} from '../../design-system'
import type { EditableTicketFields } from './model/ticketEditorModel'
import { useTicketEditor } from './model/useTicketEditor'

const STATUS_LABELS: Record<AgentTicketStatus, string> = {
  NEW: '신규',
  OPEN: '처리 중',
  PENDING: '고객 답변 대기',
  ON_HOLD: '보류',
  SOLVED: '해결됨',
  CLOSED: '종료',
}

const PRIORITY_LABELS: Record<TicketPriority, string> = {
  LOW: '낮음',
  NORMAL: '보통',
  HIGH: '높음',
  URGENT: '긴급',
}

const FIELD_LABELS: Record<TicketFieldName, string> = {
  status: '상태',
  priority: '우선순위',
  groupId: '그룹',
  assigneeId: '담당자',
}

export function AgentTicketEditorWorkspace({
  detail,
  refreshLatest,
  staffId,
}: {
  detail: AgentTicketDetail
  refreshLatest: () => Promise<AgentTicketDetail>
  staffId: string
}) {
  if (!detail.capabilities.includes('UPDATE')) {
    return <ReadOnlyTicketWorkspace detail={detail} onRefresh={refreshLatest} />
  }

  return (
    <WritableTicketWorkspace
      detail={detail}
      refreshLatest={refreshLatest}
      staffId={staffId}
    />
  )
}

function WritableTicketWorkspace({
  detail,
  refreshLatest,
  staffId,
}: {
  detail: AgentTicketDetail
  refreshLatest: () => Promise<AgentTicketDetail>
  staffId: string
}) {
  const editor = useTicketEditor({ detail, refreshLatest, staffId })

  return (
    <WorkspaceLayout
      detail={detail}
      onRefresh={() => void editor.refreshEditor()}
      properties={<WritableProperties detail={detail} editor={editor} />}
    >
      <div className="agent-ticket-editor-content">
        {editor.conflict ? (
          <ConflictResolution detail={detail} editor={editor} />
        ) : null}
        <EditorFeedback editor={editor} />
        <Conversation comments={detail.comments} />
      </div>
      <ReplyComposer detail={detail} editor={editor} />
      {editor.blocker.state === 'blocked' ? (
        <section
          aria-labelledby="ticket-navigation-guard-title"
          aria-modal="true"
          className="agent-ticket-navigation-guard"
          role="dialog"
        >
          <h2 id="ticket-navigation-guard-title">저장하지 않은 변경사항</h2>
          <p>
            이 페이지를 나가면 현재 브라우저의 초안만 남고 서버에는 저장되지
            않습니다.
          </p>
          <div className="agent-ticket-navigation-guard-actions">
            <DsButton onClick={() => editor.blocker.reset?.()} tone="secondary">
              계속 작성
            </DsButton>
            <DsButton onClick={() => editor.blocker.proceed?.()} tone="primary">
              변경사항 버리고 나가기
            </DsButton>
          </div>
        </section>
      ) : null}
    </WorkspaceLayout>
  )
}

function ReadOnlyTicketWorkspace({
  detail,
  onRefresh,
}: {
  detail: AgentTicketDetail
  onRefresh: () => Promise<AgentTicketDetail>
}) {
  const [refreshError, setRefreshError] = useState<string | null>(null)

  const refresh = async () => {
    setRefreshError(null)
    try {
      await onRefresh()
    } catch {
      setRefreshError(
        '최신 티켓 정보를 확인하지 못했습니다. 다시 시도해 주세요.',
      )
    }
  }

  return (
    <WorkspaceLayout
      detail={detail}
      onRefresh={() => void refresh()}
      properties={<ReadOnlyProperties detail={detail} />}
    >
      <div className="agent-ticket-editor-content">
        <Notification title="읽기 전용 티켓" tone="info">
          <p>현재 권한으로는 티켓을 수정할 수 없습니다.</p>
        </Notification>
        {refreshError ? (
          <Notification title="최신 정보 확인 실패" tone="danger">
            <p>{refreshError}</p>
          </Notification>
        ) : null}
        <Conversation comments={detail.comments} />
      </div>
    </WorkspaceLayout>
  )
}

function WorkspaceLayout({
  children,
  detail,
  onRefresh,
  properties,
}: {
  children: ReactNode
  detail: AgentTicketDetail
  onRefresh?: () => void
  properties: ReactNode
}) {
  return (
    <main
      aria-label={`티켓 #${detail.ticket.ticketNumber} 작업 공간`}
      className="ticket-workspace ticket-workspace--production"
    >
      <aside aria-label="티켓 속성" className="ticket-properties">
        {properties}
      </aside>
      <section aria-label="티켓 대화 및 답변" className="ticket-conversation">
        <TicketHeader detail={detail} onRefresh={onRefresh} />
        {children}
      </section>
      <CustomerContext detail={detail} />
    </main>
  )
}

function TicketHeader({
  detail,
  onRefresh,
}: {
  detail: AgentTicketDetail
  onRefresh?: () => void
}) {
  return (
    <header className="ticket-workspace-heading">
      <div className="ticket-heading-title">
        <div>
          <p className="agent-ticket-editor-eyebrow">
            티켓 #{detail.ticket.ticketNumber}
          </p>
          <h1>{detail.ticket.subject}</h1>
          <p>
            최근 업데이트{' '}
            <time dateTime={detail.ticket.updatedAt}>
              {formatDate(detail.ticket.updatedAt)}
            </time>
          </p>
        </div>
      </div>
      <div className="ticket-heading-actions">
        <StatusBadge status={detail.ticket.status} />
        {onRefresh ? (
          <DsButton onClick={onRefresh} tone="secondary">
            최신 정보 새로고침
          </DsButton>
        ) : null}
      </div>
    </header>
  )
}

function ReadOnlyProperties({ detail }: { detail: AgentTicketDetail }) {
  return (
    <>
      <div className="ticket-panel-heading">
        <h2>티켓 속성</h2>
      </div>
      <dl className="agent-ticket-properties-readonly">
        <div>
          <dt>상태</dt>
          <dd>
            <StatusBadge status={detail.ticket.status} />
          </dd>
        </div>
        <div>
          <dt>우선순위</dt>
          <dd>{PRIORITY_LABELS[detail.ticket.priority]}</dd>
        </div>
        <div>
          <dt>그룹</dt>
          <dd>{detail.ticket.group?.name ?? '미배정'}</dd>
        </div>
        <div>
          <dt>담당자</dt>
          <dd>{detail.ticket.assignee?.displayName ?? '미배정'}</dd>
        </div>
        <div>
          <dt>요청자</dt>
          <dd>{detail.ticket.requester.displayName}</dd>
        </div>
      </dl>
    </>
  )
}

function WritableProperties({
  detail,
  editor,
}: {
  detail: AgentTicketDetail
  editor: ReturnType<typeof useTicketEditor>
}) {
  const selectedGroup = detail.assignmentOptions.groups.find(
    (group) => group.id === editor.localFields.groupId,
  )
  const availableMembers = selectedGroup?.members ?? []

  return (
    <>
      <div className="ticket-panel-heading">
        <h2>티켓 속성</h2>
        <p>변경은 답변 또는 메모와 함께 한 번에 저장됩니다.</p>
      </div>
      <div className="ticket-properties-form agent-ticket-properties-form">
        <DsPropertyField label="상태">
          <select
            aria-label="상태"
            className="agent-ticket-editor-select"
            onChange={(event) =>
              editor.updateField(
                'status',
                event.currentTarget.value as AgentTicketStatus,
              )
            }
            value={editor.localFields.status}
          >
            {Object.entries(STATUS_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </DsPropertyField>
        <DsPropertyField label="우선순위">
          <select
            aria-label="우선순위"
            className="agent-ticket-editor-select"
            onChange={(event) =>
              editor.updateField(
                'priority',
                event.currentTarget.value as TicketPriority,
              )
            }
            value={editor.localFields.priority}
          >
            {Object.entries(PRIORITY_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </DsPropertyField>
        <DsPropertyField label="그룹">
          <select
            aria-label="그룹"
            className="agent-ticket-editor-select"
            onChange={(event) =>
              editor.updateField('groupId', event.currentTarget.value || null)
            }
            value={editor.localFields.groupId ?? ''}
          >
            <CurrentSelectionOption
              currentId={editor.localFields.groupId}
              currentLabel={detail.ticket.group?.name}
              options={detail.assignmentOptions.groups}
            />
            <option value="">미배정</option>
            {detail.assignmentOptions.groups.map((group) => (
              <option key={group.id} value={group.id}>
                {group.name}
              </option>
            ))}
          </select>
        </DsPropertyField>
        <DsPropertyField label="담당자">
          <select
            aria-label="담당자"
            className="agent-ticket-editor-select"
            disabled={editor.localFields.groupId === null}
            onChange={(event) =>
              editor.updateField(
                'assigneeId',
                event.currentTarget.value || null,
              )
            }
            value={editor.localFields.assigneeId ?? ''}
          >
            <CurrentSelectionOption
              currentId={editor.localFields.assigneeId}
              currentLabel={detail.ticket.assignee?.displayName}
              options={availableMembers}
            />
            <option value="">미배정</option>
            {availableMembers.map((member) => (
              <option key={member.id} value={member.id}>
                {member.displayName}
              </option>
            ))}
          </select>
        </DsPropertyField>
        <DsPropertyField label="요청자">
          <p className="agent-ticket-property-value">
            {detail.ticket.requester.displayName}
          </p>
        </DsPropertyField>
      </div>
    </>
  )
}

function CurrentSelectionOption({
  currentId,
  currentLabel,
  options,
}: {
  currentId: string | null
  currentLabel?: string
  options: Array<{ id: string }>
}) {
  if (!currentId || options.some((option) => option.id === currentId)) {
    return null
  }
  return (
    <option disabled value={currentId}>
      현재 값: {currentLabel ?? currentId}
    </option>
  )
}

function Conversation({ comments }: { comments: AgentComment[] }) {
  if (comments.length === 0) {
    return (
      <section aria-label="티켓 대화 기록" className="conversation-timeline">
        <ScreenState
          compact
          description="이 티켓에는 아직 표시할 대화가 없습니다."
          kind="empty"
          title="대화가 비어 있습니다."
        />
      </section>
    )
  }

  return (
    <section aria-label="티켓 대화 기록" className="conversation-timeline">
      {comments.map((comment) => (
        <article
          className={`conversation-entry conversation-entry--${comment.visibility.toLowerCase()}`}
          key={comment.id}
        >
          <div className="conversation-entry-body">
            <div className="conversation-meta">
              <strong>{comment.actor.displayName}</strong>
              <span>{actorLabel(comment)}</span>
              <span className="conversation-visibility">
                {comment.visibility === 'PUBLIC'
                  ? 'PUBLIC · 고객에게 표시됨'
                  : 'INTERNAL · 직원 전용'}
              </span>
              <time dateTime={comment.createdAt}>
                {formatDate(comment.createdAt)}
              </time>
            </div>
            {comment.body.split(/\n{2,}/).map((paragraph, index) => (
              <p key={`${comment.id}-${index}`}>{paragraph}</p>
            ))}
          </div>
        </article>
      ))}
    </section>
  )
}

function ReplyComposer({
  detail,
  editor,
}: {
  detail: AgentTicketDetail
  editor: ReturnType<typeof useTicketEditor>
}) {
  const modes: TicketVisibility[] = detail.ticket.isChild
    ? ['INTERNAL']
    : ['PUBLIC', 'INTERNAL']
  const mode = detail.ticket.isChild ? 'INTERNAL' : editor.mode
  const isInternal = mode === 'INTERNAL'
  const actionLabel = isInternal ? '내부 메모 저장' : '공개 답변 저장'
  const inputLabel = isInternal ? '내부 메모 내용' : '공개 답변 내용'

  return (
    <section
      aria-label="답변 작성"
      className={`reply-composer reply-composer--${isInternal ? 'internal' : 'public'}`}
    >
      {modes.length > 1 ? (
        <DsTabs
          activeId={mode}
          ariaLabel="답변 공개 범위"
          className="reply-composer-tabs"
          items={modes.map((visibility) => ({
            id: visibility,
            ariaLabel:
              visibility === 'PUBLIC'
                ? '공개 답변 작성 모드로 전환'
                : '내부 메모 작성 모드로 전환',
            label:
              visibility === 'PUBLIC'
                ? 'PUBLIC · 고객에게 전송됨'
                : 'INTERNAL · 직원 전용',
            panelId: `agent-ticket-composer-${visibility}`,
          }))}
          onChange={editor.setMode}
        />
      ) : (
        <p className="agent-ticket-editor-mode">INTERNAL · 직원 전용</p>
      )}
      <div
        aria-labelledby={
          modes.length > 1 ? `agent-ticket-composer-${mode}-tab` : undefined
        }
        id={`agent-ticket-composer-${mode}`}
        role="tabpanel"
      >
        <p aria-live="polite" className="agent-ticket-editor-mode-hint">
          {isInternal
            ? '내부 메모는 고객에게 공개되지 않습니다.'
            : '공개 답변은 고객 대화에 표시됩니다.'}
        </p>
        <label className="reply-composer-input">
          <span className="sr-only">{inputLabel}</span>
          <textarea
            aria-label={inputLabel}
            maxLength={20_000}
            onChange={(event) => editor.updateDraft(mode, event.target.value)}
            placeholder={
              isInternal
                ? '팀에 공유할 확인 사항을 작성하세요.'
                : '고객에게 보낼 답변을 작성하세요.'
            }
            value={editor.comments[mode]}
          />
        </label>
        <div className="reply-composer-footer">
          <p aria-live="polite" className="reply-composer-draft-status">
            {editor.submitting
              ? '저장 중입니다.'
              : editor.isUnsaved
                ? '복구용 초안이 이 브라우저에 저장되었습니다.'
                : '저장하지 않은 초안이 없습니다.'}
          </p>
          <DsButton
            disabled={!editor.canSubmit}
            onClick={() => void editor.submit()}
            tone="primary"
          >
            {actionLabel}
          </DsButton>
        </div>
      </div>
    </section>
  )
}

function EditorFeedback({
  editor,
}: {
  editor: ReturnType<typeof useTicketEditor>
}) {
  return (
    <>
      {editor.error ? (
        <Notification
          title={
            editor.error.saved
              ? '변경사항은 저장됐지만 최신 상태를 확인하지 못했습니다.'
              : '변경사항을 저장하지 못했습니다.'
          }
          tone="danger"
        >
          <p>{editor.error.message}</p>
          {editor.error.requestId ? (
            <p>요청 ID: {editor.error.requestId}</p>
          ) : null}
        </Notification>
      ) : null}
      {editor.success ? (
        <Notification title="저장 완료" tone="success">
          <p>{editor.success}</p>
        </Notification>
      ) : null}
      {editor.warnings.length > 0 ? (
        <Notification title="저장 전후 확인 사항" tone="warning">
          <ul>
            {editor.warnings.map((warning) => (
              <li key={warning.code}>
                {warning.message}
                {warning.count > 0 ? ` (${warning.count}건)` : ''}
              </li>
            ))}
          </ul>
        </Notification>
      ) : null}
    </>
  )
}

function ConflictResolution({
  detail,
  editor,
}: {
  detail: AgentTicketDetail
  editor: ReturnType<typeof useTicketEditor>
}) {
  const conflict = editor.conflict
  if (!conflict) return null

  return (
    <div
      aria-label="티켓 저장 충돌"
      className="agent-ticket-conflict"
      ref={editor.conflictRef}
      role="region"
      tabIndex={-1}
    >
      <Notification title="티켓 저장 충돌" tone="conflict">
        <p>
          다른 변경사항이 먼저 저장되었습니다. 초안은 보존되어 있으며, 각 필드의
          값을 직접 선택해야 합니다.
        </p>
        {conflict.requestId ? <p>요청 ID: {conflict.requestId}</p> : null}
      </Notification>
      {conflict.loadingLatest ? (
        <ScreenState
          compact
          kind="loading"
          title="최신 티켓 값을 불러오고 있습니다."
        />
      ) : conflict.latestError || !conflict.latestFields ? (
        <ScreenState
          action={
            <RetryButton
              onClick={() =>
                void editor.loadLatestForConflict(new Set(conflict.fields))
              }
            />
          }
          compact
          description={conflict.latestError}
          kind="error"
          requestId={conflict.requestId}
          title="충돌 값을 확인할 수 없습니다."
        />
      ) : (
        <ol className="agent-ticket-conflict-fields">
          {[...conflict.fields].map((field) => (
            <li key={field}>
              <h3>{FIELD_LABELS[field]}</h3>
              <dl>
                <div>
                  <dt>내 초안</dt>
                  <dd>
                    {formatEditableValue(field, editor.localFields, detail)}
                  </dd>
                </div>
                <div>
                  <dt>서버 값</dt>
                  <dd>
                    {formatEditableValue(field, conflict.latestFields!, detail)}
                  </dd>
                </div>
              </dl>
              <div className="agent-ticket-conflict-actions">
                <DsButton
                  onClick={() => editor.resolveField(field, 'SERVER')}
                  tone="secondary"
                >
                  {FIELD_LABELS[field]}에서 서버 값 적용
                </DsButton>
                <DsButton
                  onClick={() => editor.resolveField(field, 'LOCAL')}
                  tone="primary"
                >
                  {FIELD_LABELS[field]}에서 내 초안 유지
                </DsButton>
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

function CustomerContext({ detail }: { detail: AgentTicketDetail }) {
  const customer = detail.context.customer
  return (
    <aside aria-label="고객 정보" className="agent-ticket-editor-context">
      <h2>고객 정보</h2>
      {customer ? (
        <dl>
          <div>
            <dt>이름</dt>
            <dd>{customer.displayName}</dd>
          </div>
          <div>
            <dt>이메일</dt>
            <dd>{customer.email}</dd>
          </div>
        </dl>
      ) : (
        <p>연결된 고객 정보가 없습니다.</p>
      )}
    </aside>
  )
}

function actorLabel(comment: AgentComment) {
  if (comment.actor.type === 'CUSTOMER') return '고객'
  if (comment.actor.type === 'STAFF') return '상담사'
  if (comment.actor.type === 'SYSTEM') return '시스템'
  return comment.actor.type
}

function formatEditableValue(
  field: TicketFieldName,
  fields: EditableTicketFields,
  detail: AgentTicketDetail,
) {
  if (field === 'status') return STATUS_LABELS[fields.status]
  if (field === 'priority') return PRIORITY_LABELS[fields.priority]
  if (field === 'groupId') {
    if (fields.groupId === null) return '미배정'
    return groupLabel(fields.groupId, detail.assignmentOptions.groups)
  }
  if (fields.assigneeId === null) return '미배정'
  return assigneeLabel(fields.assigneeId, detail.assignmentOptions.groups)
}

function groupLabel(groupId: string, groups: TicketAssignmentGroupOption[]) {
  return groups.find((group) => group.id === groupId)?.name ?? groupId
}

function assigneeLabel(
  assigneeId: string,
  groups: TicketAssignmentGroupOption[],
) {
  return (
    groups
      .flatMap((group) => group.members)
      .find((member) => member.id === assigneeId)?.displayName ?? assigneeId
  )
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
