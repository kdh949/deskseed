import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router'
import type {
  AgentComment,
  AgentTicketDetail,
  AgentTicketStatus,
  CreateExternalReferenceInput,
  ExternalObjectType,
  ExternalReferenceContext,
  MacroPreview,
  RichTextDocumentV1,
  TicketFieldName,
  TicketPriority,
  TicketVisibility,
} from '../../api/types'
import { plainTextDocument } from '../../api/types'
import {
  ApiError,
  applyAgentTicketMacro,
  createAgentTicketCollaborationNote,
  createTicketExternalReference,
  deleteTicketExternalReference,
  downloadAgentAttachment,
  listTicketExternalReferences,
  listAccessibleAgentMacros,
  listAgentTicketCollaborationNotes,
  previewAgentTicketMacro,
  uploadAgentAttachment,
} from '../../api/client'
import { createOpaqueUuid } from '../../api/uuid'
import {
  SeedAvatar,
  SeedButton,
  SeedChoiceField,
  SeedCollaborationThread,
  SeedComposer,
  SeedComposerFooter,
  SeedConflictBar,
  SeedConversationItem,
  SeedConversationTimeline,
  SeedContextCard,
  SeedDrawer,
  SeedFeedbackState,
  SeedIcon,
  SeedIconButton,
  SeedMacroMenu,
  SeedNotice,
  SeedPropertyStack,
  SeedReadOnlyField,
  SeedRichTextContent,
  SeedRichTextEditor,
  SeedSelectField,
  SeedSplitButton,
  SeedSlaMeter,
  SeedStatusBadge,
  SeedTextField,
  SeedTicketWorkspaceShell,
  SeedWorkspaceHeader,
} from '../../design-system/canonical'
import { AttachmentList } from '../attachments/AttachmentList'
import { AttachmentUploadField } from '../attachments/AttachmentUploadField'
import type { ExtensionAccess } from '../../extension-host/types'
import { ExtensionSlot } from '../../extension-host/ExtensionSlot'
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
  extensionAccess,
  refreshLatest,
  staffId,
}: {
  detail: AgentTicketDetail
  extensionAccess?: ExtensionAccess
  refreshLatest: () => Promise<AgentTicketDetail>
  staffId: string
}) {
  return detail.capabilities.includes('UPDATE') ? (
    <WritableWorkspace
      detail={detail}
      extensionAccess={extensionAccess}
      refreshLatest={refreshLatest}
      staffId={staffId}
    />
  ) : (
    <ReadOnlyWorkspace
      detail={detail}
      extensionAccess={extensionAccess}
      refreshLatest={refreshLatest}
    />
  )
}

function WritableWorkspace({
  detail,
  extensionAccess,
  refreshLatest,
  staffId,
}: {
  detail: AgentTicketDetail
  extensionAccess?: ExtensionAccess
  refreshLatest: () => Promise<AgentTicketDetail>
  staffId: string
}) {
  const editor = useTicketEditor({ detail, refreshLatest, staffId })
  return (
    <>
      <WorkspaceFrame
        detail={detail}
        extensionAccess={extensionAccess}
        onRefresh={() => void editor.refreshEditor()}
        properties={<EditableProperties detail={detail} editor={editor} />}
        refreshLatest={refreshLatest}
        conversation={
          <div className="seed-workspace-column">
            <div className="seed-workspace-column__scroll">
              <EditorFeedback editor={editor} />
              <Conversation comments={detail.comments} />
            </div>
            <div className="seed-workspace-column__composer">
              <ConflictResolution detail={detail} editor={editor} />
              <Composer
                detail={detail}
                editor={editor}
                extensionAccess={extensionAccess}
              />
            </div>
          </div>
        }
      />
      <SeedDrawer
        description="작성 중인 답변과 변경사항이 아직 제출되지 않았습니다."
        onClose={() => {
          if (editor.blocker.state === 'blocked') editor.blocker.reset()
        }}
        open={editor.blocker.state === 'blocked'}
        title="저장하지 않은 변경사항"
      >
        <p>이 페이지를 떠나면 제출하지 않은 변경사항이 사라집니다.</p>
        <div aria-label="페이지 이동 선택" role="group">
          <SeedButton
            onClick={() => {
              if (editor.blocker.state === 'blocked') editor.blocker.reset()
            }}
            variant="primary"
          >
            계속 작성
          </SeedButton>
          <SeedButton
            onClick={() => {
              if (editor.blocker.state === 'blocked') editor.blocker.proceed()
            }}
          >
            변경사항 버리고 이동
          </SeedButton>
        </div>
      </SeedDrawer>
    </>
  )
}

function ReadOnlyWorkspace({
  detail,
  extensionAccess,
  refreshLatest,
}: {
  detail: AgentTicketDetail
  extensionAccess?: ExtensionAccess
  refreshLatest: () => Promise<AgentTicketDetail>
}) {
  const [error, setError] = useState<string | null>(null)
  const refresh = async () => {
    setError(null)
    try {
      await refreshLatest()
    } catch {
      setError('최신 티켓 정보를 확인하지 못했습니다. 다시 시도해 주세요.')
    }
  }
  return (
    <WorkspaceFrame
      detail={detail}
      extensionAccess={extensionAccess}
      onRefresh={() => void refresh()}
      properties={<ReadOnlyProperties detail={detail} />}
      refreshLatest={refreshLatest}
      conversation={
        <div className="seed-workspace-column">
          <div className="seed-workspace-column__scroll">
            <SeedNotice title="읽기 전용 티켓" tone="info">
              현재 권한으로는 티켓을 수정할 수 없습니다.
            </SeedNotice>
            {error && (
              <SeedNotice title="최신 정보 확인 실패" tone="danger">
                {error}
              </SeedNotice>
            )}
            <Conversation comments={detail.comments} />
          </div>
        </div>
      }
    />
  )
}

