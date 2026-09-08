import { useMemo, useRef, useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import {
  ApiError,
  createChildTicket,
  getAgentTicket,
  transferAgentTicket,
} from '../../api/client'
import type {
  CreateChildTicketCommand,
  TicketPriority,
  TransferTicketCommand,
} from '../../api/types'
import { createOpaqueUuid } from '../../api/uuid'
import {
  SeedButton,
  SeedContextCard,
  SeedDrawer,
  SeedFeedbackState,
  SeedNotice,
  SeedSelectField,
  SeedTextAreaField,
  SeedTextField,
} from '../../design-system/canonical'
import './collaboration-actions.css'

type Mode = 'transfer' | 'child'
type Attempt =
  | { mode: 'transfer'; command: TransferTicketCommand }
  | { mode: 'child'; command: CreateChildTicketCommand }
const EMPTY_TRANSFER = { groupId: '', assigneeId: '', reason: '' }
const EMPTY_CHILD = {
  groupId: '',
  assigneeId: '',
  subject: '',
  body: '',
  priority: 'NORMAL' as TicketPriority,
}

export function TicketCollaborationActions({
  ticketNumber,
}: {
  ticketNumber: number
}) {
  const client = useQueryClient()
  const [mode, setMode] = useState<Mode | null>(null)
  const [transfer, setTransfer] = useState(EMPTY_TRANSFER)
  const [child, setChild] = useState(EMPTY_CHILD)
  const [attempt, setAttempt] = useState<Attempt | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [mustRefresh, setMustRefresh] = useState(false)
  const [outcomeReviewRefreshed, setOutcomeReviewRefreshed] = useState(false)
  const [success, setSuccess] = useState<{
    mode: Mode
    childNumber?: number
  } | null>(null)
  const interaction = useMemo(createOpaqueUuid, [ticketNumber])
  const trigger = useRef<HTMLButtonElement | null>(null)
  const details = useQuery({
    queryKey: ['ticket-collaboration-command', ticketNumber],
    queryFn: () => getAgentTicket(ticketNumber, interaction, 'BACKGROUND'),
    enabled: mode !== null,
    retry: false,
    staleTime: 0,
  })
  const needsOutcomeReview =
    attempt?.mode === 'child' &&
    error instanceof ApiError &&
    error.status === 409 &&
    error.problem?.type === '/problems/client-command-id-reused'
  const draft = mode === 'transfer' ? transfer : child
  const groups = details.data?.assignmentOptions.groups ?? []
  const selectedGroup = groups.find((g) => g.id === draft.groupId)
  const writable =
    details.data?.capabilities.includes('UPDATE') &&
    details.data.ticket.status !== 'CLOSED'
  const open = (next: Mode) => {
    setMode(attempt?.mode ?? next)
    if (!attempt) {
      setError(null)
      setMustRefresh(false)
    }
  }
  const refresh = async () => {
    const result = await details.refetch()
    if (!result.error) {
      setMustRefresh(false)
      if (needsOutcomeReview) setOutcomeReviewRefreshed(true)
    }
  }
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (
      submitting ||
      mustRefresh ||
      needsOutcomeReview ||
      !mode ||
      !details.data ||
      !writable
    )
      return
    if (
      !attempt &&
      (!selectedGroup ||
        (draft.assigneeId &&
          !selectedGroup.members.some((m) => m.id === draft.assigneeId)))
    )
      return
    const pending: Attempt =
      attempt ??
      (mode === 'transfer'
        ? {
            mode,
            command: {
              expectedVersion: details.data.ticket.version,
              groupId: transfer.groupId,
              assigneeId: transfer.assigneeId || null,
              reason: transfer.reason.trim(),
              clientCommandId: createOpaqueUuid(),
            },
          }
        : {
            mode,
            command: {
              expectedVersion: details.data.ticket.version,
              groupId: child.groupId,
              assigneeId: child.assigneeId || null,
              subject: child.subject.trim(),
              body: child.body.trim(),
              priority: child.priority,
              clientCommandId: createOpaqueUuid(),
            },
          })
    setOutcomeReviewRefreshed(false)
    setAttempt(pending)
    setSubmitting(true)
    setError(null)
    try {
      if (pending.mode === 'transfer') {
        await transferAgentTicket(ticketNumber, pending.command)
        setTransfer(EMPTY_TRANSFER)
        setSuccess({ mode: 'transfer' })
      } else {
        const result = await createChildTicket(ticketNumber, pending.command)
        setChild(EMPTY_CHILD)
        setSuccess({ mode: 'child', childNumber: result.childTicketNumber })
      }
      setAttempt(null)
      setOutcomeReviewRefreshed(false)
      setMode(null)
      await Promise.all([
        client.invalidateQueries({ queryKey: ['agent-ticket', ticketNumber] }),
        client.invalidateQueries({
          queryKey: ['ticket-collaboration-command', ticketNumber],
        }),
        client.invalidateQueries({ queryKey: ['agent-views'] }),
        client.invalidateQueries({ queryKey: ['agent-view'] }),
      ])
    } catch (cause) {
      setError(cause)
      // A later rejection cannot establish whether an earlier ambiguous attempt committed.
      if (
        cause instanceof ApiError &&
        cause.status >= 400 &&
        cause.status < 500 &&
        cause.status !== 408 &&
        !attempt &&
        cause.problem?.type !== '/problems/client-command-id-reused'
      ) {
        setAttempt(null)
        if (cause.status === 409 || cause.status === 412) setMustRefresh(true)
      }
    } finally {
      setSubmitting(false)
    }
  }
  const updateAssignment = (field: 'groupId' | 'assigneeId', value: string) => {
    const patch =
      field === 'groupId'
        ? { groupId: value, assigneeId: '' }
        : { assigneeId: value }
    if (mode === 'transfer') setTransfer((d) => ({ ...d, ...patch }))
    else setChild((d) => ({ ...d, ...patch }))
  }
  return (
    <SeedContextCard title="이관과 내부 협업">
      <p>
        이관은 이 티켓의 담당을 바꾸고, 내부 협업 요청은 현재 담당을 유지한 채
        별도 티켓을 만듭니다.
      </p>
      <div className="ticket-collaboration-actions">
        <SeedButton
          onClick={(event) => {
            trigger.current = event.currentTarget
            open('transfer')
          }}
        >
          티켓 이관
        </SeedButton>
        <SeedButton
          onClick={(event) => {
            trigger.current = event.currentTarget
            open('child')
          }}
        >
          내부 협업 요청
        </SeedButton>
      </div>
      {success && (
        <div role="status">
          {success.mode === 'transfer' ? (
            '티켓을 이관했습니다.'
          ) : (
            <>
              내부 협업 요청을 만들었습니다.{' '}
              <Link to={`/agent/tickets/${success.childNumber}`}>
                협업 티켓 #{success.childNumber} 열기
              </Link>
            </>
          )}
        </div>
      )}
      <SeedDrawer
        open={mode !== null}
        title={mode === 'transfer' ? '티켓 이관' : '내부 협업 요청'}
        description={
          mode === 'transfer'
            ? '새 담당 그룹과 담당자가 이 티켓을 이어서 처리합니다.'
            : '현재 티켓의 담당은 유지됩니다. 요청 내용은 직원에게만 공개됩니다.'
        }
        onClose={() => {
          if (!submitting) setMode(null)
        }}
        returnFocusRef={trigger}
      >
        {details.isPending ? (
          <SeedFeedbackState
            kind="loading"
            title="현재 담당과 요청 권한을 확인하고 있습니다."
          />
        ) : details.isError ? (
          <SeedFeedbackState
            kind={
              details.error instanceof ApiError && details.error.status === 403
                ? 'denied'
                : 'error'
            }
            title="티켓 정보를 확인할 수 없습니다."
            action={
              <SeedButton onClick={() => void refresh()}>다시 확인</SeedButton>
            }
          />
        ) : !writable ? (
          <SeedFeedbackState
            kind="denied"
            title="이 티켓에서 이관이나 협업 요청을 할 수 없습니다."
          />
        ) : (
          <form
            onSubmit={(event) => void submit(event)}
            className="ticket-collaboration-form"
          >
            <p>
              현재 담당: {details.data.ticket.group?.name ?? '미배정'} /{' '}
              {details.data.ticket.assignee?.displayName ?? '담당자 없음'}
            </p>
            {!!error && (
              <SeedNotice
                tone="warning"
                title={
                  needsOutcomeReview
                    ? '기존 협업 요청을 확인해 주세요.'
                    : mustRefresh
                      ? '최신 티켓을 확인해 주세요.'
                      : attempt
                        ? '저장 결과가 확인되지 않았습니다.'
                        : '요청을 완료하지 못했습니다.'
                }
              >
                {needsOutcomeReview
                  ? '이미 처리된 요청일 수 있습니다. 관련 협업 티켓에서 요청 내용이 접수됐는지 확인한 뒤 마무리해 주세요.'
                  : attempt
                    ? '입력한 요청을 그대로 다시 보내 결과를 확인합니다. 중복 티켓을 만들지 않도록 현재 입력을 유지합니다.'
                    : error instanceof ApiError
                      ? error.message
                      : '입력은 유지됩니다.'}
              </SeedNotice>
            )}
            {needsOutcomeReview && (
              <div>
                <SeedButton
                  disabled={details.isFetching}
                  onClick={() => void refresh()}
                >
                  관련 협업 티켓 새로고침
                </SeedButton>
                <ul>
                  {details.data.context.children.map((ticket) => (
                    <li key={ticket.ticketNumber}>
                      <Link
                        to={`/agent/tickets/${ticket.ticketNumber}`}
                        target="_blank"
                        rel="noopener noreferrer"
                      >
                        #{ticket.ticketNumber} {ticket.subject}
                      </Link>
                    </li>
                  ))}
                </ul>
                {details.data.context.children.length > 0 && (
                  <SeedButton
                    disabled={details.isFetching || !outcomeReviewRefreshed}
                    onClick={() => {
                      setAttempt(null)
                      setOutcomeReviewRefreshed(false)
                      setChild(EMPTY_CHILD)
                      setError(null)
                      setMode(null)
                    }}
                  >
                    기존 협업 요청을 확인했습니다
                  </SeedButton>
                )}
              </div>
            )}
            {mustRefresh && (
              <SeedButton onClick={() => void refresh()}>
                입력을 유지하고 최신 버전 확인
              </SeedButton>
            )}
            <fieldset
              disabled={
                submitting || !!attempt || mustRefresh || details.isFetching
              }
            >
              <legend>요청 대상과 내용</legend>
              <SeedSelectField
                label="대상 그룹"
                required
                value={draft.groupId}
                onChange={(e) => updateAssignment('groupId', e.target.value)}
              >
                <option value="">그룹 선택</option>
                {groups.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </SeedSelectField>
              <SeedSelectField
                label="대상 담당자"
                value={draft.assigneeId}
                onChange={(e) => updateAssignment('assigneeId', e.target.value)}
                disabled={!selectedGroup}
              >
                <option value="">담당자 미지정</option>
                {selectedGroup?.members.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.displayName}
                  </option>
                ))}
              </SeedSelectField>
              {mode === 'transfer' ? (
                <SeedTextAreaField
                  label="이관 사유"
                  required
                  maxLength={2000}
                  value={transfer.reason}
                  onChange={(e) =>
                    setTransfer({ ...transfer, reason: e.target.value })
                  }
                />
              ) : (
                <>
                  <SeedTextField
                    label="협업 요청 제목"
                    required
                    maxLength={200}
                    value={child.subject}
                    onChange={(e) =>
                      setChild({ ...child, subject: e.target.value })
                    }
                  />
                  <SeedTextAreaField
                    label="내부 요청 내용"
                    required
                    maxLength={20000}
                    value={child.body}
                    onChange={(e) =>
                      setChild({ ...child, body: e.target.value })
                    }
                  />
                  <SeedSelectField
                    label="협업 요청 우선순위"
                    value={child.priority}
                    onChange={(e) =>
                      setChild({
                        ...child,
                        priority: e.target.value as TicketPriority,
                      })
                    }
                  >
                    {Object.entries({
                      LOW: '낮음',
                      NORMAL: '보통',
                      HIGH: '높음',
                      URGENT: '긴급',
                    }).map(([v, label]) => (
                      <option key={v} value={v}>
                        {label}
                      </option>
                    ))}
                  </SeedSelectField>
                </>
              )}
            </fieldset>
            {groups.length === 0 && <p>요청할 수 있는 활성 그룹이 없습니다.</p>}
            <SeedButton
              type="submit"
              variant="primary"
              disabled={
                submitting ||
                mustRefresh ||
                needsOutcomeReview ||
                (!attempt &&
                  (!selectedGroup ||
                    (mode === 'transfer' && !transfer.reason.trim()) ||
                    (mode === 'child' &&
                      (!child.subject.trim() || !child.body.trim()))))
              }
            >
              {submitting
                ? '처리 중…'
                : attempt
                  ? '같은 요청 다시 확인'
                  : mode === 'transfer'
                    ? '이관 실행'
                    : '내부 협업 요청 만들기'}
            </SeedButton>
          </form>
        )}
      </SeedDrawer>
    </SeedContextCard>
  )
}
