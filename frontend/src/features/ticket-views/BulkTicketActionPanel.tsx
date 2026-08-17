import { useMutation } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { executeAgentTicketBatch } from '../../api/client'
import type {
  AgentTicketBatchCommand,
  AgentTicketBatchItemResult,
  AgentTicketBatchResult,
  AgentTicketStatus,
  AgentTicketSummary,
  TicketAssignmentOptions,
  TicketPriority,
} from '../../api/types'
import { createOpaqueUuid } from '../../api/uuid'
import { DsButton, DsSelect, Notification } from '../../design-system'

type BulkOperation = 'STATUS' | 'PRIORITY' | 'ASSIGNEE' | 'TRANSFER'

export type BulkTicketActionPanelProps = {
  execute?: (
    command: AgentTicketBatchCommand,
  ) => Promise<AgentTicketBatchResult>
  onComplete?: (result: AgentTicketBatchResult) => void
  options: TicketAssignmentOptions
  tickets: AgentTicketSummary[]
}

const OUTCOME_LABELS: Record<AgentTicketBatchItemResult['outcome'], string> = {
  SUCCEEDED: '성공',
  CONFLICT: '충돌',
  DENIED: '권한 없음',
  NOT_FOUND: '찾을 수 없음',
  VALIDATION_FAILED: '검증 실패',
}