function WorkspaceFrame({
  detail,
  extensionAccess,
  onRefresh,
  properties,
  conversation,
  refreshLatest,
}: {
  detail: AgentTicketDetail
  extensionAccess?: ExtensionAccess
  onRefresh: () => void
  properties: React.ReactNode
  conversation: React.ReactNode
  refreshLatest: () => Promise<AgentTicketDetail>
}) {
  const [contextOpen, setContextOpen] = useState(false)
  const location = useLocation()
  const [copiedMessage, setCopiedMessage] = useState('')
  const contextButtonRef = useRef<HTMLButtonElement>(null)
  const externalReferences = useExternalReferences(detail, refreshLatest)
  useEffect(() => {
    if (location.hash === '#collaboration') setContextOpen(true)
  }, [location.hash])
  const ticketLabel = `#${detail.ticket.ticketNumber}`
  const copyTicketLabel = async () => {
    setCopiedMessage('')
    try {
      await navigator.clipboard.writeText(ticketLabel)
      setCopiedMessage(`${ticketLabel}을 복사했습니다.`)
    } catch {
      setCopiedMessage(`${ticketLabel}을 복사하지 못했습니다.`)
    }
  }
  const context = (
    <TicketContext
      detail={detail}
      extensionAccess={extensionAccess}
      externalReferences={externalReferences}
    />
  )
  return (
    <section
      aria-label={`티켓 #${detail.ticket.ticketNumber} 작업 공간`}
      className="seed-workspace-route"
    >
      <SeedTicketWorkspaceShell
        contextOpen={contextOpen}
        contextReturnFocusRef={contextButtonRef}
        header={
          <SeedWorkspaceHeader
            assignee={
              detail.ticket.assignee
                ? {
                    initials: initials(detail.ticket.assignee.displayName),
                    label: detail.ticket.assignee.displayName,
                  }
                : undefined
            }
            contextButtonRef={contextButtonRef}
            copiedMessage={copiedMessage}
            onCopyTicketLabel={() => void copyTicketLabel()}
            onOpenContext={() => setContextOpen(true)}
            onRefresh={onRefresh}
            priority={{
              label: PRIORITY_LABELS[detail.ticket.priority],
              tone:
                detail.ticket.priority === 'URGENT'
                  ? 'danger'
                  : detail.ticket.priority === 'HIGH'
                    ? 'warning'
                    : 'neutral',
            }}
            sla={
              detail.ticket.sla ? (
                <SeedSlaMeter
                  detail={slaSummary(detail.ticket.sla)}
                  label="SLA"
                  percent={slaPercent(detail.ticket.sla.state)}
                  tone={slaTone(detail.ticket.sla.state)}
                />
              ) : undefined
            }
            status={
              <SeedStatusBadge tone={statusTone(detail.ticket.status)}>
                {STATUS_LABELS[detail.ticket.status]}
              </SeedStatusBadge>
            }
            ticketLabel={ticketLabel}
            title={detail.ticket.subject}
          />
        }
        properties={<aside aria-label="티켓 속성">{properties}</aside>}
        conversation={
          <section aria-label="티켓 대화 및 답변">{conversation}</section>
        }
        context={context}
        onContextClose={() => setContextOpen(false)}
      />
    </section>
  )
}

function EditableProperties({
  detail,
  editor,
}: {
  detail: AgentTicketDetail
  editor: ReturnType<typeof useTicketEditor>
}) {
  const group = detail.assignmentOptions.groups.find(
    (option) => option.id === editor.localFields.groupId,
  )
  const statusOptions = (
    Object.entries(STATUS_LABELS) as Array<[AgentTicketStatus, string]>
  ).map(([value, label]) => ({
    value,
    label,
    startAdornment: (
      <span
        aria-hidden="true"
        className={`seed-status-dot seed-status-dot--${statusTone(value)}`}
      />
    ),
  }))
  const priorityOptions = (
    Object.entries(PRIORITY_LABELS) as Array<[TicketPriority, string]>
  ).map(([value, label]) => ({
    value,
    label,
    startAdornment: <SeedIcon name="priority" size="small" />,
  }))
  const groupOptions = detail.assignmentOptions.groups.map((option) => ({
    value: option.id,
    label: option.name,
    startAdornment: <SeedIcon name="users" size="small" />,
  }))
  if (
    editor.localFields.groupId &&
    !groupOptions.some((option) => option.value === editor.localFields.groupId)
  ) {
    groupOptions.unshift({
      value: editor.localFields.groupId,
      label: detail.ticket.group?.name ?? editor.localFields.groupId,
      startAdornment: <SeedIcon name="users" size="small" />,
    })
  }
  const assigneeOptions = (group?.members ?? []).map((member) => ({
    value: member.id,
    label: member.displayName,
    startAdornment: (
      <SeedAvatar
        initials={initials(member.displayName)}
        label={member.displayName}
        size="small"
      />
    ),
  }))
  if (
    editor.localFields.assigneeId &&
    !assigneeOptions.some(
      (option) => option.value === editor.localFields.assigneeId,
    )
  ) {
    const label =
      detail.ticket.assignee?.displayName ?? editor.localFields.assigneeId
    assigneeOptions.unshift({
      value: editor.localFields.assigneeId,
      label,
      startAdornment: (
        <SeedAvatar initials={initials(label)} label={label} size="small" />
      ),
    })
  }
  return (
    <SeedPropertyStack title="티켓 속성">
      <SeedChoiceField
        disabled={editor.submitting}
        label="상태"
        onChange={(value) => editor.updateField('status', value)}
        options={statusOptions}
        value={editor.localFields.status}
      />
      <SeedChoiceField
        disabled={editor.submitting}
        label="우선순위"
        onChange={(value) => editor.updateField('priority', value)}
        options={priorityOptions}
        value={editor.localFields.priority}
      />
      <SeedChoiceField
        clearLabel="그룹 배정 해제"
        disabled={editor.submitting}
        label="그룹"
        onChange={(value) => editor.updateField('groupId', value)}
        onClear={() => editor.updateField('groupId', null)}
        options={groupOptions}
        placeholder="미배정"
        value={editor.localFields.groupId}
      />
      <SeedChoiceField
        clearLabel="담당자 배정 해제"
        disabled={editor.submitting || editor.localFields.groupId === null}
        label="담당자"
        onChange={(value) => editor.updateField('assigneeId', value)}
        onClear={() => editor.updateField('assigneeId', null)}
        options={assigneeOptions}
        placeholder="미배정"
        value={editor.localFields.assigneeId}
      />
      <SeedReadOnlyField
        label="요청자"
        value={detail.ticket.requester.displayName}
      />
      {detail.context.customer?.email && (
        <SeedReadOnlyField
          label="이메일"
          leadingIcon="mail"
          value={detail.context.customer.email}
        />
      )}
      <SeedReadOnlyField
        label="생성"
        leadingIcon="calendar"
        value={formatDate(detail.ticket.createdAt)}
      />
      {detail.ticket.sla && (
        <SeedReadOnlyField
          label="최초 답변 SLA"
          leadingIcon="clock"
          value={slaSummary(detail.ticket.sla)}
        />
      )}
    </SeedPropertyStack>
  )
}

