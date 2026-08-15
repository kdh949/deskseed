import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
} from '@tanstack/react-query'
import {
  useEffect,
  useMemo,
  useState,
  type FormEvent,
  type ReactNode,
} from 'react'
import {
  ApiError,
  activateFirstReplySlaPolicyVersion,
  createFirstReplySlaPolicy,
  createFirstReplySlaPolicyVersion,
  getFirstReplySlaAnalytics,
  listBusinessSchedules,
  listFirstReplySlaPolicies,
  listFirstReplySlaPolicyVersions,
  listGroups,
  previewFirstReplySlaPolicy,
} from '../../api/client'
import type {
  AgentTicketStatus,
  FirstReplySlaAnalytics,
  FirstReplySlaPolicy,
  FirstReplySlaPolicyDefinition,
  FirstReplySlaPreview,
  FirstReplySlaPreviewInput,
  SupportGroup,
  TicketChannel,
  TicketPriority,
} from '../../api/types'
import {
  DsButton,
  Notification,
  RetryButton,
  ScreenState,
} from '../../design-system'
import { recoverAmbiguousAdminMutationOutcome } from './adminMutationRecovery'

const PRIORITIES: Array<{ label: string; value: TicketPriority }> = [
  { value: 'LOW', label: '낮음' },
  { value: 'NORMAL', label: '보통' },
  { value: 'HIGH', label: '높음' },
  { value: 'URGENT', label: '긴급' },
]

const CHANNELS: Array<{ label: string; value: TicketChannel }> = [
  { value: 'WEB', label: '웹' },
  { value: 'AGENT', label: '상담사' },
  { value: 'EMAIL', label: '이메일' },
  { value: 'CHAT', label: '채팅' },
  { value: 'API', label: 'API' },
]

const PAUSE_STATUSES: Array<{
  label: string
  value: Exclude<AgentTicketStatus, 'SOLVED' | 'CLOSED'>
}> = [
  { value: 'NEW', label: '신규' },
  { value: 'OPEN', label: '처리 중' },
  { value: 'PENDING', label: '고객 답변 대기' },
  { value: 'ON_HOLD', label: '보류' },
]

const SLA_GROUP_PAGE_SIZE = 100
const SLA_GROUP_PAGE_CONCURRENCY = 4

function blankPolicy(scheduleId = ''): FirstReplySlaPolicyDefinition {
  return {
    name: '',
    position: 10,
    scheduleId,
    conditions: { groupId: null, channel: null },
    targets: { LOW: null, NORMAL: null, HIGH: null, URGENT: null },
    pauseStatuses: ['PENDING'],
  }
}

function copyPolicy(
  policy: FirstReplySlaPolicy,
): FirstReplySlaPolicyDefinition {
  return {
    name: policy.name,
    position: policy.position,
    scheduleId: policy.scheduleId,
    conditions: { ...policy.conditions },
    targets: { ...policy.targets },
    pauseStatuses: [...policy.pauseStatuses],
  }
}

async function listAllSlaGroups(): Promise<SupportGroup[]> {
  const firstPage = await listGroups(0, SLA_GROUP_PAGE_SIZE)
  const additionalPages: SupportGroup[][] = []
  for (
    let page = 1;
    page < firstPage.totalPages;
    page += SLA_GROUP_PAGE_CONCURRENCY
  ) {
    const pages = await Promise.all(
      Array.from(
        {
          length: Math.min(
            SLA_GROUP_PAGE_CONCURRENCY,
            firstPage.totalPages - page,
          ),
        },
        (_, offset) => listGroups(page + offset, SLA_GROUP_PAGE_SIZE),
      ),
    )
    additionalPages.push(...pages.map((result) => result.items))
  }
  return Array.from(
    new Map(
      [firstPage.items, ...additionalPages]
        .flat()
        .map((group) => [group.id, group]),
    ).values(),
  )
}