export function BulkTicketActionPanel({
  execute = executeAgentTicketBatch,
  onComplete,
  options,
  tickets,
}: BulkTicketActionPanelProps) {
  const [open, setOpen] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [operation, setOperation] = useState<BulkOperation>('STATUS')
  const [value, setValue] = useState('OPEN')
  const [assigneeId, setAssigneeId] = useState('')
  const [reason, setReason] = useState('')
  const [results, setResults] = useState<AgentTicketBatchItemResult[]>([])
  const [activeAttempt, setActiveAttempt] =
    useState<AgentTicketBatchCommand | null>(null)
  const [resultAttempt, setResultAttempt] =
    useState<AgentTicketBatchCommand | null>(null)
  const resultRef = useRef<HTMLDivElement>(null)
  const selectedGroup = options.groups.find((group) => group.id === value)

  const mutation = useMutation({
    mutationFn: execute,
    onSuccess: (result) => {
      setResults(result.results)
      setActiveAttempt(null)
      setConfirming(false)
      onComplete?.(result)
    },
  })

  useEffect(() => {
    if (results.length) resultRef.current?.focus()
  }, [results])

  const failedTicketNumbers = new Set(
    results
      .filter((result) => result.outcome !== 'SUCCEEDED')
      .map((result) => result.ticketNumber),
  )
  const validationError = validate(operation, value, reason, tickets.length)

  useEffect(() => {
    setActiveAttempt(null)
  }, [assigneeId, operation, reason, tickets, value])

  const executeAttempt = (attempt: AgentTicketBatchCommand) => {
    setActiveAttempt(attempt)
    setResultAttempt(attempt)
    mutation.mutate(attempt)
  }

  if (!tickets.length) return null

  return (
    <section aria-label="선택된 티켓" className="bulk-ticket-panel">
      <div className="bulk-ticket-panel__summary">
        <div>
          <strong>{tickets.length}개 선택됨</strong>
          <p>
            현재 페이지에서 선택한 티켓만 처리합니다. 전체 검색 결과 선택은
            지원하지 않습니다.
          </p>
        </div>
        <DsButton
          aria-expanded={open}
          disabled={tickets.length > 100}
          onClick={() => setOpen((current) => !current)}
        >
          일괄 작업
        </DsButton>
      </div>

      {tickets.length > 100 ? (
        <Notification
          title="최대 100개까지만 처리할 수 있습니다."
          tone="danger"
        />
      ) : null}

      {open ? (
        <div className="bulk-ticket-panel__form">
          <label>
            <span>작업</span>
            <DsSelect
              aria-label="일괄 작업 종류"
              disabled={mutation.isPending}
              onChange={(event) => {
                const next = event.target.value as BulkOperation
                setOperation(next)
                setValue(
                  next === 'STATUS'
                    ? 'OPEN'
                    : next === 'PRIORITY'
                      ? 'NORMAL'
                      : '',
                )
                setAssigneeId('')
                setConfirming(false)
              }}
              value={operation}
            >
              <option value="STATUS">상태 변경</option>
              <option value="PRIORITY">우선순위 변경</option>
              <option value="ASSIGNEE">담당자 변경</option>
              <option value="TRANSFER">그룹 이관</option>
            </DsSelect>
          </label>
          <BulkValueField
            assigneeId={assigneeId}
            disabled={mutation.isPending}
            onAssigneeChange={setAssigneeId}
            onValueChange={setValue}
            operation={operation}
            options={options}
            selectedGroup={selectedGroup}
            value={value}
          />
          {operation === 'TRANSFER' ? (
            <label className="bulk-ticket-panel__reason">
              <span>이관 사유</span>
              <textarea
                aria-label="이관 사유"
                disabled={mutation.isPending}
                maxLength={1000}
                onChange={(event) => setReason(event.target.value)}
                required
                value={reason}
              />
            </label>
          ) : null}

          {confirming ? (
            <Notification
              title={`${tickets.length}개 티켓에 적용할까요?`}
              tone="warning"
            >
              item별 독립 트랜잭션으로 실행되며 일부만 성공할 수 있습니다.
            </Notification>
          ) : null}
          {validationError ? <p role="alert">{validationError}</p> : null}
          {mutation.isError ? (
            <Notification
              title="일괄 작업 요청을 완료하지 못했습니다."
              tone="danger"
            >
              선택과 입력은 유지됩니다. 네트워크 상태를 확인한 뒤 다시
              실행하세요.
            </Notification>
          ) : null}
          <div className="bulk-ticket-panel__actions">
            <DsButton
              disabled={Boolean(validationError) || mutation.isPending}
              onClick={() => {
                if (!confirming) {
                  setConfirming(true)
                  return
                }
                executeAttempt(
                  activeAttempt ??
                    buildBatchCommand(
                      tickets,
                      operation,
                      value,
                      assigneeId,
                      reason,
                    ),
                )
              }}
              tone="primary"
            >
              {mutation.isPending
                ? '처리 중…'
                : confirming
                  ? '확인하고 실행'
                  : '실행 전 확인'}
            </DsButton>
          </div>
        </div>
      ) : null}

      {results.length ? (
        <div
          aria-label="일괄 작업 item별 결과"
          className="bulk-ticket-panel__results"
          ref={resultRef}
          tabIndex={-1}
        >
          <h2>일괄 작업 결과</h2>
          <ul>
            {results.map((result) => (
              <li key={`${result.ticketNumber}:${result.clientCommandId}`}>
                <strong>#{result.ticketNumber}</strong>
                <span>{OUTCOME_LABELS[result.outcome]}</span>
                {result.code ? <small>{result.code}</small> : null}
              </li>
            ))}
          </ul>
          {failedTicketNumbers.size ? (
            <DsButton
              disabled={mutation.isPending}
              onClick={() =>
                resultAttempt &&
                executeAttempt({
                  items: resultAttempt.items.filter((item) =>
                    failedTicketNumbers.has(item.ticketNumber),
                  ),
                })
              }
            >
              실패한 {failedTicketNumbers.size}개 다시 시도
            </DsButton>
          ) : null}
        </div>
      ) : null}
    </section>
  )
}

