import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import type {
  TicketAssignmentGroupOption,
  TicketCommandWarning,
  TicketPriority,
  TicketVisibility,
} from '../../api/types'
import {
  SeedButton,
  SeedFeedbackState,
  SeedNotice,
  SeedSelectField,
  SeedTabs,
  SeedTextAreaField,
  SeedTextField,
} from '../../design-system/canonical'
import type { CreateAgentTicketInput } from './model/useCreateAgentTicket'
import type { useRequesterSearch } from './model/useRequesterSearch'
import { RequesterSearchField } from './RequesterSearchField'

const PRIORITY_OPTIONS: { value: TicketPriority; label: string }[] = [
  { value: 'LOW', label: '낮음' },
  { value: 'NORMAL', label: '보통' },
  { value: 'HIGH', label: '높음' },
  { value: 'URGENT', label: '긴급' },
]

export type AssignmentOptionsState =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'ready'; groups: TicketAssignmentGroupOption[] }

export interface CreateAgentTicketFormError {
  message: string
  requestId?: string
}

export interface CreateAgentTicketFormProps {
  assignmentOptions: AssignmentOptionsState
  onRetryOptions: () => void
  requesterSearch: ReturnType<typeof useRequesterSearch>
  submitting: boolean
  error: CreateAgentTicketFormError | null
  warnings: TicketCommandWarning[]
  onSubmit: (input: CreateAgentTicketInput) => void
}

export function CreateAgentTicketForm({
  assignmentOptions,
  onRetryOptions,
  requesterSearch,
  submitting,
  error,
  warnings,
  onSubmit,
}: CreateAgentTicketFormProps) {
  const [subject, setSubject] = useState('')
  const [body, setBody] = useState('')
  const [visibility, setVisibility] = useState<TicketVisibility>('INTERNAL')
  const [priority, setPriority] = useState<TicketPriority>('NORMAL')
  const [groupId, setGroupId] = useState('')
  const [assigneeId, setAssigneeId] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)
  const errorRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (error || validationError) errorRef.current?.focus()
  }, [error, validationError])

  if (assignmentOptions.status === 'loading') {
    return (
      <SeedFeedbackState
        kind="loading"
        title="새 티켓 양식을 준비하고 있습니다."
      />
    )
  }
  if (assignmentOptions.status === 'error') {
    return (
      <SeedFeedbackState
        action={<SeedButton onClick={onRetryOptions}>다시 시도</SeedButton>}
        description="잠시 후 다시 시도해 주세요."
        kind="error"
        title="배정 가능한 그룹 정보를 불러오지 못했습니다."
      />
    )
  }

  const selectedGroup = assignmentOptions.groups.find(
    (group) => group.id === groupId,
  )
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
    onSubmit({
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
    <section className="seed-create" aria-labelledby="create-ticket-title">
      <header className="seed-create__header">
        <h1 id="create-ticket-title">새 티켓</h1>
      </header>
      <form
        className="seed-create__workspace"
        noValidate
        onSubmit={handleSubmit}
      >
        <aside aria-label="새 티켓 속성" className="seed-create__properties">
          <section
            className="seed-create__property-section"
            aria-labelledby="create-ticket-requester-heading"
          >
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
          <section
            aria-labelledby="create-ticket-properties-heading"
            className="seed-create__property-section"
          >
            <h2 id="create-ticket-properties-heading">티켓 속성</h2>
            <div className="seed-create__property-fields">
              <SeedSelectField
                aria-label="우선순위"
                label="우선순위"
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
              </SeedSelectField>
              <SeedSelectField
                aria-label="그룹"
                label="그룹"
                onChange={(event) => {
                  setGroupId(event.target.value)
                  setAssigneeId('')
                }}
                value={groupId}
              >
                <option value="">미배정</option>
                {assignmentOptions.groups.map((group) => (
                  <option key={group.id} value={group.id}>
                    {group.name}
                  </option>
                ))}
              </SeedSelectField>
              <SeedSelectField
                aria-label="담당자"
                disabled={!selectedGroup}
                label="담당자"
                onChange={(event) => setAssigneeId(event.target.value)}
                value={assigneeId}
              >
                <option value="">미배정</option>
                {(selectedGroup?.members ?? []).map((member) => (
                  <option key={member.id} value={member.id}>
                    {member.displayName}
                  </option>
                ))}
              </SeedSelectField>
            </div>
          </section>
        </aside>
        <section aria-label="티켓 내용" className="seed-create__editor">
          <div className="seed-create__editor-body">
            {(error || validationError) && (
              <div ref={errorRef} tabIndex={-1}>
                <SeedNotice
                  title={error ? '티켓 생성 실패' : '입력을 확인해 주세요'}
                  tone={error ? 'danger' : 'warning'}
                >
                  {error
                    ? `${error.message}${error.requestId ? ` (요청 ID: ${error.requestId})` : ''}`
                    : validationError}
                </SeedNotice>
              </div>
            )}
            {warnings.map((warning) => (
              <SeedNotice
                key={warning.code}
                title="생성 전 확인"
                tone="warning"
              >
                {warning.message}
              </SeedNotice>
            ))}
            <SeedTextField
              aria-label="제목"
              label="제목"
              maxLength={200}
              onChange={(event) => setSubject(event.target.value)}
              required
              value={subject}
            />
            <div className="seed-create__comment-composer">
              <div className="seed-field">
                <span className="seed-field__label">첫 코멘트 공개 범위</span>
                <SeedTabs
                  active={visibility}
                  ariaLabel="첫 코멘트 공개 범위"
                  items={[
                    { id: 'INTERNAL', label: '내부 메모' },
                    { id: 'PUBLIC', label: '공개 답변' },
                  ]}
                  onChange={setVisibility}
                />
              </div>
              <SeedTextAreaField
                aria-label="첫 코멘트 내용"
                label="첫 코멘트 내용"
                maxLength={20000}
                onChange={(event) => setBody(event.target.value)}
                required
                rows={14}
                value={body}
              />
            </div>
          </div>
          <footer className="seed-create__actions">
            <Link to="/agent/views/my-open">취소</Link>
            <SeedButton disabled={submitting} type="submit" variant="primary">
              {submitting ? '생성 중…' : '티켓 생성'}
            </SeedButton>
          </footer>
        </section>
      </form>
    </section>
  )
}