export function AdminFirstReplySlaPage() {
  const queryClient = useQueryClient()
  const [selectedPolicy, setSelectedPolicy] =
    useState<FirstReplySlaPolicy | null>(null)
  const [selectedVersionNumber, setSelectedVersionNumber] = useState<
    number | null
  >(null)
  const [editorOpen, setEditorOpen] = useState(false)
  const [draft, setDraft] =
    useState<FirstReplySlaPolicyDefinition>(blankPolicy())
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saveOutcomeUnknown, setSaveOutcomeUnknown] = useState(false)
  const [activationOpen, setActivationOpen] = useState(false)
  const [previewPriority, setPreviewPriority] =
    useState<TicketPriority>('NORMAL')
  const [previewGroupId, setPreviewGroupId] = useState<string | null>(null)
  const [previewChannel, setPreviewChannel] = useState<TicketChannel>('WEB')
  const [previewStart, setPreviewStart] = useState('')

  const policiesQuery = useQuery({
    queryKey: ['admin-first-reply-sla-policies'],
    queryFn: listFirstReplySlaPolicies,
    retry: false,
  })
  const schedulesQuery = useQuery({
    queryKey: ['admin-first-reply-sla-schedules'],
    queryFn: listBusinessSchedules,
    retry: false,
  })
  const groupsQuery = useQuery({
    queryKey: ['admin-first-reply-sla-groups'],
    queryFn: listAllSlaGroups,
    retry: false,
  })
  const analyticsQuery = useQuery({
    queryKey: ['admin-first-reply-sla-analytics'],
    queryFn: getFirstReplySlaAnalytics,
    retry: false,
  })
  const versionsQuery = useQuery({
    queryKey: ['admin-first-reply-sla-policy-versions', selectedPolicy?.id],
    queryFn: () => listFirstReplySlaPolicyVersions(selectedPolicy!.id),
    enabled: selectedPolicy !== null,
    retry: false,
  })
  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: ['admin-first-reply-sla-policies'],
      }),
      queryClient.invalidateQueries({
        queryKey: ['admin-first-reply-sla-policy-versions'],
      }),
      queryClient.invalidateQueries({
        queryKey: ['admin-first-reply-sla-analytics'],
      }),
    ])
  }
  const saveMutation = useMutation({
    mutationFn: ({
      definition,
      editing,
    }: {
      definition: FirstReplySlaPolicyDefinition
      editing: FirstReplySlaPolicy | null
    }) =>
      editing
        ? createFirstReplySlaPolicyVersion(
            editing.id,
            editing.aggregateVersion,
            definition,
          )
        : createFirstReplySlaPolicy(definition),
    onSuccess: async (policy) => {
      setSelectedPolicy(policy)
      setSelectedVersionNumber(policy.version)
      setDraft(copyPolicy(policy))
      setSaveError(null)
      setSaveOutcomeUnknown(false)
      await refresh()
    },
    onError: async (error) => {
      setSaveOutcomeUnknown(
        await recoverAmbiguousAdminMutationOutcome(error, refresh),
      )
    },
  })
  const activateMutation = useMutation({
    mutationFn: ({
      policy,
      version,
    }: {
      policy: FirstReplySlaPolicy
      version: number
    }) =>
      activateFirstReplySlaPolicyVersion(
        policy.id,
        version,
        policy.aggregateVersion,
      ),
    onSuccess: async (policy) => {
      setSelectedPolicy(policy)
      setSelectedVersionNumber(policy.version)
      setActivationOpen(false)
      await refresh()
    },
  })
  const previewMutation = useMutation({
    mutationFn: previewFirstReplySlaPolicy,
  })

  useEffect(() => {
    if (!selectedPolicy) return
    const current = policiesQuery.data?.find(
      (policy) => policy.id === selectedPolicy.id,
    )
    if (current) setSelectedPolicy(current)
  }, [policiesQuery.data, selectedPolicy])

  const selectedVersion = useMemo(() => {
    const versions = versionsQuery.data ?? []
    if (selectedVersionNumber !== null) {
      return (
        versions.find((version) => version.version === selectedVersionNumber) ??
        null
      )
    }
    return selectedPolicy
      ? (versions.find(
          (version) => version.version === selectedPolicy.version,
        ) ?? null)
      : null
  }, [selectedPolicy, selectedVersionNumber, versionsQuery.data])

  if (policiesQuery.isPending) {
    return (
      <AdminSlaScreenState
        kind="loading"
        title="First Reply SLA 정책을 불러오는 중"
      />
    )
  }
  if (policiesQuery.isError) {
    const denied =
      policiesQuery.error instanceof ApiError &&
      policiesQuery.error.status === 403
    return (
      <AdminSlaScreenState
        action={
          denied ? undefined : (
            <RetryButton onClick={() => void policiesQuery.refetch()} />
          )
        }
        description={
          denied
            ? 'SLA 정책은 ADMIN만 관리할 수 있습니다.'
            : '잠시 후 SLA 정책을 다시 요청해 주세요.'
        }
        kind={denied ? 'denied' : 'error'}
        title={
          denied
            ? 'SLA 정책 관리 권한이 없습니다.'
            : 'First Reply SLA 정책을 불러오지 못했습니다.'
        }
      />
    )
  }

  const scheduleOptions = schedulesQuery.data ?? []
  const allGroupOptions = groupsQuery.data ?? []
  const groupOptions = allGroupOptions.filter(
    (group) => group.status === 'ACTIVE',
  )
  const preservedConditionGroup = draft.conditions.groupId
    ? allGroupOptions.find((group) => group.id === draft.conditions.groupId)
    : undefined
  const submitPolicy = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (saveOutcomeUnknown) return
    if (!draft.name.trim() || !draft.scheduleId) {
      setSaveError('정책 이름과 적용할 영업 시간표를 선택해 주세요.')
      return
    }
    if (!Number.isSafeInteger(draft.position) || draft.position < 1) {
      setSaveError('적용 순서는 1 이상의 정수여야 합니다.')
      return
    }
    setSaveError(null)
    saveMutation.mutate({
      definition: normalizePolicy(draft),
      editing: selectedPolicy,
    })
  }
  const openNewPolicy = () => {
    if (saveOutcomeUnknown) return
    setSelectedPolicy(null)
    setSelectedVersionNumber(null)
    setDraft(blankPolicy(scheduleOptions[0]?.id ?? ''))
    setEditorOpen(true)
    setSaveError(null)
    setSaveOutcomeUnknown(false)
    saveMutation.reset()
    previewMutation.reset()
  }
  const openNewVersion = () => {
    if (!selectedVersion) return
    if (saveOutcomeUnknown) return
    setDraft(copyPolicy(selectedVersion))
    setEditorOpen(true)
    setSaveError(null)
    setSaveOutcomeUnknown(false)
    saveMutation.reset()
    previewMutation.reset()
  }
  const closeEditor = () => {
    setEditorOpen(false)
    setSaveOutcomeUnknown(false)
    saveMutation.reset()
  }

  return (
    <main aria-label="First Reply SLA 관리" className="admin-page">
      <header className="admin-page-header">
        <div>
          <h1>First Reply SLA</h1>
          <p>
            첫 PUBLIC 답변의 목표, pause 상태, 적용 순서와 영업 시간표
            snapshot을 version으로 관리합니다.
          </p>
        </div>
        <div className="admin-inline-actions">
          <DsButton
            disabled={
              schedulesQuery.isPending ||
              schedulesQuery.isError ||
              saveOutcomeUnknown
            }
            onClick={openNewPolicy}
            tone="primary"
          >
            새 SLA 정책
          </DsButton>
          <DsButton
            onClick={() => void policiesQuery.refetch()}
            tone="secondary"
          >
            SLA 정책 새로고침
          </DsButton>
        </div>
      </header>

      <SlaAnalytics query={analyticsQuery} />
      {schedulesQuery.isError ? (
        <Notification
          title="영업 시간표 선택 목록을 불러오지 못했습니다."
          tone="danger"
        >
          <p>SLA 정책을 작성하려면 실제 영업 시간표 projection이 필요합니다.</p>
        </Notification>
      ) : null}
      {groupsQuery.isError ? (
        <Notification
          title="그룹 조건 목록을 불러오지 못했습니다."
          tone="danger"
        >
          <p>
            그룹 조건을 사용하지 않는 정책만 현재 서버 검증에 따라 작성할 수
            있습니다.
          </p>
        </Notification>
      ) : null}

      <section
        aria-labelledby="sla-policy-list-heading"
        className="admin-surface"
      >
        <h2 id="sla-policy-list-heading">First Reply SLA 정책</h2>
        {policiesQuery.data.length === 0 ? (
          <ScreenState
            compact
            description="영업 시간표를 먼저 준비한 뒤 SLA 정책을 작성하고 명시적으로 활성화하세요."
            kind="empty"
            title="등록된 First Reply SLA 정책이 없습니다."
          />
        ) : (
          <div className="admin-table-wrap">
            <table className="admin-table">
              <caption className="sr-only">First Reply SLA 정책 목록</caption>
              <thead>
                <tr>
                  <th scope="col">이름</th>
                  <th scope="col">순서</th>
                  <th scope="col">시간표 version</th>
                  <th scope="col">최신 / 활성 version</th>
                  <th scope="col">상태</th>
                  <th scope="col">작업</th>
                </tr>
              </thead>
              <tbody>
                {policiesQuery.data.map((policy) => (
                  <tr key={policy.id}>
                    <td>{policy.name}</td>
                    <td>{policy.position}</td>
                    <td>{policy.scheduleVersion}</td>
                    <td>{`${policy.version} / ${policy.activeVersion ?? '—'}`}</td>
                    <td>{policy.active ? '활성' : '비활성'}</td>
                    <td>
                      <DsButton
                        aria-expanded={selectedPolicy?.id === policy.id}
                        onClick={() => {
                          setSelectedPolicy(policy)
                          setSelectedVersionNumber(policy.version)
                          closeEditor()
                          setActivationOpen(false)
                        }}
                        tone="secondary"
                      >
                        SLA 정책 관리
                      </DsButton>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {selectedPolicy ? (
        <section
          aria-labelledby="sla-policy-detail-heading"
          className="admin-surface"
        >
          <div className="admin-page-header">
            <div>
              <h2 id="sla-policy-detail-heading">{selectedPolicy.name}</h2>
              <p>{`aggregate version ${selectedPolicy.aggregateVersion} · schedule version ${selectedPolicy.scheduleVersion}`}</p>
            </div>
            <div className="admin-inline-actions">
              <DsButton
                disabled={versionsQuery.isPending || saveOutcomeUnknown}
                onClick={openNewVersion}
                tone="primary"
              >
                새 version 작성
              </DsButton>
              <DsButton
                onClick={() => setSelectedPolicy(null)}
                tone="secondary"
              >
                닫기
              </DsButton>
            </div>
          </div>
          {versionsQuery.isPending ? (
            <ScreenState
              compact
              kind="loading"
              title="SLA policy version 이력을 불러오는 중"
            />
          ) : versionsQuery.isError ? (
            <Notification
              title="SLA policy version 이력을 불러오지 못했습니다."
              tone="danger"
            />
          ) : (
            <>
              <ul
                className="admin-version-list"
                aria-label="SLA policy version 이력"
              >
                {versionsQuery.data.map((version) => (
                  <li key={version.version}>
                    <strong>{`version ${version.version}`}</strong>
                    <span className="admin-muted">
                      {version.active ? '현재 활성' : '비활성 version'}
                    </span>
                    <span className="admin-muted">
                      {formatTimestamp(version.createdAt)}
                    </span>
                    <DsButton
                      aria-pressed={
                        selectedVersion?.version === version.version
                      }
                      onClick={() => setSelectedVersionNumber(version.version)}
                      tone="secondary"
                    >
                      이 version 검토
                    </DsButton>
                  </li>
                ))}
              </ul>
              {selectedVersion ? (
                <SlaReadModel
                  groupOptions={allGroupOptions}
                  onActivate={() => setActivationOpen(true)}
                  policy={selectedVersion}
                  showActivate={
                    selectedPolicy.activeVersion !== selectedVersion.version
                  }
                />
              ) : null}
              {activationOpen && selectedVersion ? (
                <div
                  className="admin-confirmation"
                  role="group"
                  aria-label="SLA policy version 활성화 최종 확인"
                >
                  <p>{`version ${selectedVersion.version}을(를) 활성화할까요? 이미 계산된 target snapshot은 변경하지 않습니다.`}</p>
                  <DsButton
                    disabled={activateMutation.isPending}
                    onClick={() =>
                      activateMutation.mutate({
                        policy: selectedPolicy,
                        version: selectedVersion.version,
                      })
                    }
                    tone="primary"
                  >
                    {activateMutation.isPending
                      ? '활성화 중…'
                      : 'version 활성화 확정'}
                  </DsButton>
                  <DsButton
                    disabled={activateMutation.isPending}
                    onClick={() => setActivationOpen(false)}
                    tone="secondary"
                  >
                    취소
                  </DsButton>
                  {activateMutation.isError ? (
                    <SlaMutationNotification
                      action="SLA policy version을 활성화"
                      error={activateMutation.error}
                    />
                  ) : null}
                  {activateMutation.isSuccess ? (
                    <Notification
                      title="SLA policy version을 활성화했습니다."
                      tone="success"
                    />
                  ) : null}
                </div>
              ) : null}
            </>
          )}
        </section>
      ) : null}

      {editorOpen ? (
        <section
          aria-labelledby="sla-policy-editor-heading"
          className="admin-surface"
        >
          <div className="admin-page-header">
            <div>
              <h2 id="sla-policy-editor-heading">
                {selectedPolicy
                  ? `${selectedPolicy.name} 새 version`
                  : '새 First Reply SLA 정책'}
              </h2>
              <p>
                저장 전 미리보기는 candidate policy만 평가하고 정책 version을
                만들지 않습니다.
              </p>
            </div>
            <DsButton onClick={closeEditor} tone="secondary">
              작성 닫기
            </DsButton>
          </div>
          {scheduleOptions.length === 0 ? (
            <ScreenState
              compact
              description="SLA 정책은 실제 영업 시간표를 참조해야 합니다."
              kind="empty"
              title="선택할 영업 시간표가 없습니다."
            />
          ) : (
            <>
              <form className="admin-form" onSubmit={submitPolicy}>
                <div className="admin-form-grid">
                  <label className="admin-field" htmlFor="sla-policy-name">
                    <span>정책 이름</span>
                    <input
                      id="sla-policy-name"
                      maxLength={100}
                      onChange={(event) =>
                        setDraft((current) => ({
                          ...current,
                          name: event.target.value,
                        }))
                      }
                      value={draft.name}
                    />
                  </label>
                  <label className="admin-field" htmlFor="sla-policy-position">
                    <span>적용 순서</span>
                    <input
                      id="sla-policy-position"
                      min="1"
                      onChange={(event) =>
                        setDraft((current) => ({
                          ...current,
                          position: Number(event.target.value),
                        }))
                      }
                      type="number"
                      value={draft.position}
                    />
                  </label>
                  <label className="admin-field" htmlFor="sla-policy-schedule">
                    <span>영업 시간표</span>
                    <select
                      id="sla-policy-schedule"
                      onChange={(event) =>
                        setDraft((current) => ({
                          ...current,
                          scheduleId: event.target.value,
                        }))
                      }
                      value={draft.scheduleId}
                    >
                      <option value="">시간표를 선택하세요</option>
                      {scheduleOptions.map((schedule) => (
                        <option
                          key={schedule.id}
                          value={schedule.id}
                        >{`${schedule.name} (v${schedule.activeVersion ?? schedule.version})`}</option>
                      ))}
                    </select>
                  </label>
                  <label className="admin-field" htmlFor="sla-policy-group">
                    <span>그룹 조건</span>
                    <select
                      id="sla-policy-group"
                      onChange={(event) =>
                        setDraft((current) => ({
                          ...current,
                          conditions: {
                            ...current.conditions,
                            groupId: event.target.value || null,
                          },
                        }))
                      }
                      value={draft.conditions.groupId ?? ''}
                    >
                      <option value="">모든 그룹</option>
                      {draft.conditions.groupId &&
                      !groupOptions.some(
                        (group) => group.id === draft.conditions.groupId,
                      ) ? (
                        <option disabled value={draft.conditions.groupId}>
                          {preservedConditionGroup
                            ? `현재 조건: ${preservedConditionGroup.name} (비활성)`
                            : `현재 조건: ${draft.conditions.groupId} (조회 불가)`}
                        </option>
                      ) : null}
                      {groupOptions.map((group) => (
                        <option key={group.id} value={group.id}>
                          {group.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="admin-field" htmlFor="sla-policy-channel">
                    <span>채널 조건</span>
                    <select
                      id="sla-policy-channel"
                      onChange={(event) =>
                        setDraft((current) => ({
                          ...current,
                          conditions: {
                            ...current.conditions,
                            channel: (event.target.value ||
                              null) as TicketChannel | null,
                          },
                        }))
                      }
                      value={draft.conditions.channel ?? ''}
                    >
                      <option value="">모든 채널</option>
                      {CHANNELS.map((channel) => (
                        <option key={channel.value} value={channel.value}>
                          {channel.label}
                        </option>
                      ))}
                    </select>
                  </label>
                </div>
                <fieldset className="admin-form">
                  <legend>우선순위별 최초 답변 목표 (분)</legend>
                  <div className="admin-form-grid">
                    {PRIORITIES.map((priority) => (
                      <label
                        className="admin-field"
                        htmlFor={`sla-target-${priority.value}`}
                        key={priority.value}
                      >
                        <span>{priority.label}</span>
                        <input
                          id={`sla-target-${priority.value}`}
                          min="1"
                          onChange={(event) =>
                            setDraft((current) => ({
                              ...current,
                              targets: {
                                ...current.targets,
                                [priority.value]:
                                  event.target.value === ''
                                    ? null
                                    : Number(event.target.value),
                              },
                            }))
                          }
                          placeholder="미적용"
                          type="number"
                          value={draft.targets[priority.value] ?? ''}
                        />
                      </label>
                    ))}
                  </div>
                </fieldset>
                <fieldset className="admin-form">
                  <legend>시계 정지 상태</legend>
                  <div className="admin-check-list">
                    {PAUSE_STATUSES.map((status) => {
                      const checked = draft.pauseStatuses.includes(status.value)
                      return (
                        <label key={status.value}>
                          <input
                            checked={checked}
                            onChange={() =>
                              setDraft((current) => ({
                                ...current,
                                pauseStatuses: checked
                                  ? current.pauseStatuses.filter(
                                      (value) => value !== status.value,
                                    )
                                  : [...current.pauseStatuses, status.value],
                              }))
                            }
                            type="checkbox"
                          />
                          {status.label}
                        </label>
                      )
                    })}
                  </div>
                </fieldset>
                {saveError ? (
                  <Notification title={saveError} tone="warning" />
                ) : null}
                {saveOutcomeUnknown ? (
                  <Notification
                    title="SLA 정책 저장 결과를 확인할 수 없습니다."
                    tone="warning"
                  >
                    <p>
                      서버 응답이 유실되었을 수 있어 같은 요청을 다시 제출하지
                      않습니다. 정책 목록과 version 이력을 다시 읽었습니다. 서버
                      상태를 확인한 뒤 이 작성 화면을 닫고 다음 작업을 선택해
                      주세요.
                    </p>
                  </Notification>
                ) : saveMutation.isError ? (
                  <SlaMutationNotification
                    action="SLA 정책을 저장"
                    error={saveMutation.error}
                  />
                ) : null}
                {saveMutation.isSuccess ? (
                  <Notification
                    title="SLA policy version을 저장했습니다."
                    tone="success"
                  />
                ) : null}
                <div className="admin-form-actions">
                  <DsButton
                    disabled={saveMutation.isPending || saveOutcomeUnknown}
                    tone="primary"
                    type="submit"
                  >
                    {saveMutation.isPending
                      ? 'SLA 정책 저장 중…'
                      : selectedPolicy
                        ? '새 version 저장'
                        : 'SLA 정책 생성'}
                  </DsButton>
                </div>
              </form>
              <SlaPreview
                draft={draft}
                groupOptions={groupOptions}
                mutation={previewMutation}
                onChannelChange={setPreviewChannel}
                onGroupChange={setPreviewGroupId}
                onPriorityChange={setPreviewPriority}
                onStartChange={setPreviewStart}
                policyId={selectedPolicy?.id ?? null}
                previewChannel={previewChannel}
                previewGroupId={previewGroupId}
                previewPriority={previewPriority}
                previewStart={previewStart}
              />
            </>
          )}
        </section>
      ) : null}
    </main>
  )
}

function SlaAnalytics({
  query,
}: {
  query: ReturnType<typeof useQuery<FirstReplySlaAnalytics, Error>>
}) {
  if (query.isPending) {
    return (
      <section aria-label="First Reply SLA 성과" className="admin-surface">
        <ScreenState
          compact
          kind="loading"
          title="First Reply SLA 성과를 불러오는 중"
        />
      </section>
    )
  }
  if (query.isError) {
    return (
      <Notification
        title="First Reply SLA 성과를 불러오지 못했습니다."
        tone="danger"
      >
        <p>
          정책 운영은 계속할 수 있지만 성과 수치는 서버에서 다시 확인해 주세요.
        </p>
      </Notification>
    )
  }
  const analytics = query.data
  return (
    <section aria-labelledby="sla-analytics-heading" className="admin-surface">
      <h2 id="sla-analytics-heading">현재 First Reply SLA 성과</h2>
      <dl className="admin-definition-list">
        <div>
          <dt>활성</dt>
          <dd>{analytics.active}</dd>
        </div>
        <div>
          <dt>정지</dt>
          <dd>{analytics.paused}</dd>
        </div>
        <div>
          <dt>달성</dt>
          <dd>{analytics.achieved}</dd>
        </div>
        <div>
          <dt>위반</dt>
          <dd>{analytics.breached}</dd>
        </div>
        <div>
          <dt>정책 없음</dt>
          <dd>{analytics.noPolicy}</dd>
        </div>
        <div>
          <dt>달성률</dt>
          <dd>
            {analytics.achievedRate === null
              ? '—'
              : `${Math.round(analytics.achievedRate * 100)}%`}
          </dd>
        </div>
      </dl>
    </section>
  )
}

function SlaReadModel({
  groupOptions,
  onActivate,
  policy,
  showActivate,
}: {
  groupOptions: Array<{ id: string; name: string }>
  onActivate: () => void
  policy: FirstReplySlaPolicy
  showActivate: boolean
}) {
  return (
    <section
      aria-labelledby="sla-version-review-heading"
      className="admin-surface"
    >
      <div className="admin-page-header">
        <div>
          <h3 id="sla-version-review-heading">{`version ${policy.version} 검토`}</h3>
          <p>{`${policy.active ? '활성' : '비활성'} · 시간표 version ${policy.scheduleVersion}`}</p>
        </div>
        {showActivate ? (
          <DsButton onClick={onActivate} tone="primary">
            이 version 활성화
          </DsButton>
        ) : null}
      </div>
      <dl className="admin-definition-list">
        <div>
          <dt>적용 순서</dt>
          <dd>{policy.position}</dd>
        </div>
        <div>
          <dt>그룹 조건</dt>
          <dd>
            {policy.conditions.groupId
              ? (groupOptions.find(
                  (group) => group.id === policy.conditions.groupId,
                )?.name ?? '삭제되었거나 조회 불가한 그룹')
              : '모든 그룹'}
          </dd>
        </div>
        <div>
          <dt>채널 조건</dt>
          <dd>{policy.conditions.channel ?? '모든 채널'}</dd>
        </div>
        <div>
          <dt>정지 상태</dt>
          <dd>{policy.pauseStatuses.join(', ') || '없음'}</dd>
        </div>
        {PRIORITIES.map((priority) => (
          <div key={priority.value}>
            <dt>{`${priority.label} 목표`}</dt>
            <dd>
              {policy.targets[priority.value] === null
                ? '미적용'
                : `${policy.targets[priority.value]}분`}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

function SlaPreview({
  draft,
  groupOptions,
  mutation,
  onChannelChange,
  onGroupChange,
  onPriorityChange,
  onStartChange,
  policyId,
  previewChannel,
  previewGroupId,
  previewPriority,
  previewStart,
}: {
  draft: FirstReplySlaPolicyDefinition
  groupOptions: Array<{ id: string; name: string }>
  mutation: UseMutationResult<
    FirstReplySlaPreview,
    Error,
    FirstReplySlaPreviewInput,
    unknown
  >
  onChannelChange: (value: TicketChannel) => void
  onGroupChange: (value: string | null) => void
  onPriorityChange: (value: TicketPriority) => void
  onStartChange: (value: string) => void
  policyId: string | null
  previewChannel: TicketChannel
  previewGroupId: string | null
  previewPriority: TicketPriority
  previewStart: string
}) {
  const [validationError, setValidationError] = useState<string | null>(null)
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!draft.name.trim() || !draft.scheduleId || !previewStart) {
      setValidationError(
        '후보 정책의 이름/시간표와 샘플 시작 시각을 입력해 주세요.',
      )
      return
    }
    setValidationError(null)
    mutation.mutate({
      candidatePolicyId: policyId,
      candidate: normalizePolicy(draft),
      ticket: {
        priority: previewPriority,
        groupId: previewGroupId,
        channel: previewChannel,
      },
      startAt: new Date(previewStart).toISOString(),
    })
  }
  return (
    <section aria-labelledby="sla-preview-heading" className="admin-surface">
      <h3 id="sla-preview-heading">저장 전 SLA 적용 미리보기</h3>
      <form className="admin-form" onSubmit={submit}>
        <div className="admin-form-grid">
          <label className="admin-field" htmlFor="sla-preview-priority">
            <span>샘플 우선순위</span>
            <select
              id="sla-preview-priority"
              onChange={(event) =>
                onPriorityChange(event.target.value as TicketPriority)
              }
              value={previewPriority}
            >
              {PRIORITIES.map((priority) => (
                <option key={priority.value} value={priority.value}>
                  {priority.label}
                </option>
              ))}
            </select>
          </label>
          <label className="admin-field" htmlFor="sla-preview-group">
            <span>샘플 그룹</span>
            <select
              id="sla-preview-group"
              onChange={(event) => onGroupChange(event.target.value || null)}
              value={previewGroupId ?? ''}
            >
              <option value="">미배정</option>
              {groupOptions.map((group) => (
                <option key={group.id} value={group.id}>
                  {group.name}
                </option>
              ))}
            </select>
          </label>
          <label className="admin-field" htmlFor="sla-preview-channel">
            <span>샘플 채널</span>
            <select
              id="sla-preview-channel"
              onChange={(event) =>
                onChannelChange(event.target.value as TicketChannel)
              }
              value={previewChannel}
            >
              {CHANNELS.map((channel) => (
                <option key={channel.value} value={channel.value}>
                  {channel.label}
                </option>
              ))}
            </select>
          </label>
          <label className="admin-field" htmlFor="sla-preview-start">
            <span>샘플 시작 시각</span>
            <input
              id="sla-preview-start"
              onChange={(event) => onStartChange(event.target.value)}
              type="datetime-local"
              value={previewStart}
            />
          </label>
        </div>
        {validationError ? (
          <Notification title={validationError} tone="warning" />
        ) : null}
        {mutation.isError ? (
          <SlaMutationNotification
            action="SLA 적용 미리보기를 계산"
            error={mutation.error}
          />
        ) : null}
        {mutation.data ? (
          <Notification title="SLA 적용 미리보기 결과" tone="info">
            <p>
              {mutation.data.matched
                ? `목표 ${mutation.data.targetMinutes ?? '—'}분, 예정 시각 ${formatTimestamp(mutation.data.dueAt)}`
                : '일치하는 후보 또는 활성 정책이 없습니다.'}
            </p>
          </Notification>
        ) : null}
        <div className="admin-form-actions">
          <DsButton
            disabled={mutation.isPending}
            tone="secondary"
            type="submit"
          >
            {mutation.isPending ? '미리보기 계산 중…' : 'SLA 적용 미리보기'}
          </DsButton>
        </div>
      </form>
    </section>
  )
}

function normalizePolicy(
  definition: FirstReplySlaPolicyDefinition,
): FirstReplySlaPolicyDefinition {
  return {
    name: definition.name.trim(),
    position: definition.position,
    scheduleId: definition.scheduleId,
    conditions: { ...definition.conditions },
    targets: { ...definition.targets },
    pauseStatuses: [...definition.pauseStatuses],
  }
}

function SlaMutationNotification({
  action,
  error,
}: {
  action: string
  error: unknown
}) {
  const conflict =
    error instanceof ApiError && (error.status === 409 || error.status === 412)
  return (
    <Notification
      title={
        conflict ? `${action}할 수 없습니다.` : `${action}하지 못했습니다.`
      }
      tone={conflict ? 'conflict' : 'danger'}
    >
      <p>
        {conflict
          ? '작성 중인 정책은 보존했습니다. 최신 version을 새로고침한 뒤 다시 검토해 주세요.'
          : '작성 중인 정책은 보존했습니다. 서버 검증 오류를 확인한 뒤 다시 시도해 주세요.'}
      </p>
    </Notification>
  )
}

function AdminSlaScreenState({
  action,
  description,
  kind,
  title,
}: {
  action?: ReactNode
  description?: string
  kind: 'denied' | 'error' | 'loading'
  title: string
}) {
  return (
    <main className="admin-page">
      <ScreenState
        action={action}
        description={description}
        kind={kind}
        title={title}
      />
    </main>
  )
}

function formatTimestamp(value: string | null) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
