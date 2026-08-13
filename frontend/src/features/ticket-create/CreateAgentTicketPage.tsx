import { useQuery } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { listTicketAssignmentOptions } from '../../api/client'
import type { TicketPriority, TicketVisibility } from '../../api/types'
import {
  DsButton,
  DsPropertyField,
  DsSelect,
  DsTabs,
  Notification,
  ScreenState,
} from '../../design-system'
import { useCreateAgentTicket } from './model/useCreateAgentTicket'
import { useRequesterSearch } from './model/useRequesterSearch'
import { RequesterSearchField } from './RequesterSearchField'

const PRIORITY_OPTIONS: { value: TicketPriority; label: string }[] = [
  { value: 'LOW', label: '낮음' },
  { value: 'NORMAL', label: '보통' },
  { value: 'HIGH', label: '높음' },
  { value: 'URGENT', label: '긴급' },
]

export function CreateAgentTicketPage() {
  const assignmentOptionsQuery = useQuery({
    queryKey: ['ticket-assignment-options'],
    queryFn: listTicketAssignmentOptions,
  })
  const { submit, submitting, error, warnings } = useCreateAgentTicket()
  const requesterSearch = useRequesterSearch()

  const [subject, setSubject] = useState('')
  const [body, setBody] = useState('')
  const [visibility, setVisibility] = useState<TicketVisibility>('INTERNAL')
  const [priority, setPriority] = useState<TicketPriority>('NORMAL')
  const [groupId, setGroupId] = useState<string>('')
  const [assigneeId, setAssigneeId] = useState<string>('')
  const [validationError, setValidationError] = useState<string | null>(null)

  const subjectId = useId()
  const bodyId = useId()
  const errorRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (error || validationError) errorRef.current?.focus()
  }, [error, validationError])

  const selectedGroup = assignmentOptionsQuery.data?.groups.find(
    (group) => group.id === groupId,
  )

  if (assignmentOptionsQuery.isPending) {
    return (
      <main className="create-ticket-page">
        <ScreenState kind="loading" title="새 티켓 양식을 준비하고 있습니다." />
      </main>
    )
  }

  if (assignmentOptionsQuery.isError) {
    return (
      <main className="create-ticket-page">
        <ScreenState
          action={
            <DsButton onClick={() => assignmentOptionsQuery.refetch()}>
              다시 시도
            </DsButton>
          }
          description="잠시 후 다시 시도해 주세요."
          kind="error"
          title="배정 가능한 그룹 정보를 불러오지 못했습니다."
        />
      </main>
    )
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    setValidationError(null)
    const requester = requesterSearch.selection
    if (!requester) {
      setValidationError('요청자를 검색해서 선택하거나 새로 등록해 주세요.')
      return
    }
    const trimmedSubject = subject.trim()
    if (!trimmedSubject) {
      setValidationError('제목을 입력해 주세요.')
      return
    }
    const trimmedBody = body.trim()
    if (!trimmedBody) {
      setValidationError('첫 코멘트 내용을 입력해 주세요.')
      return
    }
    submit({
      requester:
        requester.mode === 'existing'
          ? { customerId: requester.customer.id }
          : { name: requester.name, email: requester.email },
      subject: trimmedSubject,
      visibility,
      body: trimmedBody,
      priority,
      groupId: groupId || null,
      assigneeId: groupId ? assigneeId || null : null,
    })
  }

  return (
    <main className="create-ticket-page">
      <header className="create-ticket-header">
        <h1>새 티켓 생성</h1>
        <p>고객 문의 없이 상담사가 직접 티켓을 시작합니다.</p>
      </header>

      {error ? (
        <Notification
          ref={errorRef}
          tabIndex={-1}
          title={
            error.requestId
              ? `${error.message} (요청 ID: ${error.requestId})`
              : error.message
          }
          tone="danger"
        />
      ) : null}
      {validationError ? (
        <Notification
          ref={errorRef}
          tabIndex={-1}
          title={validationError}
          tone="warning"
        />
      ) : null}
      {warnings.length > 0
        ? warnings.map((warning) => (
            <Notification
              key={warning.code}
              title={warning.message}
              tone="warning"
            />
          ))
        : null}

      <form className="create-ticket-form" onSubmit={handleSubmit}>
        <section aria-labelledby="create-ticket-requester-heading">
          <h2 id="create-ticket-requester-heading">요청자</h2>
          <RequesterSearchField
            newEmail={requesterSearch.newEmail}
            newName={requesterSearch.newName}
            onNewEmailChange={requesterSearch.setNewEmail}
            onNewNameChange={requesterSearch.setNewName}
            onQueryChange={requesterSearch.setQuery}
            onSelectCustomer={requesterSearch.setSelectedCustomer}
            onTabChange={requesterSearch.setTab}
            query={requesterSearch.query}
            results={requesterSearch.results}
            searchError={requesterSearch.searchError}
            searching={requesterSearch.searching}
            selectedCustomer={requesterSearch.selectedCustomer}
            tab={requesterSearch.tab}
          />
        </section>

        <label className="create-ticket-field" htmlFor={subjectId}>
          <span>제목</span>
          <input
            id={subjectId}
            maxLength={200}
            onChange={(event) => setSubject(event.target.value)}
            value={subject}
          />
        </label>

        <div className="create-ticket-field">
          <span id="create-ticket-visibility-label">첫 코멘트 공개 범위</span>
          <DsTabs
            activeId={visibility}
            ariaLabel="첫 코멘트 공개 범위"
            items={[
              { id: 'INTERNAL', label: '내부 메모' },
              { id: 'PUBLIC', label: '공개 답변' },
            ]}
            onChange={setVisibility}
          />
        </div>

        <label className="create-ticket-field" htmlFor={bodyId}>
          <span>첫 코멘트 내용</span>
          <textarea
            id={bodyId}
            maxLength={20000}
            onChange={(event) => setBody(event.target.value)}
            rows={6}
            value={body}
          />
        </label>

        <DsPropertyField label="우선순위">
          <DsSelect
            aria-label="우선순위"
            onChange={(event) =>
              setPriority(event.target.value as TicketPriority)
            }
            value={priority}
          >
            {PRIORITY_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </DsSelect>
        </DsPropertyField>

        <DsPropertyField label="그룹">
          <DsSelect
            aria-label="그룹"
            onChange={(event) => {
              setGroupId(event.target.value)
              setAssigneeId('')
            }}
            value={groupId}
          >
            <option value="">미배정</option>
            {assignmentOptionsQuery.data.groups.map((group) => (
              <option key={group.id} value={group.id}>
                {group.name}
              </option>
            ))}
          </DsSelect>
        </DsPropertyField>

        <DsPropertyField label="담당자">
          <DsSelect
            aria-label="담당자"
            disabled={!selectedGroup}
            onChange={(event) => setAssigneeId(event.target.value)}
            value={assigneeId}
          >
            <option value="">미배정</option>
            {(selectedGroup?.members ?? []).map((member) => (
              <option key={member.id} value={member.id}>
                {member.displayName}
              </option>
            ))}
          </DsSelect>
        </DsPropertyField>

        <footer className="create-ticket-actions">
          <Link to="/agent/views/my-open">취소</Link>
          <DsButton disabled={submitting} tone="primary" type="submit">
            {submitting ? '생성 중…' : '티켓 생성'}
          </DsButton>
        </footer>
      </form>
    </main>
  )
}
