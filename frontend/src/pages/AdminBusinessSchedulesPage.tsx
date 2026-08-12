import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  activateBusinessScheduleVersion,
  ApiError,
  createBusinessSchedule,
  createBusinessScheduleVersion,
  listBusinessScheduleVersions,
  listBusinessSchedules,
  previewBusinessSchedule,
} from '../api/client'
import type {
  BusinessInterval,
  BusinessSchedule,
  BusinessScheduleDefinition,
  BusinessScheduleException,
  BusinessSchedulePreview,
  BusinessWeekday,
  BusinessWeekdaySchedule,
} from '../api/types'
import { Notification, ScreenState } from '../shared/ui/system'

const DAYS: { key: BusinessWeekday; label: string }[] = [
  { key: 'MONDAY', label: '월요일' },
  { key: 'TUESDAY', label: '화요일' },
  { key: 'WEDNESDAY', label: '수요일' },
  { key: 'THURSDAY', label: '목요일' },
  { key: 'FRIDAY', label: '금요일' },
  { key: 'SATURDAY', label: '토요일' },
  { key: 'SUNDAY', label: '일요일' },
]

const DEFAULT_INTERVAL = { start: '09:00', end: '18:00' }

function emptyDefinition(): BusinessScheduleDefinition {
  return {
    name: '새 업무 시간 일정',
    timeZone: 'Asia/Seoul',
    weekdays: DAYS.map(({ key }, index) => ({
      weekday: key,
      enabled: index < 5,
      intervals: index < 5 ? [{ ...DEFAULT_INTERVAL }] : [],
    })),
    exceptions: [],
  }
}

function copyDefinition(
  schedule: BusinessSchedule,
): BusinessScheduleDefinition {
  return {
    name: schedule.name,
    timeZone: schedule.timeZone,
    weekdays: schedule.weekdays.map((day) => ({
      ...day,
      intervals: day.intervals.map((interval) => ({ ...interval })),
    })),
    exceptions: schedule.exceptions.map((exception) => ({
      ...exception,
      intervals: exception.intervals.map((interval) => ({ ...interval })),
    })),
  }
}

function scheduleError(error: unknown) {
  if (!(error instanceof ApiError)) return '일정 요청을 처리할 수 없습니다.'
  if (error.status === 403)
    return '업무 시간 일정은 관리자만 변경할 수 있습니다.'
  if (error.status === 412)
    return '다른 관리자가 일정을 변경했습니다. 최신 버전을 다시 불러왔습니다.'
  if (error.status === 409) return '같은 이름의 일정이 이미 있습니다.'
  const fields = error.problem?.fieldErrors
    ?.map((field) => `${field.field}: ${field.message}`)
    .join(' · ')
  return (
    fields ||
    `${error.message}${error.requestId ? ` 요청 ID: ${error.requestId}` : ''}`
  )
}

function dateTime(value: string | null, timeZone: string) {
  if (!value) return '다음 영업 시간 없음'
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone,
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(value))
}

