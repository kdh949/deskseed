import { useRef, useState, type RefObject } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, listTicketAssignmentOptions } from '../../api/client'
import {
  SeedButton,
  SeedDrawer,
  SeedFeedbackState,
  SeedNotice,
  SeedSelectField,
  SeedSkeletonRows,
  SeedTextField,
} from '../../design-system/canonical'
import { getViewConfigurationCatalog } from '../../features/ticket-views/viewConfigurationCatalog'
import {
  ACTIONS,
  EVENTS,
  FIELDS,
  PRIORITIES,
  activateTrigger,
  deactivateTrigger,
  listTriggers,
  previewTrigger,
  repositionTrigger,
  saveTrigger,
  triggerHistory,
  triggerVersion,
  type Action,
  type Condition,
  type RuleEvent,
  type Trigger,
  type TriggerDraft,
  type TriggerPreview,
} from './api'
import './rules.css'

type Choice = { id: string; label: string }
const draftOf = (rule: Trigger): TriggerDraft => ({
  name: rule.name,
  conditions: rule.conditions,
  actions: rule.actions,
})
const initialDraft = (): TriggerDraft => ({
  name: '',
  conditions: [
    { group: 'ALL', field: 'EVENT', operator: 'IS', value: 'TICKET_CREATED' },
  ],
  actions: [{ type: 'SET_PRIORITY', priority: 'HIGH' }],
})
const operatorLabels = {
  IS: '같음',
  IS_NOT: '다름',
  PRESENT: '있음',
  NOT_PRESENT: '없음',
}
const failureLabels: Record<string, string> = {
  TICKET_CLOSED: '종료된 티켓은 변경할 수 없습니다.',
  TARGET_GROUP_INACTIVE: '대상 그룹이 비활성 상태입니다.',
  TARGET_ASSIGNEE_NOT_ACTIVE_MEMBER:
    '담당자가 최종 그룹의 활성 구성원이 아닙니다.',
  UNASSIGNED_GROUP_REQUIRED:
    '미배정 알림에는 담당자가 없고 담당 그룹이 있어야 합니다.',
}

function RuleError({ error }: { error: unknown }) {
  if (!error) return null
  const status = error instanceof ApiError ? error.status : 0
  return (
    <SeedNotice
      tone={status === 409 || status === 412 ? 'warning' : 'danger'}
      title={
        status === 403
          ? '규칙을 관리할 권한이 없습니다.'
          : status === 409 || status === 412
            ? '다른 변경과 충돌했습니다.'
            : '요청 결과를 확인하지 못했습니다.'
      }
    >
      <p>입력은 유지됩니다. 최신 상태를 확인한 후 다시 시도하세요.</p>
    </SeedNotice>
  )
}

export function AdminTriggersPage() {
  const client = useQueryClient()
  const rules = useQuery({
    queryKey: ['admin-triggers'],
    queryFn: listTriggers,
    retry: false,
  })
  const [editing, setEditing] = useState<Trigger | 'new' | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [moving, setMoving] = useState(false)
  const returnFocusRef = useRef<HTMLElement | null>(null)
  const refresh = () =>
    client.invalidateQueries({ queryKey: ['admin-triggers'] })
  const move = async (rule: Trigger, position: number) => {
    setMoving(true)
    setError(null)
    try {
      await repositionTrigger(rule, position)
      await refresh()
    } catch (cause) {
      setError(cause)
    } finally {
      setMoving(false)
    }
  }
  return (
    <section className="rule-page">
      <header>
        <h1>트리거</h1>
        <p>
          문의가 들어오거나 변경될 때 조건에 따라 담당 팀과 우선순위를 정하고
          알림을 보냅니다.
        </p>
      </header>
      <div className="rule-actions">
        <SeedButton
          onClick={(event) => {
            returnFocusRef.current = event.currentTarget
            setEditing('new')
          }}
        >
          트리거 만들기
        </SeedButton>
        <SeedButton onClick={() => void rules.refetch()}>
          목록 새로고침
        </SeedButton>
      </div>
      <RuleError error={error || rules.error} />
      {rules.isPending ? (
        <SeedSkeletonRows />
      ) : rules.data?.length === 0 ? (
        <SeedFeedbackState
          kind="empty"
          title="등록된 트리거가 없습니다."
          description="반복되는 분류나 배정 작업을 규칙으로 추가하세요."
        />
      ) : (
        <ol className="rule-list">
          {rules.data?.map((rule, index, items) => (
            <li key={rule.id}>
              <div>
                <strong>{rule.name}</strong>
                <p>
                  순서 {rule.position} · 최신 버전 {rule.currentVersion} ·{' '}
                  {rule.activeVersion
                    ? `활성 버전 ${rule.activeVersion}`
                    : '비활성'}
                </p>
              </div>
              <div className="rule-actions">
                <SeedButton
                  onClick={(event) => {
                    returnFocusRef.current = event.currentTarget
                    setEditing(rule)
                  }}
                >
                  관리: {rule.name}
                </SeedButton>
                <SeedButton
                  disabled={moving || index === 0}
                  onClick={() => void move(rule, items[index - 1]!.position)}
                >
                  앞으로: {rule.name}
                </SeedButton>
              </div>
            </li>
          ))}
        </ol>
      )}
      {editing && (
        <TriggerEditor
          key={editing === 'new' ? 'new' : editing.id}
          initial={editing === 'new' ? null : editing}
          initialPosition={Math.min(
            10000,
            Math.max(0, ...(rules.data ?? []).map((rule) => rule.position)) +
              10,
          )}
          onClose={() => setEditing(null)}
          onRefresh={refresh}
          returnFocusRef={returnFocusRef}
        />
      )}
    </section>
  )
}