function ReadOnlyProperties({ detail }: { detail: AgentTicketDetail }) {
  return (
    <SeedPropertyStack title="티켓 속성">
      <SeedReadOnlyField
        label="상태"
        value={STATUS_LABELS[detail.ticket.status]}
      />
      <SeedReadOnlyField
        label="우선순위"
        value={PRIORITY_LABELS[detail.ticket.priority]}
      />
      <SeedReadOnlyField
        label="그룹"
        leadingIcon="users"
        value={detail.ticket.group?.name ?? '미배정'}
      />
      <SeedReadOnlyField
        label="담당자"
        leadingIcon="user"
        value={detail.ticket.assignee?.displayName ?? '미배정'}
      />
      <SeedReadOnlyField
        label="요청자"
        value={detail.ticket.requester.displayName}
      />
      {detail.context.customer?.email && (
        <SeedReadOnlyField
          label="이메일"
          leadingIcon="mail"
          value={detail.context.customer.email}
        />
      )}
      <SeedReadOnlyField
        label="생성"
        leadingIcon="calendar"
        value={formatDate(detail.ticket.createdAt)}
      />
      {detail.ticket.sla && (
        <SeedReadOnlyField
          label="최초 답변 SLA"
          leadingIcon="clock"
          value={slaSummary(detail.ticket.sla)}
        />
      )}
    </SeedPropertyStack>
  )
}

function Conversation({ comments }: { comments: AgentComment[] }) {
  const [filter, setFilter] = useState<'ALL' | TicketVisibility>('ALL')
  if (!comments.length)
    return (
      <SeedFeedbackState
        compact
        description="이 티켓에는 아직 표시할 대화가 없습니다."
        kind="empty"
        title="대화가 비어 있습니다."
      />
    )
  const publicCount = comments.filter(
    (comment) => comment.visibility === 'PUBLIC',
  ).length
  const internalCount = comments.length - publicCount
  const visibleComments =
    filter === 'ALL'
      ? comments
      : comments.filter((comment) => comment.visibility === filter)
  return (
    <SeedConversationTimeline
      activeFilter={filter}
      filters={[
        { id: 'ALL', label: '대화', count: comments.length },
        { id: 'PUBLIC', label: 'PUBLIC', count: publicCount },
        { id: 'INTERNAL', label: 'INTERNAL', count: internalCount },
      ]}
      onFilterChange={setFilter}
      sortLabel="오래된 순"
    >
      {visibleComments.map((comment) => (
        <SeedConversationItem
          actorLabel={comment.actor.displayName}
          actorRole={comment.actor.type === 'CUSTOMER' ? '고객' : '상담사'}
          attachments={
            <AttachmentList
              attachments={comment.attachments}
              download={(attachmentId) =>
                downloadAgentAttachment(attachmentId, createOpaqueUuid())
              }
            />
          }
          dateTime={comment.createdAt}
          initials={initials(comment.actor.displayName)}
          key={comment.id}
          sourceLabel={sourceLabel(comment.source)}
          timestamp={formatDate(comment.createdAt)}
          visibility={comment.visibility}
        >
          {comment.content.format === 'RICH_TEXT_V1' ? (
            <SeedRichTextContent document={comment.content.document} />
          ) : (
            comment.body
              .split(/\n{2,}/)
              .map((paragraph, index) => (
                <p key={`${comment.id}-${index}`}>{paragraph}</p>
              ))
          )}
        </SeedConversationItem>
      ))}
    </SeedConversationTimeline>
  )
}