export function AdminBusinessSchedulesPage() {
  const [schedules, setSchedules] = useState<BusinessSchedule[]>([])
  const [versions, setVersions] = useState<BusinessSchedule[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [selectedVersion, setSelectedVersion] = useState(0)
  const [draft, setDraft] =
    useState<BusinessScheduleDefinition>(emptyDefinition)
  const [creating, setCreating] = useState(false)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [preview, setPreview] = useState<BusinessSchedulePreview | null>(null)
  const [previewStart, setPreviewStart] = useState('2026-08-14T17:00')
  const [previewEnd, setPreviewEnd] = useState('2026-08-17T10:00')
  const [previewMinutes, setPreviewMinutes] = useState(120)
  const errorRef = useRef<HTMLDivElement>(null)

  const selectedSchedule = schedules.find((item) => item.id === selectedId)
  const editedVersion = versions.find(
    (item) => item.version === selectedVersion,
  )
  const aggregateVersion = selectedSchedule?.aggregateVersion ?? 0

  const load = useCallback(
    async (preferredId?: string, preferredVersion?: number) => {
      setLoading(true)
      try {
        const nextSchedules = await listBusinessSchedules()
        setSchedules(nextSchedules)
        const id =
          preferredId && nextSchedules.some((item) => item.id === preferredId)
            ? preferredId
            : nextSchedules[0]?.id || ''
        setSelectedId(id)
        if (!id) {
          setVersions([])
          setCreating(false)
          return
        }
        const nextVersions = await listBusinessScheduleVersions(id)
        setVersions(nextVersions)
        const version =
          preferredVersion &&
          nextVersions.some((item) => item.version === preferredVersion)
            ? preferredVersion
            : nextVersions[0]?.version || 0
        setSelectedVersion(version)
        const selected = nextVersions.find((item) => item.version === version)
        if (selected) setDraft(copyDefinition(selected))
        setCreating(false)
      } catch (caught) {
        setError(scheduleError(caught))
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

  async function selectSchedule(id: string) {
    setError(null)
    setNotice(null)
    setSelectedId(id)
    setLoading(true)
    try {
      const nextVersions = await listBusinessScheduleVersions(id)
      setVersions(nextVersions)
      const latest = nextVersions[0]
      setSelectedVersion(latest?.version ?? 0)
      if (latest) setDraft(copyDefinition(latest))
      setCreating(false)
    } catch (caught) {
      setError(scheduleError(caught))
    } finally {
      setLoading(false)
    }
  }

  function updateWeekday(
    weekday: BusinessWeekday,
    update: (current: BusinessWeekdaySchedule) => BusinessWeekdaySchedule,
  ) {
    setDraft((current) => ({
      ...current,
      weekdays: current.weekdays.map((day) =>
        day.weekday === weekday ? update(day) : day,
      ),
    }))
    setPreview(null)
  }

  function updateException(
    index: number,
    update: (current: BusinessScheduleException) => BusinessScheduleException,
  ) {
    setDraft((current) => ({
      ...current,
      exceptions: current.exceptions.map((item, itemIndex) =>
        itemIndex === index ? update(item) : item,
      ),
    }))
    setPreview(null)
  }

  async function runPreview() {
    setBusy(true)
    setError(null)
    try {
      setPreview(
        await previewBusinessSchedule({
          schedule: draft,
          startAt: new Date(previewStart).toISOString(),
          endAt: new Date(previewEnd).toISOString(),
          businessMinutes: previewMinutes,
        }),
      )
    } catch (caught) {
      setError(scheduleError(caught))
    } finally {
      setBusy(false)
    }
  }

  async function save() {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const saved = creating
        ? await createBusinessSchedule(draft)
        : await createBusinessScheduleVersion(
            selectedId,
            aggregateVersion,
            draft,
          )
      await load(saved.id, saved.version)
      setNotice(
        creating
          ? `일정 ${saved.name}이 생성되었습니다.`
          : `버전 ${saved.version}가 저장되었습니다.`,
      )
    } catch (caught) {
      setError(scheduleError(caught))
      if (caught instanceof ApiError && caught.status === 412)
        await load(selectedId)
    } finally {
      setBusy(false)
    }
  }

  async function activate(version: number) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const activated = await activateBusinessScheduleVersion(
        selectedId,
        version,
        aggregateVersion,
      )
      await load(selectedId, version)
      setNotice(`버전 ${activated.version}가 활성화되었습니다.`)
    } catch (caught) {
      setError(scheduleError(caught))
      if (caught instanceof ApiError && caught.status === 412)
        await load(selectedId)
    } finally {
      setBusy(false)
    }
  }

  const localPreviewZone = useMemo(
    () => draft.timeZone || 'UTC',
    [draft.timeZone],
  )

  return (
    <section
      aria-labelledby="business-schedules-title"
      className="schedule-admin"
    >
      <div className="admin-title-row">
        <div>
          <p className="eyebrow">BUSINESS RULES</p>
          <h1 id="business-schedules-title">업무 시간 일정</h1>
          <p>
            영업 시간을 버전으로 보존하고 활성화 전에 실제 마감 시각을
            확인합니다.
          </p>
        </div>
        <button
          className="button secondary"
          type="button"
          onClick={() => {
            setCreating(true)
            setSelectedId('')
            setSelectedVersion(0)
            setDraft(emptyDefinition())
            setVersions([])
            setNotice(null)
          }}
        >
          새 일정
        </button>
      </div>

      {error ? (
        <Notification
          ref={errorRef}
          tabIndex={-1}
          tone="danger"
          title={error}
        />
      ) : null}
      {notice ? <Notification tone="success" title={notice} /> : null}

      <div className="schedule-layout">
        <aside
          className="admin-panel schedule-list"
          aria-label="업무 시간 일정 목록"
        >
          <h2>일정</h2>
          {loading && schedules.length === 0 ? (
            <ScreenState
              kind="loading"
              compact
              title="일정을 불러오는 중입니다."
            />
          ) : null}
          {!loading && schedules.length === 0 && !creating ? (
            <ScreenState kind="empty" compact title="아직 일정이 없습니다." />
          ) : null}
          <ul>
            {schedules.map((schedule) => (
              <li key={schedule.id}>
                <button
                  className={
                    schedule.id === selectedId
                      ? 'schedule-select active'
                      : 'schedule-select'
                  }
                  type="button"
                  onClick={() => void selectSchedule(schedule.id)}
                >
                  <strong>{schedule.name}</strong>
                  <span>{schedule.timeZone}</span>
                  <small>
                    v{schedule.version} ·{' '}
                    {schedule.active ? '활성' : '활성 버전 없음'}
                  </small>
                </button>
              </li>
            ))}
          </ul>
        </aside>

        <div className="schedule-workspace">
          <section
            className="admin-panel"
            aria-labelledby="schedule-definition-title"
          >
            <div className="schedule-section-heading">
              <div>
                <h2 id="schedule-definition-title">
                  {creating ? '새 일정' : `버전 ${selectedVersion} 편집`}
                </h2>
                {!creating && editedVersion ? (
                  <p>
                    현재 버전은 수정되지 않으며 저장 시 새 버전이 만들어집니다.
                  </p>
                ) : null}
              </div>
              {!creating && editedVersion?.active ? (
                <span className="schedule-active-badge">활성 버전</span>
              ) : null}
            </div>
            <div className="schedule-basics">
              <label>
                일정 이름
                <input
                  maxLength={100}
                  required
                  value={draft.name}
                  onChange={(event) =>
                    setDraft((current) => ({
                      ...current,
                      name: event.target.value,
                    }))
                  }
                />
              </label>
              <label>
                IANA 시간대
                <input
                  list="business-timezones"
                  value={draft.timeZone}
                  onChange={(event) =>
                    setDraft((current) => ({
                      ...current,
                      timeZone: event.target.value,
                    }))
                  }
                />
                <datalist id="business-timezones">
                  <option value="Asia/Seoul" />
                  <option value="America/New_York" />
                  <option value="Europe/London" />
                  <option value="Australia/Sydney" />
                </datalist>
              </label>
            </div>

            <div className="weekday-editor">
              {DAYS.map(({ key, label }) => {
                const day = draft.weekdays.find((item) => item.weekday === key)!
                return (
                  <section
                    className="weekday-row"
                    key={key}
                    aria-label={`${label} 영업 시간`}
                  >
                    <label className="weekday-toggle">
                      <input
                        type="checkbox"
                        checked={day.enabled}
                        aria-label={`${label} 영업`}
                        onChange={(event) =>
                          updateWeekday(key, (current) => ({
                            ...current,
                            enabled: event.target.checked,
                            intervals: event.target.checked
                              ? current.intervals.length
                                ? current.intervals
                                : [{ ...DEFAULT_INTERVAL }]
                              : [],
                          }))
                        }
                      />
                      <strong>{label}</strong>
                    </label>
                    <IntervalEditor
                      label={label}
                      intervals={day.intervals}
                      disabled={!day.enabled}
                      onChange={(intervals) =>
                        updateWeekday(key, (current) => ({
                          ...current,
                          intervals,
                        }))
                      }
                    />
                  </section>
                )
              })}
            </div>

            <div className="schedule-subsection-heading">
              <div>
                <h3>휴일과 날짜 예외</h3>
                <p>예외가 있는 날짜는 주간 영업 시간을 완전히 대체합니다.</p>
              </div>
              <button
                className="button secondary small"
                type="button"
                onClick={() =>
                  setDraft((current) => ({
                    ...current,
                    exceptions: [
                      ...current.exceptions,
                      { date: '', mode: 'CLOSED', intervals: [], label: '' },
                    ],
                  }))
                }
              >
                예외 추가
              </button>
            </div>
            {draft.exceptions.length === 0 ? (
              <p className="schedule-empty-copy">
                등록된 휴일이나 예외가 없습니다.
              </p>
            ) : null}
            <div className="exception-list">
              {draft.exceptions.map((exception, index) => (
                <section
                  className="exception-row"
                  key={`${exception.date}-${index}`}
                >
                  <label>
                    날짜
                    <input
                      type="date"
                      value={exception.date}
                      onChange={(event) =>
                        updateException(index, (current) => ({
                          ...current,
                          date: event.target.value,
                        }))
                      }
                    />
                  </label>
                  <label>
                    운영 방식
                    <select
                      value={exception.mode}
                      onChange={(event) =>
                        updateException(index, (current) => ({
                          ...current,
                          mode: event.target.value as 'CLOSED' | 'OPEN',
                          intervals:
                            event.target.value === 'OPEN'
                              ? current.intervals.length
                                ? current.intervals
                                : [{ ...DEFAULT_INTERVAL }]
                              : [],
                        }))
                      }
                    >
                      <option value="CLOSED">휴무</option>
                      <option value="OPEN">특별 영업</option>
                    </select>
                  </label>
                  <label>
                    설명
                    <input
                      maxLength={200}
                      value={exception.label ?? ''}
                      onChange={(event) =>
                        updateException(index, (current) => ({
                          ...current,
                          label: event.target.value,
                        }))
                      }
                    />
                  </label>
                  <button
                    className="text-button danger"
                    type="button"
                    onClick={() =>
                      setDraft((current) => ({
                        ...current,
                        exceptions: current.exceptions.filter(
                          (_, itemIndex) => itemIndex !== index,
                        ),
                      }))
                    }
                  >
                    예외 삭제
                  </button>
                  {exception.mode === 'OPEN' ? (
                    <IntervalEditor
                      label={`${exception.date || '예외'} 특별 영업`}
                      intervals={exception.intervals}
                      onChange={(intervals) =>
                        updateException(index, (current) => ({
                          ...current,
                          intervals,
                        }))
                      }
                    />
                  ) : null}
                </section>
              ))}
            </div>
            <div className="schedule-actions">
              <button
                className="button primary"
                type="button"
                disabled={busy}
                onClick={() => void save()}
              >
                {creating ? '일정 만들기' : '새 버전 저장'}
              </button>
            </div>
          </section>

          <section
            className="admin-panel preview-panel"
            aria-labelledby="schedule-preview-title"
          >
            <div className="schedule-section-heading">
              <div>
                <h2 id="schedule-preview-title">미저장 일정 미리보기</h2>
                <p>저장 전 초안에 동일한 결정론적 계산기를 적용합니다.</p>
              </div>
              <span className="dst-policy">
                DST: gap 전진 · overlap 양쪽 포함
              </span>
            </div>
            <div className="preview-controls">
              <label>
                시작 시각
                <input
                  type="datetime-local"
                  value={previewStart}
                  onChange={(event) => setPreviewStart(event.target.value)}
                />
              </label>
              <label>
                종료 시각
                <input
                  type="datetime-local"
                  value={previewEnd}
                  onChange={(event) => setPreviewEnd(event.target.value)}
                />
              </label>
              <label>
                더할 업무 분
                <input
                  min={0}
                  max={525600}
                  type="number"
                  value={previewMinutes}
                  onChange={(event) =>
                    setPreviewMinutes(Number(event.target.value))
                  }
                />
              </label>
              <button
                className="button secondary"
                type="button"
                disabled={busy}
                onClick={() => void runPreview()}
              >
                미저장 일정 미리보기
              </button>
            </div>
            {preview ? (
              <dl className="preview-results" aria-live="polite">
                <div>
                  <dt>계산된 마감</dt>
                  <dd>{dateTime(preview.dueAt, localPreviewZone)}</dd>
                </div>
                <div>
                  <dt>구간 내 업무 시간</dt>
                  <dd>{preview.elapsedBusinessMinutes}분</dd>
                </div>
                <div>
                  <dt>다음 영업 시작</dt>
                  <dd>{dateTime(preview.nextOpenAt, localPreviewZone)}</dd>
                </div>
                <div>
                  <dt>다음 영업 종료</dt>
                  <dd>{dateTime(preview.nextCloseAt, localPreviewZone)}</dd>
                </div>
              </dl>
            ) : null}
          </section>

          {!creating ? (
            <section
              className="admin-panel"
              aria-labelledby="schedule-versions-title"
            >
              <h2 id="schedule-versions-title">버전 기록</h2>
              <ol className="version-list">
                {versions.map((version) => (
                  <li key={version.version}>
                    <button
                      className={
                        version.version === selectedVersion
                          ? 'version-select active'
                          : 'version-select'
                      }
                      type="button"
                      onClick={() => {
                        setSelectedVersion(version.version)
                        setDraft(copyDefinition(version))
                      }}
                    >
                      <strong>버전 {version.version}</strong>
                      <span>
                        {version.createdBy.displayName} ·{' '}
                        {dateTime(version.createdAt, version.timeZone)}
                      </span>
                    </button>
                    {version.active ? (
                      <span className="schedule-active-badge">활성</span>
                    ) : (
                      <button
                        className="button secondary small"
                        type="button"
                        disabled={busy}
                        aria-label={`버전 ${version.version} 활성화`}
                        onClick={() => void activate(version.version)}
                      >
                        활성화
                      </button>
                    )}
                  </li>
                ))}
              </ol>
            </section>
          ) : null}
        </div>
      </div>
    </section>
  )
}

function IntervalEditor({
  label,
  intervals,
  disabled = false,
  onChange,
}: {
  label: string
  intervals: BusinessInterval[]
  disabled?: boolean
  onChange: (intervals: BusinessInterval[]) => void
}) {
  return (
    <div className="interval-editor">
      {intervals.map((interval, index) => (
        <div className="interval-row" key={index}>
          <label>
            <span className="visually-hidden">
              {label} 구간 {index + 1} 시작
            </span>
            <input
              aria-label={`${label} 구간 ${index + 1} 시작`}
              type="time"
              step={60}
              value={interval.start}
              onChange={(event) =>
                onChange(
                  intervals.map((item, itemIndex) =>
                    itemIndex === index
                      ? { ...item, start: event.target.value }
                      : item,
                  ),
                )
              }
            />
          </label>
          <span aria-hidden="true">–</span>
          <label>
            <span className="visually-hidden">
              {label} 구간 {index + 1} 종료
            </span>
            <input
              aria-label={`${label} 구간 ${index + 1} 종료`}
              type="time"
              step={60}
              value={interval.end}
              onChange={(event) =>
                onChange(
                  intervals.map((item, itemIndex) =>
                    itemIndex === index
                      ? { ...item, end: event.target.value }
                      : item,
                  ),
                )
              }
            />
          </label>
          {intervals.length > 1 ? (
            <button
              className="text-button danger"
              type="button"
              aria-label={`${label} 구간 ${index + 1} 삭제`}
              onClick={() =>
                onChange(
                  intervals.filter((_, itemIndex) => itemIndex !== index),
                )
              }
            >
              삭제
            </button>
          ) : null}
        </div>
      ))}
      <button
        className="text-button"
        type="button"
        disabled={disabled || intervals.length >= 12}
        aria-label={`${label} 시간 구간 추가`}
        onClick={() => onChange([...intervals, { ...DEFAULT_INTERVAL }])}
      >
        + 시간 구간
      </button>
    </div>
  )
}