function BulkValueField({
  assigneeId,
  disabled,
  onAssigneeChange,
  onValueChange,
  operation,
  options,
  selectedGroup,
  value,
}: {
  assigneeId: string
  disabled: boolean
  onAssigneeChange: (value: string) => void
  onValueChange: (value: string) => void
  operation: BulkOperation
  options: TicketAssignmentOptions
  selectedGroup?: TicketAssignmentOptions['groups'][number]
  value: string
}) {
  if (operation === 'STATUS') {
    return (
      <SelectField
        label="변경할 상태"
        disabled={disabled}
        onChange={onValueChange}
        value={value}
      >
        {(
          [
            'NEW',
            'OPEN',
            'PENDING',
            'ON_HOLD',
            'SOLVED',
          ] satisfies AgentTicketStatus[]
        ).map((status) => (
          <option key={status} value={status}>
            {status}
          </option>
        ))}
      </SelectField>
    )
  }
  if (operation === 'PRIORITY') {
    return (
      <SelectField
        label="변경할 우선순위"
        disabled={disabled}
        onChange={onValueChange}
        value={value}
      >
        {(['LOW', 'NORMAL', 'HIGH', 'URGENT'] satisfies TicketPriority[]).map(
          (priority) => (
            <option key={priority} value={priority}>
              {priority}
            </option>
          ),
        )}
      </SelectField>
    )
  }
  if (operation === 'ASSIGNEE') {
    const members = options.groups.flatMap((group) => group.members)
    const uniqueMembers = [
      ...new Map(members.map((member) => [member.id, member])).values(),
    ]
    return (
      <SelectField
        label="변경할 담당자"
        disabled={disabled}
        onChange={onValueChange}
        value={value}
      >
        <option value="">미배정</option>
        {uniqueMembers.map((member) => (
          <option key={member.id} value={member.id}>
            {member.displayName}
          </option>
        ))}
      </SelectField>
    )
  }
  return (
    <>
      <SelectField
        label="이관할 그룹"
        disabled={disabled}
        onChange={(next) => {
          onValueChange(next)
          onAssigneeChange('')
        }}
        value={value}
      >
        <option value="">그룹 선택</option>
        {options.groups.map((group) => (
          <option key={group.id} value={group.id}>
            {group.name}
          </option>
        ))}
      </SelectField>
      <SelectField
        label="대상 그룹 담당자"
        disabled={disabled || !selectedGroup}
        onChange={onAssigneeChange}
        value={assigneeId}
      >
        <option value="">미배정</option>
        {selectedGroup?.members.map((member) => (
          <option key={member.id} value={member.id}>
            {member.displayName}
          </option>
        ))}
      </SelectField>
    </>
  )
}

function SelectField({
  children,
  disabled,
  label,
  onChange,
  value,
}: {
  children: React.ReactNode
  disabled: boolean
  label: string
  onChange: (value: string) => void
  value: string
}) {
  return (
    <label>
      <span>{label}</span>
      <DsSelect
        aria-label={label}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        value={value}
      >
        {children}
      </DsSelect>
    </label>
  )
}

function validate(
  operation: BulkOperation,
  value: string,
  reason: string,
  count: number,
) {
  if (count < 1 || count > 100)
    return '1개 이상 100개 이하의 티켓을 선택하세요.'
  if (operation === 'TRANSFER' && !value) return '이관할 그룹을 선택하세요.'
  if (operation === 'TRANSFER' && !reason.trim())
    return '그룹 이관 사유를 입력하세요.'
  return ''
}

function buildBatchCommand(
  tickets: AgentTicketSummary[],
  operation: BulkOperation,
  value: string,
  assigneeId: string,
  reason: string,
): AgentTicketBatchCommand {
  return {
    items: tickets.map((ticket) => {
      const command =
        operation === 'STATUS'
          ? {
              type: 'UPDATE' as const,
              changedFields: ['status' as const],
              status: value as AgentTicketStatus,
            }
          : operation === 'PRIORITY'
            ? {
                type: 'UPDATE' as const,
                changedFields: ['priority' as const],
                priority: value as TicketPriority,
              }
            : operation === 'ASSIGNEE'
              ? {
                  type: 'UPDATE' as const,
                  changedFields: ['assigneeId' as const],
                  assigneeId: value || null,
                }
              : {
                  type: 'TRANSFER' as const,
                  groupId: value,
                  assigneeId: assigneeId || null,
                  reason: reason.trim(),
                }
      return {
        ticketNumber: ticket.ticketNumber,
        expectedVersion: ticket.version,
        clientCommandId: createOpaqueUuid(),
        command,
      }
    }),
  }
}