function Composer({
  detail,
  editor,
  extensionAccess,
}: {
  detail: AgentTicketDetail
  editor: ReturnType<typeof useTicketEditor>
  extensionAccess?: ExtensionAccess
}) {
  const mode = detail.ticket.isChild ? 'INTERNAL' : editor.mode
  const modes: TicketVisibility[] = detail.ticket.isChild
    ? ['INTERNAL']
    : ['PUBLIC', 'INTERNAL']
  const internal = mode === 'INTERNAL'
  const [resetVersions, setResetVersions] = useState<
    Record<TicketVisibility, number>
  >({ PUBLIC: 0, INTERNAL: 0 })
  const macro = useMacroLibrary(detail, editor)
  const submit = async (statusAfter?: AgentTicketStatus) => {
    if (editor.attachmentStates[mode].blocked) return
    if (await editor.submit(editor.attachmentStates[mode].ids, statusAfter)) {
      setResetVersions((current) => ({ ...current, [mode]: current[mode] + 1 }))
    }
  }
  const attachInlineImage = async (file: File) => {
    const upload = await uploadAgentAttachment(file)
    const current = editor.attachmentStates[mode]
    if (!current.ids.includes(upload.id)) {
      editor.updateAttachmentState(mode, {
        ...current,
        ids: [...current.ids, upload.id],
      })
    }
    return {
      attachmentId: upload.id,
      alt: file.name,
      previewUrl: URL.createObjectURL(file),
    }
  }
  const draftStatus = editor.submitting
    ? '저장 중…'
    : editor.draftSyncState === 'conflict'
      ? '다른 브라우저 초안과 충돌'
      : editor.draftSyncState === 'local-only'
        ? '이 브라우저에만 저장됨'
        : editor.isUnsaved
          ? '초안 변경됨'
          : '저장됨'
  return (
    <>
      <SeedComposer
        availableModes={modes}
        canSubmit={editor.canSubmit && !editor.attachmentStates[mode].blocked}
        disabled={editor.submitting}
        editor={
          <SeedRichTextEditor
            ariaLabel={internal ? '내부 메모 내용' : '공개 답변 내용'}
            disabled={editor.submitting}
            key={mode}
            onChange={(document, plainText) =>
              editor.updateRichDraft(mode, document, plainText)
            }
            onUploadImage={attachInlineImage}
            value={editor.documents[mode]}
          />
        }
        extension={
          extensionAccess ? (
            <ExtensionSlot
              access={extensionAccess}
              context={{
                ticketNumber: String(detail.ticket.ticketNumber),
                composerMode: internal ? 'internal' : 'public',
              }}
              slot="ticket-composer.toolbar"
            />
          ) : undefined
        }
        footer={
          <SeedComposerFooter
            actions={
              <>
                <SeedButton
                  disabled={!editor.isUnsaved || editor.submitting}
                  onClick={() => void editor.saveDraftNow()}
                  size="compact"
                  variant="quiet"
                >
                  초안 저장
                </SeedButton>
                <SeedSplitButton
                  actions={[
                    {
                      id: 'PENDING',
                      label: internal
                        ? '메모 저장 후 대기'
                        : '답변 후 고객 대기',
                      description: '댓글과 상태 변경을 한 번에 저장합니다.',
                    },
                    {
                      id: 'SOLVED',
                      label: internal ? '메모 저장 후 해결' : '답변 후 해결',
                      description:
                        '댓글과 해결 상태를 한 command로 제출합니다.',
                    },
                  ]}
                  busy={editor.submitting}
                  disabled={
                    !editor.canSubmit || editor.attachmentStates[mode].blocked
                  }
                  label={internal ? '내부 메모 저장' : '답변 보내기'}
                  onAction={(status) =>
                    void submit(status as AgentTicketStatus)
                  }
                  onPrimary={() => void submit()}
                />
              </>
            }
            left={
              <>
                <SeedMacroMenu
                  items={macro.items}
                  onRetry={macro.load}
                  onSelect={macro.preview}
                  state={macro.state}
                />
                <AttachmentUploadField
                  disabled={editor.submitting}
                  initialAttachmentIds={editor.attachmentStates[mode].ids}
                  key={mode}
                  label={internal ? 'INTERNAL 첨부 파일' : 'PUBLIC 첨부 파일'}
                  onStateChange={(state) =>
                    editor.updateAttachmentState(mode, state)
                  }
                  resetVersion={resetVersions[mode]}
                  upload={uploadAgentAttachment}
                />
              </>
            }
            status={
              <span className="seed-composer__draft-status">{draftStatus}</span>
            }
          />
        }
        messageLabel={internal ? '내부 메모 내용' : '공개 답변 내용'}
        mode={mode}
        onModeChange={editor.setMode}
        onSubmit={() => void submit()}
        placeholder={
          internal
            ? '팀에 공유할 확인 사항을 작성하세요.'
            : '고객에게 보낼 답변을 작성하세요.'
        }
        status={draftStatus}
        submitLabel={internal ? '내부 메모 저장' : '답변 보내기'}
      />
      {macro.message && (
        <SeedNotice
          title={
            macro.message.tone === 'positive'
              ? '매크로 적용 완료'
              : '매크로 처리 실패'
          }
          tone={macro.message.tone}
        >
          {macro.message.text}
        </SeedNotice>
      )}
      <SeedDrawer
        description="서버가 계산한 변경사항과 답변을 검토한 뒤 티켓에 적용합니다."
        onClose={() => macro.setReviewOpen(false)}
        open={macro.reviewOpen}
        title={`${macro.selectedName} 검토`}
      >
        {macro.previewState === 'loading' ? (
          <SeedFeedbackState
            kind="loading"
            title="매크로 변경을 계산하는 중입니다."
          />
        ) : macro.previewState === 'error' ? (
          <SeedFeedbackState
            action={
              macro.selectedId ? (
                <SeedButton onClick={() => macro.preview(macro.selectedId!)}>
                  다시 시도
                </SeedButton>
              ) : undefined
            }
            kind="error"
            title="매크로 미리보기를 불러오지 못했습니다."
          />
        ) : macro.currentPreview ? (
          <div className="seed-macro-review">
            <header>
              <h3>{macro.selectedName}</h3>
              <SeedStatusBadge tone="info">
                버전 {macro.currentPreview.macroVersion}
              </SeedStatusBadge>
            </header>
            {macro.currentPreview.changes.length > 0 && (
              <dl>
                {macro.currentPreview.changes.map((change) => (
                  <div key={change.field}>
                    <dt>{change.field}</dt>
                    <dd>
                      <span>{change.before ?? '없음'}</span>
                      <SeedIcon name="chevron" size="small" />
                      <strong>{change.after ?? '없음'}</strong>
                    </dd>
                  </div>
                ))}
              </dl>
            )}
            {macro.currentPreview.comment && macro.reviewDocument && (
              <section>
                <h4>
                  {macro.currentPreview.comment.visibility === 'PUBLIC'
                    ? 'PUBLIC 답변'
                    : 'INTERNAL 메모'}
                </h4>
                <SeedRichTextEditor
                  ariaLabel="매크로 답변 검토"
                  onChange={(document) => macro.setReviewDocument(document)}
                  value={macro.reviewDocument}
                />
              </section>
            )}
            <footer>
              <SeedButton onClick={() => macro.setReviewOpen(false)}>
                취소
              </SeedButton>
              <SeedButton
                disabled={macro.applying}
                onClick={() => void macro.apply()}
                variant="primary"
              >
                {macro.applying ? '적용 중…' : '매크로 적용'}
              </SeedButton>
            </footer>
          </div>
        ) : null}
      </SeedDrawer>
    </>
  )
}