function TriggerEditor({
  initial,
  initialPosition,
  onClose,
  onRefresh,
  returnFocusRef,
}: {
  initial: Trigger | null
  initialPosition: number
  onClose: () => void
  onRefresh: () => Promise<unknown>
  returnFocusRef: RefObject<HTMLElement>
}) {
  const [current, setCurrent] = useState(initial)
  const [draft, setDraft] = useState<TriggerDraft>(() =>
    initial ? draftOf(initial) : initialDraft(),
  )
  const [position, setPosition] = useState(String(initialPosition))
  const [selectedVersion, setSelectedVersion] = useState(
    initial?.currentVersion ?? 1,
  )
  const [sample, setSample] = useState('')
  const [eventType, setEventType] = useState<RuleEvent>('TICKET_CREATED')
  const [preview, setPreview] = useState<TriggerPreview | null>(null)
  const [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [message, setMessage] = useState('')
  const feedbackRef = useRef<HTMLDivElement>(null)
  const assignments = useQuery({
    queryKey: ['agent-assignment-options'],
    queryFn: listTicketAssignmentOptions,
    retry: false,
  })
  const catalog = useQuery({
    queryKey: ['view-configuration-catalog'],
    queryFn: getViewConfigurationCatalog,
    retry: false,
  })
  const history = useQuery({
    queryKey: ['trigger-history', current?.id],
    queryFn: () => triggerHistory(current!.id),
    enabled: current !== null,
    retry: false,
  })
  const dirty =
    !current || JSON.stringify(draft) !== JSON.stringify(draftOf(current))
  const change = (next: TriggerDraft) => {
    setDraft(next)
    setPreview(null)
    setMessage('')
  }
  const groups =
    assignments.data?.groups.map((group) => ({
      id: group.id,
      label: group.name,
    })) ?? []
  const staff = Array.from(
    new Map(
      assignments.data?.groups.flatMap((group) =>
        group.members.map(
          (person) =>
            [person.id, { id: person.id, label: person.displayName }] as const,
        ),
      ),
    ).values(),
  )
  const valuesFor = (field: Condition['field']): Choice[] =>
    field === 'EVENT'
      ? choices(EVENTS)
      : field === 'PRIORITY'
        ? choices(PRIORITIES)
        : field === 'GROUP'
          ? groups
          : field === 'ASSIGNEE'
            ? staff
            : field === 'TAG'
              ? (catalog.data?.tags ?? [])
              : (catalog.data?.forms ?? [])
  const invalid =
    !draft.name.trim() ||
    !draft.conditions.length ||
    !draft.conditions.some((condition) => condition.field === 'EVENT') ||
    draft.conditions.some(
      (condition) =>
        ['IS', 'IS_NOT'].includes(condition.operator) && !condition.value,
    ) ||
    !draft.actions.length ||
    new Set(draft.actions.map((action) => action.type)).size !==
      draft.actions.length ||
    draft.actions.some(
      (action) => action.type === 'SET_GROUP' && !action.groupId,
    ) ||
    (!current &&
      (!Number.isInteger(Number(position)) ||
        Number(position) < 1 ||
        Number(position) > 10000))
  const run = async (action: () => Promise<void>, writing = false) => {
    setBusy(true)
    setError(null)
    setMessage('')
    try {
      await action()
    } catch (cause) {
      setError(cause)
      if (writing) setUncertain(true)
    } finally {
      setBusy(false)
      requestAnimationFrame(() => feedbackRef.current?.focus())
    }
  }
  const accept = (rule: Trigger, version: number) => {
    setCurrent(rule)
    setDraft(draftOf(rule))
    setSelectedVersion(version)
    setPreview(null)
    setUncertain(false)
  }
  const updateCondition = (index: number, condition: Condition) =>
    change({
      ...draft,
      conditions: draft.conditions.map((item, n) =>
        n === index ? condition : item,
      ),
    })
  const updateAction = (index: number, action: Action) =>
    change({
      ...draft,
      actions: draft.actions.map((item, n) => (n === index ? action : item)),
    })
  return (
    <SeedDrawer
      open
      title={current ? `${current.name} 관리` : '트리거 만들기'}
      description="저장한 버전으로 미리보기한 후 활성화하세요. 활성 버전만 새 문의 이벤트에 적용됩니다."
      onClose={onClose}
      returnFocusRef={returnFocusRef}
    >
      <div className="rule-editor">
        <div ref={feedbackRef} tabIndex={-1}>
          <RuleError error={error} />
          {message && <p role="status">{message}</p>}
        </div>
        {uncertain && (
          <SeedNotice title="최신 상태 확인이 필요합니다" tone="warning">
            <p>
              동일 요청을 바로 반복하지 않습니다. 입력을 유지하면서 목록과 버전
              상태를 확인하세요.
            </p>
            <SeedButton
              disabled={busy}
              onClick={() =>
                void run(async () => {
                  await onRefresh()
                  if (current)
                    setCurrent(
                      await triggerVersion(current.id, selectedVersion),
                    )
                  setUncertain(false)
                  setMessage(
                    '최신 상태를 확인했습니다. 현재 입력과 버전을 비교하세요.',
                  )
                  void history.refetch()
                })
              }
            >
              최신 상태 확인 (입력 유지)
            </SeedButton>
          </SeedNotice>
        )}
        {(assignments.isError || catalog.isError) && (
          <SeedNotice title="일부 선택지를 불러오지 못했습니다" tone="warning">
            <SeedButton
              onClick={() => {
                void assignments.refetch()
                void catalog.refetch()
              }}
            >
              선택지 다시 불러오기
            </SeedButton>
          </SeedNotice>
        )}
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (invalid || uncertain) return
            void run(async () => {
              const saved = await saveTrigger(
                current,
                { ...draft, name: draft.name.trim() },
                Number(position),
              )
              accept(saved, saved.currentVersion)
              await onRefresh()
              if (current) void history.refetch()
              setMessage(`버전 ${saved.currentVersion}을 저장했습니다.`)
            }, true)
          }}
        >
          <fieldset disabled={busy} className="rule-form">
            <legend>규칙 편집</legend>
            <SeedTextField
              autoFocus
              label="트리거 이름"
              value={draft.name}
              required
              maxLength={120}
              onChange={(event) =>
                change({ ...draft, name: event.target.value })
              }
            />
            {!current && (
              <SeedTextField
                label="평가 순서"
                type="number"
                min={1}
                max={10000}
                value={position}
                onChange={(event) => setPosition(event.target.value)}
                hint="작은 값부터 평가합니다. 같은 순서는 사용할 수 없습니다."
              />
            )}
            <h3>조건</h3>
            <p>
              모든 조건이 맞고, ‘하나 이상’ 조건이 있으면 그중 하나 이상도
              맞아야 합니다.
            </p>
            {draft.conditions.map((condition, index) => (
              <fieldset key={index} className="rule-row">
                <legend>조건 {index + 1}</legend>
                <RuleSelect
                  label={`조건 ${index + 1} 묶음`}
                  value={condition.group}
                  options={[
                    { id: 'ALL', label: '모두' },
                    { id: 'ANY', label: '하나 이상' },
                  ]}
                  onChange={(group) =>
                    updateCondition(index, {
                      ...condition,
                      group: group as Condition['group'],
                    })
                  }
                />
                <RuleSelect
                  label={`조건 ${index + 1} 항목`}
                  value={condition.field}
                  options={choices(FIELDS)}
                  onChange={(field) =>
                    updateCondition(index, {
                      ...condition,
                      field: field as Condition['field'],
                      operator: 'IS',
                      value: '',
                    })
                  }
                />
                <RuleSelect
                  label={`조건 ${index + 1} 비교`}
                  value={condition.operator}
                  options={choices(operatorLabels).filter(
                    (choice) =>
                      ['GROUP', 'ASSIGNEE'].includes(condition.field) ||
                      ['IS', 'IS_NOT'].includes(choice.id),
                  )}
                  onChange={(operator) =>
                    updateCondition(index, {
                      ...condition,
                      operator: operator as Condition['operator'],
                      value: ['PRESENT', 'NOT_PRESENT'].includes(operator)
                        ? null
                        : (condition.value ?? ''),
                    })
                  }
                />
                {['IS', 'IS_NOT'].includes(condition.operator) && (
                  <RuleSelect
                    label={`조건 ${index + 1} 값`}
                    value={condition.value ?? ''}
                    options={valuesFor(condition.field)}
                    onChange={(value) =>
                      updateCondition(index, { ...condition, value })
                    }
                    placeholder="값 선택"
                  />
                )}
                <SeedButton
                  type="button"
                  onClick={() =>
                    change({
                      ...draft,
                      conditions: draft.conditions.filter(
                        (_, n) => n !== index,
                      ),
                    })
                  }
                >
                  조건 {index + 1} 삭제
                </SeedButton>
              </fieldset>
            ))}
            <SeedButton
              type="button"
              disabled={draft.conditions.length >= 50}
              onClick={() =>
                change({
                  ...draft,
                  conditions: [
                    ...draft.conditions,
                    {
                      group: 'ALL',
                      field: 'ASSIGNEE',
                      operator: 'NOT_PRESENT',
                    },
                  ],
                })
              }
            >
              조건 추가
            </SeedButton>
            <h3>변경과 알림</h3>
            <p>
              그룹과 담당자는 최종 조합을 함께 검증합니다. 미배정 알림은
              담당자가 없는 티켓의 그룹 구성원에게 보냅니다.
            </p>
            {draft.actions.map((action, index) => (
              <fieldset key={index} className="rule-row">
                <legend>액션 {index + 1}</legend>
                <RuleSelect
                  label={`액션 ${index + 1} 종류`}
                  value={action.type}
                  options={choices(ACTIONS)}
                  onChange={(type) =>
                    updateAction(index, defaultAction(type as Action['type']))
                  }
                />
                {action.type === 'SET_GROUP' && (
                  <RuleSelect
                    label={`액션 ${index + 1} 그룹`}
                    value={action.groupId}
                    options={groups}
                    placeholder="그룹 선택"
                    onChange={(groupId) =>
                      updateAction(index, { ...action, groupId })
                    }
                  />
                )}
                {action.type === 'SET_PRIORITY' && (
                  <RuleSelect
                    label={`액션 ${index + 1} 우선순위`}
                    value={action.priority}
                    options={choices(PRIORITIES)}
                    onChange={(priority) =>
                      updateAction(index, {
                        ...action,
                        priority: priority as keyof typeof PRIORITIES,
                      })
                    }
                  />
                )}
                {action.type === 'SET_ASSIGNEE' && (
                  <RuleSelect
                    label={`액션 ${index + 1} 담당자`}
                    value={action.assigneeId ?? ''}
                    options={staff}
                    placeholder="배정 해제"
                    onChange={(assigneeId) =>
                      updateAction(index, {
                        ...action,
                        assigneeId: assigneeId || null,
                      })
                    }
                  />
                )}
                <SeedButton
                  type="button"
                  onClick={() =>
                    change({
                      ...draft,
                      actions: draft.actions.filter((_, n) => n !== index),
                    })
                  }
                >
                  액션 {index + 1} 삭제
                </SeedButton>
              </fieldset>
            ))}
            <SeedButton
              type="button"
              disabled={draft.actions.length >= Object.keys(ACTIONS).length}
              onClick={() =>
                change({
                  ...draft,
                  actions: [
                    ...draft.actions,
                    defaultAction(
                      (Object.keys(ACTIONS) as Action['type'][]).find(
                        (type) =>
                          !draft.actions.some((action) => action.type === type),
                      ) ?? 'SET_PRIORITY',
                    ),
                  ],
                })
              }
            >
              액션 추가
            </SeedButton>
            {invalid && (
              <p role="alert">
                이름과 조건 값을 입력하고, 이벤트 조건과 중복되지 않는 액션을
                하나 이상 추가하세요.
              </p>
            )}
            <SeedButton
              type="submit"
              variant="primary"
              disabled={invalid || uncertain || !dirty}
            >
              {current ? '새 버전 저장' : '초안 저장'}
            </SeedButton>
          </fieldset>
        </form>
        {current && (
          <section aria-label="규칙 검증과 활성화">
            <h3>버전 {selectedVersion} 검증</h3>
            <p>
              {current.activeVersion
                ? `현재 활성 버전 ${current.activeVersion}`
                : '현재 비활성'}{' '}
              · 변경한 입력은 새 버전으로 저장한 후 검증하세요.
            </p>
            <SeedTextField
              label="샘플 티켓 번호"
              type="number"
              min={1}
              value={sample}
              onChange={(event) => {
                setSample(event.target.value)
                setPreview(null)
              }}
            />
            <RuleSelect
              label="평가할 이벤트"
              value={eventType}
              options={choices(EVENTS)}
              onChange={(value) => {
                setEventType(value as RuleEvent)
                setPreview(null)
              }}
            />
            <div className="rule-actions">
              <SeedButton
                disabled={
                  busy ||
                  dirty ||
                  !Number.isSafeInteger(Number(sample)) ||
                  Number(sample) < 1
                }
                onClick={() =>
                  void run(async () =>
                    setPreview(
                      await previewTrigger(
                        current.id,
                        selectedVersion,
                        Number(sample),
                        eventType,
                      ),
                    ),
                  )
                }
              >
                미리보기
              </SeedButton>
              <SeedButton
                disabled={
                  busy ||
                  uncertain ||
                  dirty ||
                  !preview ||
                  preview.invariantFailures.length > 0 ||
                  current.activeVersion === selectedVersion
                }
                onClick={() =>
                  void run(async () => {
                    accept(
                      await activateTrigger(current, selectedVersion),
                      selectedVersion,
                    )
                    await onRefresh()
                    void history.refetch()
                    setMessage(`버전 ${selectedVersion}을 활성화했습니다.`)
                  }, true)
                }
              >
                이 버전 활성화
              </SeedButton>
              <SeedButton
                disabled={busy || uncertain || current.activeVersion === null}
                onClick={() =>
                  void run(async () => {
                    const result = await deactivateTrigger(current)
                    setCurrent({
                      ...current,
                      activeVersion: result.activeVersion,
                      aggregateVersion: result.aggregateVersion,
                    })
                    await onRefresh()
                    void history.refetch()
                    setMessage(
                      '새 이벤트에 대한 실행을 중지했습니다. 이미 접수한 작업은 처리됩니다.',
                    )
                  }, true)
                }
              >
                비활성화
              </SeedButton>
            </div>
            {preview && (
              <SeedNotice
                tone={preview.invariantFailures.length ? 'warning' : 'positive'}
                title={
                  preview.matched
                    ? '샘플 티켓이 조건에 맞습니다.'
                    : '샘플 티켓이 조건에 맞지 않습니다.'
                }
              >
                <ul>
                  {draft.conditions.map((condition, index) => (
                    <li key={index}>
                      조건 {index + 1}: {FIELDS[condition.field]} ·{' '}
                      {preview.matchedConditions.includes(index)
                        ? '일치'
                        : '불일치'}
                    </li>
                  ))}
                </ul>
                <p>
                  조건이 맞으면:{' '}
                  {preview.proposedActions
                    .map((type) => ACTIONS[type])
                    .join(', ')}
                </p>
                {preview.invariantFailures.map((failure) => (
                  <p key={failure}>{failureLabels[failure] ?? failure}</p>
                ))}
                <p>이 미리보기는 티켓이나 알림을 변경하지 않습니다.</p>
              </SeedNotice>
            )}
          </section>
        )}
        {current && (
          <section aria-label="트리거 이력">
            <h3>이력</h3>
            <SeedButton
              disabled={history.isFetching}
              onClick={() => void history.refetch()}
            >
              이력 새로고침
            </SeedButton>
            <RuleError error={history.error} />
            {history.isPending ? (
              <SeedSkeletonRows />
            ) : (
              history.data && (
                <>
                  <h4>최근 버전</h4>
                  <ul>
                    {history.data.versions.map((version) => (
                      <li key={version.version}>
                        <SeedButton
                          disabled={busy || dirty}
                          onClick={() =>
                            void run(async () =>
                              accept(
                                await triggerVersion(
                                  current.id,
                                  version.version,
                                ),
                                version.version,
                              ),
                            )
                          }
                        >
                          버전 {version.version} 불러오기
                        </SeedButton>{' '}
                        {version.name} · {version.createdByDisplay}
                      </li>
                    ))}
                  </ul>
                  <h4>활성화 기록</h4>
                  <ul>
                    {history.data.activations.map((entry, index) => (
                      <li key={index}>
                        버전 {entry.version} ·{' '}
                        {entry.state === 'ACTIVE' ? '활성화' : '비활성화'} ·{' '}
                        {entry.actorDisplay}
                      </li>
                    ))}
                  </ul>
                  <h4>최근 실행</h4>
                  {!history.data.executions.length && (
                    <p>완료된 실행이 없습니다.</p>
                  )}
                  <ul>
                    {history.data.executions.map((entry) => (
                      <li key={entry.id}>
                        <a href={`/agent/tickets/${entry.ticketNumber}`}>
                          티켓 #{entry.ticketNumber}
                        </a>{' '}
                        · 버전 {entry.version} · {outcomeLabel(entry.outcome)}
                        {entry.errorCode ? ` · ${entry.errorCode}` : ''}
                      </li>
                    ))}
                  </ul>
                  <h4>접수한 작업</h4>
                  <ul>
                    {history.data.jobs.map((job) => (
                      <li key={job.id}>
                        티켓 #{job.ticketNumber} · {EVENTS[job.eventType]} ·{' '}
                        {outcomeLabel(job.status)} · 시도 {job.attemptCount}회
                        {job.lastErrorCode ? ` · ${job.lastErrorCode}` : ''}
                      </li>
                    ))}
                  </ul>
                </>
              )
            )}
          </section>
        )}
        <SeedButton onClick={onClose}>닫기</SeedButton>
      </div>
    </SeedDrawer>
  )
}
function choices(labels: Record<string, string>): Choice[] {
  return Object.entries(labels).map(([id, label]) => ({ id, label }))
}
function RuleSelect({
  label,
  value,
  options,
  onChange,
  placeholder,
}: {
  label: string
  value: string
  options: Choice[]
  onChange: (value: string) => void
  placeholder?: string
}) {
  return (
    <SeedSelectField
      label={label}
      value={value}
      onChange={(event) => onChange(event.target.value)}
    >
      {placeholder !== undefined && <option value="">{placeholder}</option>}
      {value && !options.some((option) => option.id === value) && (
        <option value={value}>기존 값 (선택지 확인 필요): {value}</option>
      )}
      {options.map((option) => (
        <option key={option.id} value={option.id}>
          {option.label}
        </option>
      ))}
    </SeedSelectField>
  )
}
function defaultAction(type: Action['type']): Action {
  switch (type) {
    case 'SET_GROUP':
      return { type, groupId: '' }
    case 'SET_ASSIGNEE':
      return { type, assigneeId: null }
    case 'SET_PRIORITY':
      return { type, priority: 'HIGH' }
    case 'ENQUEUE_WEBHOOK':
      return { type, eventType: 'ticket.trigger.executed' }
    default:
      return { type }
  }
}
function outcomeLabel(value: string) {
  return (
    (
      {
        MATCHED: '적용됨',
        NOT_MATCHED: '조건 불일치',
        NO_OP: '변경 없음',
        LOOP_BLOCKED: '반복 차단',
        PENDING: '대기',
        LEASED: '처리 중',
        SUCCEEDED: '완료',
        RETRY_SCHEDULED: '재시도 예정',
        DEAD_LETTERED: '재시도 한도 초과',
      } as Record<string, string>
    )[value] ?? value
  )
}
