import { useCallback, useEffect, useRef, useState } from 'react'
import {
  activateFirstReplySlaPolicyVersion,
  ApiError,
  createFirstReplySlaPolicy,
  createFirstReplySlaPolicyVersion,
  getFirstReplySlaAnalytics,
  listBusinessSchedules,
  listFirstReplySlaPolicies,
  listFirstReplySlaPolicyVersions,
  previewFirstReplySlaPolicy,
} from '../api/client'
import type {
  AgentTicketStatus,
  BusinessSchedule,
  FirstReplySlaAnalytics,
  FirstReplySlaPolicy,
  FirstReplySlaPolicyDefinition,
  FirstReplySlaPreview,
  TicketChannel,
  TicketPriority,
} from '../api/types'
import { Notification, ScreenState } from '../shared/ui/system'

const PRIORITIES: TicketPriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT']
const STATUSES: AgentTicketStatus[] = [
  'NEW',
  'OPEN',
  'PENDING',
  'ON_HOLD',
  'SOLVED',
  'CLOSED',
]
const CHANNELS: TicketChannel[] = ['WEB', 'AGENT', 'EMAIL', 'CHAT', 'API']

function emptyPolicy(scheduleId = ''): FirstReplySlaPolicyDefinition {
  return {
    name: '기본 First Reply 정책',
    position: 10,
    scheduleId,
    conditions: { groupId: null, channel: null },
    targets: { LOW: 480, NORMAL: 240, HIGH: 120, URGENT: 60 },
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

function message(error: unknown) {
  if (!(error instanceof ApiError))
    return 'SLA 정책 요청을 처리하지 못했습니다.'
  if (error.status === 403) return 'SLA 정책은 관리자만 관리할 수 있습니다.'
  if (error.status === 412)
    return '다른 관리자가 정책을 변경했습니다. 최신 버전을 불러왔습니다.'
  const fields = error.problem?.fieldErrors
    ?.map((field) => `${field.field}: ${field.message}`)
    .join(' · ')
  return (
    fields ||
    `${error.message}${error.requestId ? ` 요청 ID: ${error.requestId}` : ''}`
  )
}

export function AdminFirstReplySlaPage() {
  const [policies, setPolicies] = useState<FirstReplySlaPolicy[]>([])
  const [versions, setVersions] = useState<FirstReplySlaPolicy[]>([])
  const [schedules, setSchedules] = useState<BusinessSchedule[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [selectedVersion, setSelectedVersion] = useState(0)
  const [draft, setDraft] = useState<FirstReplySlaPolicyDefinition>(emptyPolicy)
  const [creating, setCreating] = useState(false)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [preview, setPreview] = useState<FirstReplySlaPreview | null>(null)
  const [analytics, setAnalytics] = useState<FirstReplySlaAnalytics | null>(
    null,
  )
  const [samplePriority, setSamplePriority] = useState<TicketPriority>('NORMAL')
  const [sampleChannel, setSampleChannel] = useState<TicketChannel>('WEB')
  const [sampleStart, setSampleStart] = useState('2026-08-14T18:30')
  const errorRef = useRef<HTMLDivElement>(null)

  const load = useCallback(
    async (preferredId?: string, preferredVersion?: number) => {
      setLoading(true)
      try {
        const [nextPolicies, nextSchedules, nextAnalytics] = await Promise.all([
          listFirstReplySlaPolicies(),
          listBusinessSchedules(),
          getFirstReplySlaAnalytics(),
        ])
        setPolicies(nextPolicies)
        setSchedules(nextSchedules)
        setAnalytics(nextAnalytics)
        const id =
          preferredId && nextPolicies.some((item) => item.id === preferredId)
            ? preferredId
            : nextPolicies[0]?.id || ''
        setSelectedId(id)
        if (!id) {
          setVersions([])
          setDraft(
            emptyPolicy(
              nextSchedules.find((item) => item.active)?.id ??
                nextSchedules[0]?.id ??
                '',
            ),
          )
          setCreating(true)
          return
        }
        const nextVersions = await listFirstReplySlaPolicyVersions(id)
        setVersions(nextVersions)
        const version =
          preferredVersion &&
          nextVersions.some((item) => item.version === preferredVersion)
            ? preferredVersion
            : (nextVersions[0]?.version ?? 0)
        setSelectedVersion(version)
        const selected = nextVersions.find((item) => item.version === version)
        if (selected) setDraft(copyPolicy(selected))
        setCreating(false)
      } catch (caught) {
        setError(message(caught))
      } finally {
        setLoading(false)
      }
    },
    [],
  )

  useEffect(() => void load(), [load])
  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])

  const selectedPolicy = policies.find((item) => item.id === selectedId)

  async function selectPolicy(id: string) {
    setError(null)
    setNotice(null)
    setLoading(true)
    try {
      const next = await listFirstReplySlaPolicyVersions(id)
      setSelectedId(id)
      setVersions(next)
      setSelectedVersion(next[0]?.version ?? 0)
      if (next[0]) setDraft(copyPolicy(next[0]))
      setCreating(false)
    } catch (caught) {
      setError(message(caught))
    } finally {
      setLoading(false)
    }
  }

  async function save() {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const saved = creating
        ? await createFirstReplySlaPolicy(draft)
        : await createFirstReplySlaPolicyVersion(
            selectedId,
            selectedPolicy?.aggregateVersion ?? 0,
            draft,
          )
      await load(saved.id, saved.version)
      setNotice(
        creating
          ? 'First Reply 정책 초안이 생성되었습니다.'
          : `정책 v${saved.version}이 저장되었습니다.`,
      )
    } catch (caught) {
      setError(message(caught))
      if (caught instanceof ApiError && caught.status === 412)
        await load(selectedId)
    } finally {
      setBusy(false)
    }
  }

  async function activate(version: number) {
    setBusy(true)
    setError(null)
    try {
      const activated = await activateFirstReplySlaPolicyVersion(
        selectedId,
        version,
        selectedPolicy?.aggregateVersion ?? 0,
      )
      await load(selectedId, version)
      setNotice(`정책 v${activated.version}이 활성화되었습니다.`)
    } catch (caught) {
      setError(message(caught))
      if (caught instanceof ApiError && caught.status === 412)
        await load(selectedId)
    } finally {
      setBusy(false)
    }
  }

  async function runPreview() {
    setBusy(true)
    setError(null)
    try {
      setPreview(
        await previewFirstReplySlaPolicy({
          candidate: draft,
          ticket: {
            priority: samplePriority,
            groupId: draft.conditions.groupId,
            channel: sampleChannel,
          },
          startAt: new Date(sampleStart).toISOString(),
        }),
      )
    } catch (caught) {
      setError(message(caught))
    } finally {
      setBusy(false)
    }
  }

  function selectVersion(version: number) {
    const selected = versions.find((item) => item.version === version)
    if (!selected) return
    setSelectedVersion(version)
    setDraft(copyPolicy(selected))
    setPreview(null)
  }

  return (
    <section
      aria-labelledby="sla-policy-title"
      className="schedule-admin sla-policy-admin"
    >
      <div className="admin-title-row">
        <div>
          <p className="eyebrow">BUSINESS RULES · FIRST REPLY</p>
          <h1 id="sla-policy-title">First Reply SLA 정책</h1>
          <p>
            위치 순서로 정책을 평가하고 활성 일정 버전과 priority target을
            ticket에 고정합니다.
          </p>
        </div>
        <button
          className="button secondary"
          type="button"
          onClick={() => {
            setCreating(true)
            setSelectedId('')
            setVersions([])
            setDraft(
              emptyPolicy(
                schedules.find((item) => item.active)?.id ??
                  schedules[0]?.id ??
                  '',
              ),
            )
          }}
        >
          새 정책
        </button>
      </div>

      {error ? (
        <Notification
          ref={errorRef}
          tabIndex={-1}
          tone="danger"
          title="SLA 정책 오류"
        >
          {error}
        </Notification>
      ) : null}
      {notice ? (
        <Notification tone="success" title="저장 완료">
          {notice}
        </Notification>
      ) : null}

      {analytics ? (
        <section
          className="sla-analytics-strip"
          aria-labelledby="sla-analytics-title"
        >
          <div>
            <p className="eyebrow">RECONCILED FACTS</p>
            <h2 id="sla-analytics-title">First Reply 현황</h2>
            <p>
              달성률 분모는 ACHIEVED + BREACHED이며 NO_POLICY는 별도로
              집계합니다.
            </p>
          </div>
          <dl>
            <div>
              <dt>달성률</dt>
              <dd>
                {analytics.achievedRate === null
                  ? '—'
                  : `${Math.round(analytics.achievedRate * 100)}%`}
              </dd>
            </div>
            <div>
              <dt>진행</dt>
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
              <dt>NO_POLICY</dt>
              <dd>{analytics.noPolicy}</dd>
            </div>
          </dl>
        </section>
      ) : null}

      {loading ? (
        <ScreenState kind="loading" title="SLA 정책 불러오는 중" />
      ) : (
        <div className="schedule-layout">
          <aside className="schedule-list" aria-label="SLA 정책 목록">
            {policies.length === 0 ? (
              <p>아직 정책이 없습니다.</p>
            ) : (
              policies.map((policy) => (
                <button
                  type="button"
                  key={policy.id}
                  aria-pressed={policy.id === selectedId}
                  onClick={() => void selectPolicy(policy.id)}
                >
                  <strong>{policy.name}</strong>
                  <span>
                    위치 {policy.position} · 최신 v{policy.version}
                  </span>
                  <span>{policy.active ? '활성 버전' : '초안'}</span>
                </button>
              ))
            )}
          </aside>

          <div className="schedule-editor">
            <div className="schedule-editor-heading">
              <div>
                <p className="eyebrow">
                  {creating
                    ? 'NEW POLICY'
                    : `POLICY VERSION ${selectedVersion}`}
                </p>
                <h2>{draft.name}</h2>
              </div>
              <button
                className="button primary"
                type="button"
                disabled={busy || !draft.scheduleId}
                onClick={() => void save()}
              >
                {busy
                  ? '저장 중…'
                  : creating
                    ? '정책 초안 만들기'
                    : '새 immutable 버전 저장'}
              </button>
            </div>

            <fieldset disabled={busy} className="policy-definition">
              <legend>정책 정의</legend>
              <div className="policy-field-grid">
                <label>
                  정책 이름
                  <input
                    value={draft.name}
                    onChange={(event) =>
                      setDraft({ ...draft, name: event.target.value })
                    }
                  />
                </label>
                <label>
                  평가 위치
                  <input
                    type="number"
                    min="1"
                    max="10000"
                    value={draft.position}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        position: Number(event.target.value),
                      })
                    }
                  />
                </label>
                <label>
                  업무 시간 일정
                  <select
                    value={draft.scheduleId}
                    onChange={(event) =>
                      setDraft({ ...draft, scheduleId: event.target.value })
                    }
                  >
                    <option value="">일정 선택</option>
                    {schedules.map((schedule) => (
                      <option key={schedule.id} value={schedule.id}>
                        {schedule.name} · active{' '}
                        {schedule.active ? `v${schedule.version}` : '없음'}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  채널 조건
                  <select
                    value={draft.conditions.channel ?? ''}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        conditions: {
                          ...draft.conditions,
                          channel: (event.target.value ||
                            null) as TicketChannel | null,
                        },
                      })
                    }
                  >
                    <option value="">모든 채널</option>
                    {CHANNELS.map((channel) => (
                      <option key={channel}>{channel}</option>
                    ))}
                  </select>
                </label>
                <label>
                  그룹 ID 조건 (선택)
                  <input
                    value={draft.conditions.groupId ?? ''}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        conditions: {
                          ...draft.conditions,
                          groupId: event.target.value || null,
                        },
                      })
                    }
                    placeholder="UUID 또는 비워 둠"
                  />
                </label>
              </div>

              <h3>Priority별 First Reply target</h3>
              <div className="policy-target-grid">
                {PRIORITIES.map((priority) => (
                  <label key={priority}>
                    {priority}
                    <input
                      aria-label={`${priority} target minutes`}
                      type="number"
                      min="1"
                      max="525600"
                      value={draft.targets[priority] ?? ''}
                      onChange={(event) =>
                        setDraft({
                          ...draft,
                          targets: {
                            ...draft.targets,
                            [priority]: event.target.value
                              ? Number(event.target.value)
                              : null,
                          },
                        })
                      }
                    />
                    <span>분</span>
                  </label>
                ))}
              </div>

              <h3>시간 정지 상태</h3>
              <div className="policy-pause-grid">
                {STATUSES.map((status) => (
                  <label key={status}>
                    <input
                      type="checkbox"
                      checked={draft.pauseStatuses.includes(status)}
                      onChange={(event) =>
                        setDraft({
                          ...draft,
                          pauseStatuses: event.target.checked
                            ? [...draft.pauseStatuses, status]
                            : draft.pauseStatuses.filter(
                                (item) => item !== status,
                              ),
                        })
                      }
                    />
                    {status}
                  </label>
                ))}
              </div>
            </fieldset>

            <section
              className="policy-preview"
              aria-labelledby="policy-preview-title"
            >
              <h3 id="policy-preview-title">정책 preview</h3>
              <div className="policy-field-grid">
                <label>
                  Sample priority
                  <select
                    value={samplePriority}
                    onChange={(event) =>
                      setSamplePriority(event.target.value as TicketPriority)
                    }
                  >
                    {PRIORITIES.map((priority) => (
                      <option key={priority}>{priority}</option>
                    ))}
                  </select>
                </label>
                <label>
                  Sample channel
                  <select
                    value={sampleChannel}
                    onChange={(event) =>
                      setSampleChannel(event.target.value as TicketChannel)
                    }
                  >
                    {CHANNELS.map((channel) => (
                      <option key={channel}>{channel}</option>
                    ))}
                  </select>
                </label>
                <label>
                  고객 첫 공개 문의 시각
                  <input
                    type="datetime-local"
                    value={sampleStart}
                    onChange={(event) => setSampleStart(event.target.value)}
                  />
                </label>
              </div>
              <button
                className="button secondary"
                type="button"
                disabled={busy}
                onClick={() => void runPreview()}
              >
                선택·기한 preview
              </button>
              {preview ? (
                <dl className="preview-results">
                  <div>
                    <dt>정책 일치</dt>
                    <dd>{preview.matched ? '일치' : 'NO_POLICY'}</dd>
                  </div>
                  <div>
                    <dt>Target</dt>
                    <dd>
                      {preview.targetMinutes
                        ? `${preview.targetMinutes}분`
                        : '없음'}
                    </dd>
                  </div>
                  <div>
                    <dt>Due</dt>
                    <dd>
                      {preview.dueAt
                        ? new Date(preview.dueAt).toLocaleString('ko-KR')
                        : '없음'}
                    </dd>
                  </div>
                  <div>
                    <dt>Schedule snapshot</dt>
                    <dd>
                      {preview.scheduleVersion
                        ? `v${preview.scheduleVersion}`
                        : '없음'}
                    </dd>
                  </div>
                  <div>
                    <dt>DST</dt>
                    <dd>{preview.dstPolicy}</dd>
                  </div>
                </dl>
              ) : null}
            </section>

            {!creating ? (
              <section
                className="schedule-versions"
                aria-labelledby="policy-version-history"
              >
                <h3 id="policy-version-history">Immutable version history</h3>
                {versions.map((version) => (
                  <div key={version.version} className="schedule-version-row">
                    <button
                      type="button"
                      aria-pressed={version.version === selectedVersion}
                      onClick={() => selectVersion(version.version)}
                    >
                      v{version.version} · 위치 {version.position}
                    </button>
                    <span>
                      {version.active
                        ? '현재 활성'
                        : `${version.createdBy.displayName} 생성`}
                    </span>
                    {!version.active ? (
                      <button
                        className="button secondary small"
                        type="button"
                        disabled={
                          busy ||
                          Object.values(version.targets).every(
                            (value) => value === null,
                          )
                        }
                        onClick={() => void activate(version.version)}
                      >
                        이 버전 활성화
                      </button>
                    ) : null}
                  </div>
                ))}
              </section>
            ) : null}
          </div>
        </div>
      )}
    </section>
  )
}