function useMacroLibrary(
  detail: AgentTicketDetail,
  editor: ReturnType<typeof useTicketEditor>,
) {
  const interactionId = useMemo(createOpaqueUuid, [detail.ticket.ticketNumber])
  const [macros, setMacros] = useState<
    Awaited<ReturnType<typeof listAccessibleAgentMacros>>
  >([])
  const [state, setState] = useState<
    'idle' | 'loading' | 'empty' | 'error' | 'denied'
  >('loading')
  const [previewState, setPreviewState] = useState<
    'idle' | 'loading' | 'error'
  >('idle')
  const [currentPreview, setCurrentPreview] = useState<MacroPreview | null>(
    null,
  )
  const [reviewDocument, setReviewDocument] =
    useState<RichTextDocumentV1 | null>(null)
  const [reviewOpen, setReviewOpen] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [applying, setApplying] = useState(false)
  const [message, setMessage] = useState<{
    tone: 'positive' | 'danger'
    text: string
  } | null>(null)
  const load = useCallback(async () => {
    setState('loading')
    try {
      const response = await listAccessibleAgentMacros()
      const active = response.filter((macro) => macro.activeVersion !== null)
      setMacros(active)
      setState(active.length ? 'idle' : 'empty')
    } catch (cause) {
      setState(
        cause instanceof ApiError && cause.status === 403 ? 'denied' : 'error',
      )
    }
  }, [])
  useEffect(() => {
    void load()
  }, [load])
  const preview = async (macroId: string) => {
    setSelectedId(macroId)
    setReviewOpen(true)
    setPreviewState('loading')
    setMessage(null)
    try {
      const result = await previewAgentTicketMacro(
        detail.ticket.ticketNumber,
        macroId,
        interactionId,
      )
      setCurrentPreview(result)
      setReviewDocument(
        result.comment
          ? result.comment.content.format === 'RICH_TEXT_V1'
            ? result.comment.content.document
            : plainTextDocument(result.comment.body)
          : null,
      )
      setPreviewState('idle')
    } catch {
      setPreviewState('error')
    }
  }
  const apply = async () => {
    if (!selectedId || !currentPreview) return
    setApplying(true)
    setMessage(null)
    try {
      await applyAgentTicketMacro(
        detail.ticket.ticketNumber,
        selectedId,
        currentPreview,
        currentPreview.comment && reviewDocument
          ? { format: 'RICH_TEXT_V1', document: reviewDocument }
          : null,
        createOpaqueUuid(),
      )
      setReviewOpen(false)
      setMessage({
        tone: 'positive',
        text: '검토한 매크로 변경사항을 티켓에 적용했습니다.',
      })
      await editor.refreshEditor()
    } catch (cause) {
      const stale =
        cause instanceof ApiError &&
        (cause.status === 409 || cause.status === 412)
      setMessage({
        tone: 'danger',
        text: stale
          ? '티켓 또는 매크로 버전이 바뀌었습니다. 미리보기를 다시 확인해 주세요.'
          : '매크로를 적용하지 못했습니다. 입력은 변경되지 않았습니다.',
      })
      if (stale) setPreviewState('error')
    } finally {
      setApplying(false)
    }
  }
  return {
    apply,
    applying,
    currentPreview,
    items: macros.map((macro) => ({
      id: macro.id,
      label: macro.name,
      description: macro.scope === 'PERSONAL' ? '개인 매크로' : '공유 매크로',
    })),
    load: () => void load(),
    message,
    preview: (id: string) => void preview(id),
    previewState,
    reviewDocument,
    reviewOpen,
    selectedId,
    selectedName:
      macros.find((macro) => macro.id === selectedId)?.name ?? '매크로',
    setReviewDocument,
    setReviewOpen,
    state,
  }
}

function EditorFeedback({
  editor,
}: {
  editor: ReturnType<typeof useTicketEditor>
}) {
  return (
    <>
      {editor.error && (
        <SeedNotice
          title={
            editor.error.saved
              ? '저장 후 최신 상태 확인 실패'
              : '변경사항 저장 실패'
          }
          tone="danger"
        >
          {editor.error.message}
          {editor.error.requestId && <p>요청 ID: {editor.error.requestId}</p>}
        </SeedNotice>
      )}
      {editor.draftError && (
        <SeedNotice title="복구 초안 동기화 실패" tone="warning">
          {editor.draftError.message}
          {editor.draftError.requestId && (
            <p>요청 ID: {editor.draftError.requestId}</p>
          )}
        </SeedNotice>
      )}
      {editor.success && (
        <SeedNotice title="저장 완료" tone="positive">
          {editor.success}
        </SeedNotice>
      )}
      {editor.warnings.map((warning) => (
        <SeedNotice
          key={warning.code}
          title="저장 전후 확인 사항"
          tone="warning"
        >
          {warning.message}
          {warning.count > 0 ? ` (${warning.count}건)` : ''}
        </SeedNotice>
      ))}
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
  const [compareOpen, setCompareOpen] = useState(false)
  const compareButtonRef = useRef<HTMLButtonElement>(null)
  const conflict = editor.conflict
  if (!conflict) return null
  const description = conflict.loadingLatest
    ? '최신 티켓 값을 불러오고 있습니다.'
    : conflict.latestError || !conflict.latestFields
      ? '최신 티켓 값을 확인하지 못했습니다. 초안은 보존되어 있습니다.'
      : `다른 탭에서 ${[...conflict.fields].map((field) => FIELD_LABELS[field]).join(', ')} 값이 변경되었습니다.`
  return (
    <>
      <SeedConflictBar
        actions={
          conflict.latestFields ? (
            <>
              <SeedButton
                onClick={() => editor.resolveAllFields('SERVER')}
                size="compact"
              >
                최신본 적용
              </SeedButton>
              <SeedButton
                onClick={() => setCompareOpen(true)}
                ref={compareButtonRef}
                size="compact"
              >
                비교
              </SeedButton>
              <SeedButton
                onClick={() => editor.resolveAllFields('LOCAL')}
                size="compact"
                variant="primary"
              >
                내 초안 유지
              </SeedButton>
            </>
          ) : (
            <SeedButton
              onClick={() =>
                void editor.loadLatestForConflict(new Set(conflict.fields))
              }
              size="compact"
            >
              다시 시도
            </SeedButton>
          )
        }
        containerRef={editor.conflictRef}
        description={description}
        title="저장 충돌"
      />
      <SeedDrawer
        description="충돌한 필드마다 서버 최신 값과 내 초안 중 하나를 선택하세요."
        onClose={() => setCompareOpen(false)}
        open={compareOpen}
        returnFocusRef={compareButtonRef}
        title="티켓 저장 충돌 비교"
      >
        {conflict.latestFields ? (
          <ol className="seed-conflict-comparison">
            {[...conflict.fields].map((field) => (
              <li key={field}>
                <h3>{FIELD_LABELS[field]}</h3>
                <dl>
                  <div>
                    <dt>내 초안</dt>
                    <dd>{formatValue(field, editor.localFields, detail)}</dd>
                  </div>
                  <div>
                    <dt>서버 최신 값</dt>
                    <dd>
                      {formatValue(field, conflict.latestFields!, detail)}
                    </dd>
                  </div>
                </dl>
                <div>
                  <SeedButton
                    onClick={() => editor.resolveField(field, 'SERVER')}
                  >
                    {FIELD_LABELS[field]}에서 서버 값 적용
                  </SeedButton>
                  <SeedButton
                    onClick={() => editor.resolveField(field, 'LOCAL')}
                    variant="primary"
                  >
                    {FIELD_LABELS[field]}에서 내 초안 유지
                  </SeedButton>
                </div>
              </li>
            ))}
          </ol>
        ) : (
          <SeedFeedbackState
            compact
            kind="loading"
            title="최신 값을 확인하는 중입니다."
          />
        )}
      </SeedDrawer>
    </>
  )
}

function TicketContext({
  detail,
  extensionAccess,
  externalReferences,
}: {
  detail: AgentTicketDetail
  extensionAccess?: ExtensionAccess
  externalReferences: ReturnType<typeof useExternalReferences>
}) {
  const related = [detail.context.parent, ...detail.context.children].flatMap(
    (ticket) => (ticket ? [ticket] : []),
  )
  const collaboration = useCollaborationNotes(detail)
  const people = detail.assignmentOptions.groups
    .flatMap((group) => group.members)
    .filter(
      (person, index, all) =>
        all.findIndex((candidate) => candidate.id === person.id) === index,
    )
    .map((person) => ({
      id: person.id,
      label: person.displayName,
      initials: initials(person.displayName),
    }))
  return (
    <div className="seed-context-stack">
      <SeedContextCard title="고객">
        {detail.context.customer ? (
          <div className="seed-context-person">
            <SeedAvatar
              initials={initials(detail.context.customer.displayName)}
              label={detail.context.customer.displayName}
            />
            <span>
              <strong>{detail.context.customer.displayName}</strong>
              <small>
                <SeedIcon name="mail" size="small" />{' '}
                {detail.context.customer.email}
              </small>
              <small>고객 ID {detail.context.customer.id}</small>
            </span>
          </div>
        ) : (
          <p>고객 컨텍스트가 제공되지 않았습니다.</p>
        )}
      </SeedContextCard>
      <SeedContextCard
        title="관련 티켓"
        badge={
          <SeedStatusBadge tone="neutral">{related.length}</SeedStatusBadge>
        }
      >
        {related.length > 0 ? (
          <ul className="seed-related-tickets">
            {related.slice(0, 4).map((ticket) => (
              <li key={ticket.ticketNumber}>
                <Link to={`/agent/tickets/${ticket.ticketNumber}`}>
                  #{ticket.ticketNumber}
                </Link>
                <span>{ticket.subject}</span>
                <SeedStatusBadge tone={statusTone(ticket.status)}>
                  {STATUS_LABELS[ticket.status]}
                </SeedStatusBadge>
              </li>
            ))}
          </ul>
        ) : (
          <p className="seed-context-empty">
            연결된 상위·하위 티켓이 없습니다.
          </p>
        )}
      </SeedContextCard>
      <SeedCollaborationThread
        canWrite={detail.capabilities.includes('UPDATE')}
        notes={collaboration.notes.map((note) => ({
          id: note.id,
          author: note.author.displayName,
          initials: initials(note.author.displayName),
          body: note.body,
          timestamp: formatDate(note.createdAt),
          mentionLabels: note.mentionedStaff.map(
            (staff) => `@${staff.displayName}`,
          ),
        }))}
        onRetry={collaboration.load}
        onSubmit={collaboration.create}
        people={people}
        state={collaboration.state}
        submitting={collaboration.submitting}
      />
      {extensionAccess && (
        <ExtensionSlot
          access={extensionAccess}
          context={{ ticketNumber: String(detail.ticket.ticketNumber) }}
          slot="ticket-workspace.context"
        />
      )}
      <ExternalReferencesCard
        controller={externalReferences}
        fallbackCount={detail.context.externalReferenceCount}
      />
      <SeedContextCard title="최근 활동">
        {detail.history.length > 0 ? (
          <ol className="seed-context-history">
            {detail.history.slice(0, 4).map((item) => (
              <li key={item.id}>
                <SeedAvatar
                  initials={initials(item.actor.displayName)}
                  label={item.actor.displayName}
                />
                <span>
                  <strong>{historyLabel(item.eventType)}</strong>
                  <small>
                    <time dateTime={item.occurredAt}>
                      {formatDate(item.occurredAt)}
                    </time>
                  </small>
                </span>
              </li>
            ))}
          </ol>
        ) : (
          <p className="seed-context-empty">표시할 최근 활동이 없습니다.</p>
        )}
      </SeedContextCard>
    </div>
  )
}

function useCollaborationNotes(detail: AgentTicketDetail) {
  const interactionId = useMemo(createOpaqueUuid, [detail.ticket.ticketNumber])
  const [notes, setNotes] = useState<
    Awaited<ReturnType<typeof listAgentTicketCollaborationNotes>>['items']
  >([])
  const [state, setState] = useState<
    'idle' | 'loading' | 'empty' | 'error' | 'denied'
  >('loading')
  const [submitting, setSubmitting] = useState(false)
  const load = useCallback(async () => {
    setState('loading')
    try {
      const page = await listAgentTicketCollaborationNotes(
        detail.ticket.ticketNumber,
        interactionId,
      )
      setNotes(page.items)
      setState(page.items.length ? 'idle' : 'empty')
    } catch (cause) {
      setState(
        cause instanceof ApiError && cause.status === 403 ? 'denied' : 'error',
      )
    }
  }, [detail.ticket.ticketNumber, interactionId])
  useEffect(() => {
    void load()
  }, [load])
  const create = async (body: string, mentionedStaffIds: string[]) => {
    setSubmitting(true)
    try {
      const result = await createAgentTicketCollaborationNote(
        detail.ticket.ticketNumber,
        {
          body,
          mentionedStaffIds,
          clientCommandId: createOpaqueUuid(),
        },
      )
      setNotes((current) => [
        result.note,
        ...current.filter((note) => note.id !== result.note.id),
      ])
      setState('idle')
      return true
    } catch (cause) {
      setState(
        cause instanceof ApiError && cause.status === 403 ? 'denied' : 'error',
      )
      return false
    } finally {
      setSubmitting(false)
    }
  }
  return { create, load: () => void load(), notes, state, submitting }
}

function useExternalReferences(
  detail: AgentTicketDetail,
  refreshLatest: () => Promise<AgentTicketDetail>,
) {
  const interactionId = useMemo(createOpaqueUuid, [detail.ticket.ticketNumber])
  const [context, setContext] = useState<ExternalReferenceContext | null>(null)
  const [status, setStatus] = useState<
    'loading' | 'loaded' | 'error' | 'denied'
  >('loading')
  const [message, setMessage] = useState<string | null>(null)
  const [mutating, setMutating] = useState(false)

  const load = useCallback(async () => {
    setStatus('loading')
    setMessage(null)
    try {
      setContext(
        await listTicketExternalReferences(
          detail.ticket.ticketNumber,
          interactionId,
        ),
      )
      setStatus('loaded')
    } catch (cause) {
      const apiError = cause instanceof ApiError ? cause : null
      setStatus(apiError?.status === 403 ? 'denied' : 'error')
      setMessage(
        `${apiError?.status === 403 ? '외부 참조를 볼 권한이 없습니다.' : '외부 참조를 불러오지 못했습니다.'}${apiError?.requestId ? ` 요청 ID: ${apiError.requestId}` : ''}`,
      )
    }
  }, [detail.ticket.ticketNumber, interactionId])

  useEffect(() => {
    void load()
  }, [load])

  const create = async (
    input: Omit<CreateExternalReferenceInput, 'expectedVersion'>,
  ) => {
    if (!context?.canManage) return false
    setMutating(true)
    setMessage(null)
    try {
      const result = await createTicketExternalReference(
        detail.ticket.ticketNumber,
        {
          ...input,
          expectedVersion: context.ticketVersion,
        },
      )
      setContext((current) =>
        current
          ? {
              ...current,
              items: [...current.items, result.reference],
              ticketVersion: result.ticketVersion,
            }
          : current,
      )
      setMessage('외부 참조를 연결했습니다.')
      try {
        await refreshLatest()
      } catch {
        setMessage(
          '외부 참조는 연결했지만 티켓 최신 상태를 다시 확인하지 못했습니다.',
        )
      }
      return true
    } catch (cause) {
      const apiError = cause instanceof ApiError ? cause : null
      setMessage(
        `${apiError?.status === 409 || apiError?.status === 412 ? '티켓 버전이 바뀌었습니다. 최신 외부 참조를 다시 확인해 주세요.' : '외부 참조를 연결하지 못했습니다.'}${apiError?.requestId ? ` 요청 ID: ${apiError.requestId}` : ''}`,
      )
      if (apiError?.status === 409 || apiError?.status === 412) void load()
      return false
    } finally {
      setMutating(false)
    }
  }

  const remove = async (referenceId: string) => {
    if (!context?.canManage) return
    setMutating(true)
    setMessage(null)
    try {
      const result = await deleteTicketExternalReference(
        detail.ticket.ticketNumber,
        referenceId,
        context.ticketVersion,
      )
      setContext((current) =>
        current
          ? {
              ...current,
              items: current.items.filter(
                (item) => item.id !== result.removedReferenceId,
              ),
              ticketVersion: result.ticketVersion,
            }
          : current,
      )
      setMessage('외부 참조 연결을 해제했습니다.')
      try {
        await refreshLatest()
      } catch {
        setMessage(
          '외부 참조는 해제했지만 티켓 최신 상태를 다시 확인하지 못했습니다.',
        )
      }
    } catch (cause) {
      const apiError = cause instanceof ApiError ? cause : null
      setMessage(
        `${apiError?.status === 409 || apiError?.status === 412 ? '티켓 버전이 바뀌었습니다. 최신 외부 참조를 다시 확인해 주세요.' : '외부 참조 연결을 해제하지 못했습니다.'}${apiError?.requestId ? ` 요청 ID: ${apiError.requestId}` : ''}`,
      )
      if (apiError?.status === 409 || apiError?.status === 412) void load()
    } finally {
      setMutating(false)
    }
  }

  return { context, create, load, message, mutating, remove, status }
}

const EXTERNAL_OBJECT_TYPE_LABELS: Record<ExternalObjectType, string> = {
  ORDER: '주문',
  PAYMENT: '결제',
  REFUND: '환불',
  USER: '사용자',
  STORE: '스토어',
  OPS_CASE: '운영 케이스',
  CUSTOM: '기타',
}

function ExternalReferencesCard({
  controller,
  fallbackCount,
}: {
  controller: ReturnType<typeof useExternalReferences>
  fallbackCount: number
}) {
  const [formOpen, setFormOpen] = useState(false)
  const [externalSystemId, setExternalSystemId] = useState('')
  const [objectType, setObjectType] = useState<ExternalObjectType>('ORDER')
  const [externalId, setExternalId] = useState('')
  const [displayLabel, setDisplayLabel] = useState('')
  const [safeDeepLink, setSafeDeepLink] = useState('')
  const availableSystems =
    controller.context?.availableSystems.filter(
      (system) => system.status === 'ACTIVE',
    ) ?? []
  const selectedSystemId = externalSystemId || availableSystems[0]?.id || ''
  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!selectedSystemId) return
    const saved = await controller.create({
      externalSystemId: selectedSystemId,
      objectType,
      externalId: externalId.trim(),
      displayLabel: displayLabel.trim(),
      safeDeepLink: safeDeepLink.trim(),
      metadata: {},
      metadataObservedAt: new Date().toISOString(),
    })
    if (!saved) return
    setExternalId('')
    setDisplayLabel('')
    setSafeDeepLink('')
    setFormOpen(false)
  }
  const count = controller.context?.items.length ?? fallbackCount
  return (
    <SeedContextCard
      action={
        controller.context?.canManage ? (
          <SeedIconButton
            icon={formOpen ? 'x' : 'plus'}
            label={formOpen ? '외부 참조 입력 닫기' : '외부 참조 연결'}
            onClick={() => setFormOpen((current) => !current)}
            variant="quiet"
          />
        ) : undefined
      }
      badge={<SeedStatusBadge tone="neutral">{count}</SeedStatusBadge>}
      title="외부 참조"
    >
      {controller.status === 'loading' ? (
        <SeedFeedbackState
          compact
          kind="loading"
          title="외부 참조를 불러오는 중입니다."
        />
      ) : controller.status === 'error' ? (
        <SeedFeedbackState
          action={
            <SeedButton onClick={() => void controller.load()}>
              다시 시도
            </SeedButton>
          }
          compact
          description={controller.message ?? undefined}
          kind="error"
          title="외부 참조를 불러오지 못했습니다."
        />
      ) : controller.status === 'denied' ? (
        <SeedFeedbackState
          compact
          description={controller.message ?? undefined}
          kind="denied"
          title="외부 참조 권한이 없습니다."
        />
      ) : (
        <>
          {controller.message && (
            <p className="seed-external-references__message" role="status">
              {controller.message}
            </p>
          )}
          {controller.context?.items.length ? (
            <ul className="seed-external-references">
              {controller.context.items.map((reference) => (
                <li key={reference.id}>
                  <span>
                    {reference.safeDeepLink ? (
                      <a
                        href={reference.safeDeepLink}
                        rel="noopener noreferrer"
                        target="_blank"
                      >
                        {reference.displayLabel}
                      </a>
                    ) : (
                      <strong>{reference.displayLabel}</strong>
                    )}
                    <small>
                      {reference.system.displayName} ·{' '}
                      {EXTERNAL_OBJECT_TYPE_LABELS[reference.objectType]} ·{' '}
                      {reference.externalId}
                    </small>
                  </span>
                  {controller.context?.canManage && (
                    <SeedIconButton
                      disabled={controller.mutating}
                      icon="x"
                      label={`${reference.displayLabel} 연결 해제`}
                      onClick={() => void controller.remove(reference.id)}
                      variant="quiet"
                    />
                  )}
                </li>
              ))}
            </ul>
          ) : (
            <p className="seed-context-empty">연결된 외부 참조가 없습니다.</p>
          )}
          {formOpen && (
            <form
              className="seed-external-reference-form"
              onSubmit={(event) => void submit(event)}
            >
              {availableSystems.length ? (
                <>
                  <SeedSelectField
                    label="외부 시스템"
                    onChange={(event) =>
                      setExternalSystemId(event.target.value)
                    }
                    required
                    value={selectedSystemId}
                  >
                    {availableSystems.map((system) => (
                      <option key={system.id} value={system.id}>
                        {system.displayName}
                      </option>
                    ))}
                  </SeedSelectField>
                  <SeedSelectField
                    label="대상 유형"
                    onChange={(event) =>
                      setObjectType(event.target.value as ExternalObjectType)
                    }
                    required
                    value={objectType}
                  >
                    {Object.entries(EXTERNAL_OBJECT_TYPE_LABELS).map(
                      ([value, label]) => (
                        <option key={value} value={value}>
                          {label}
                        </option>
                      ),
                    )}
                  </SeedSelectField>
                  <SeedTextField
                    label="외부 ID"
                    onChange={(event) => setExternalId(event.target.value)}
                    required
                    value={externalId}
                  />
                  <SeedTextField
                    label="표시 이름"
                    onChange={(event) => setDisplayLabel(event.target.value)}
                    required
                    value={displayLabel}
                  />
                  <SeedTextField
                    label="안전한 HTTPS 링크"
                    onChange={(event) => setSafeDeepLink(event.target.value)}
                    required
                    type="url"
                    value={safeDeepLink}
                  />
                  <SeedButton
                    disabled={controller.mutating}
                    type="submit"
                    variant="primary"
                  >
                    {controller.mutating ? '연결 중' : '외부 참조 연결'}
                  </SeedButton>
                </>
              ) : (
                <SeedNotice
                  title="연결 가능한 시스템이 없습니다."
                  tone="warning"
                >
                  관리자가 활성 외부 시스템을 등록해야 합니다.
                </SeedNotice>
              )}
            </form>
          )}
        </>
      )}
    </SeedContextCard>
  )
}

function formatValue(
  field: TicketFieldName,
  fields: EditableTicketFields,
  detail: AgentTicketDetail,
) {
  if (field === 'status') return STATUS_LABELS[fields.status]
  if (field === 'priority') return PRIORITY_LABELS[fields.priority]
  if (field === 'groupId')
    return (
      detail.assignmentOptions.groups.find(
        (group) => group.id === fields.groupId,
      )?.name ?? '미배정'
    )
  return (
    detail.assignmentOptions.groups
      .flatMap((group) => group.members)
      .find((member) => member.id === fields.assigneeId)?.displayName ??
    '미배정'
  )
}

function statusTone(
  status: AgentTicketStatus,
): 'neutral' | 'info' | 'positive' | 'warning' {
  if (status === 'OPEN' || status === 'SOLVED' || status === 'CLOSED')
    return 'positive'
  if (status === 'PENDING' || status === 'ON_HOLD') return 'warning'
  if (status === 'NEW') return 'info'
  return 'neutral'
}

function slaPercent(
  state: NonNullable<AgentTicketDetail['ticket']['sla']>['state'],
) {
  return {
    ACTIVE: 48,
    AT_RISK: 72,
    PAUSED: 52,
    ACHIEVED: 100,
    BREACHED: 100,
    CANCELLED: 0,
    NO_POLICY: 0,
  }[state]
}

function slaTone(
  state: NonNullable<AgentTicketDetail['ticket']['sla']>['state'],
): 'positive' | 'warning' | 'danger' | 'neutral' {
  if (state === 'BREACHED') return 'danger'
  if (state === 'AT_RISK') return 'warning'
  if (state === 'CANCELLED' || state === 'NO_POLICY' || state === 'PAUSED')
    return 'neutral'
  return 'positive'
}

function initials(name: string) {
  return (
    name
      .trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((part) => part[0])
      .join('')
      .toUpperCase() || 'DS'
  )
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function sourceLabel(source: string) {
  return (
    {
      WEB: '웹',
      STAFF_WEB: '상담사 UI',
      AGENT_UI: '상담사 UI',
      PLATFORM_API: '플랫폼 API',
      TRIGGER: '트리거',
      AUTOMATION: '자동화',
    }[source] ?? source
  )
}

function slaSummary(sla: NonNullable<AgentTicketDetail['ticket']['sla']>) {
  const label = {
    ACTIVE: '진행 중',
    AT_RISK: '위험',
    PAUSED: '일시 정지',
    ACHIEVED: '달성',
    BREACHED: '위반',
    CANCELLED: '취소',
    NO_POLICY: '정책 없음',
  }[sla.state]
  return sla.dueAt
    ? `${label} · ${new Intl.DateTimeFormat('ko-KR', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(new Date(sla.dueAt))}`
    : label
}

function historyLabel(eventType: string) {
  return (
    {
      TICKET_CREATED: '티켓 생성',
      COMMENT_CREATED: '코멘트 추가',
      TICKET_UPDATED: '티켓 업데이트',
    }[eventType] ?? '티켓 활동'
  )
}
