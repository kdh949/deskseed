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
  activateBusinessScheduleVersion,
  createBusinessSchedule,
  createBusinessScheduleVersion,
  listBusinessSchedules,
  listBusinessScheduleVersions,
  previewBusinessSchedule,
} from '../../api/client'
import type {
  BusinessInterval,
  BusinessSchedule,
  BusinessScheduleDefinition,
  BusinessScheduleException,
  BusinessSchedulePreview,
  BusinessSchedulePreviewInput,
  BusinessWeekday,
  BusinessWeekdaySchedule,
} from '../../api/types'
import {
  DsButton,
  Notification,
  RetryButton,
  ScreenState,
} from '../../design-system'
import { recoverAmbiguousAdminMutationOutcome } from './adminMutationRecovery'

const WEEKDAYS: Array<{ label: string; value: BusinessWeekday }> = [
  { value: 'MONDAY', label: '월요일' },
  { value: 'TUESDAY', label: '화요일' },
  { value: 'WEDNESDAY', label: '수요일' },
  { value: 'THURSDAY', label: '목요일' },
  { value: 'FRIDAY', label: '금요일' },
  { value: 'SATURDAY', label: '토요일' },
  { value: 'SUNDAY', label: '일요일' },
]

function blankWeekdays(): BusinessWeekdaySchedule[] {
  return WEEKDAYS.map(({ value }) => ({
    weekday: value,
    enabled: false,
    intervals: [],
  }))
}

function blankDefinition(): BusinessScheduleDefinition {
  return {
    name: '',
    timeZone: 'Asia/Seoul',
    weekdays: blankWeekdays(),
    exceptions: [],
  }
}

function copyDefinition(
  schedule: BusinessSchedule,
): BusinessScheduleDefinition {
  return {
    name: schedule.name,
    timeZone: schedule.timeZone,
    weekdays: schedule.weekdays.map((weekday) => ({
      ...weekday,
      intervals: weekday.intervals.map((interval) => ({ ...interval })),
    })),
    exceptions: schedule.exceptions.map((exception) => ({
      ...exception,
      intervals: exception.intervals.map((interval) => ({ ...interval })),
    })),
  }
}

function blankException(): BusinessScheduleException {
  return { date: '', mode: 'CLOSED', intervals: [], label: null }
}

export function AdminBusinessSchedulesPage() {
  const queryClient = useQueryClient()
  const [selectedSchedule, setSelectedSchedule] =
    useState<BusinessSchedule | null>(null)
  const [selectedVersionNumber, setSelectedVersionNumber] = useState<
    number | null
  >(null)
  const [editorOpen, setEditorOpen] = useState(false)
  const [draft, setDraft] =
    useState<BusinessScheduleDefinition>(blankDefinition)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saveOutcomeUnknown, setSaveOutcomeUnknown] = useState(false)
  const [previewStart, setPreviewStart] = useState('')
  const [previewEnd, setPreviewEnd] = useState('')
  const [previewMinutes, setPreviewMinutes] = useState('')
  const [activationOpen, setActivationOpen] = useState(false)

  const schedulesQuery = useQuery({
    queryKey: ['admin-business-schedules'],
    queryFn: listBusinessSchedules,
    retry: false,
  })
  const versionsQuery = useQuery({
    queryKey: ['admin-business-schedule-versions', selectedSchedule?.id],
    queryFn: () => listBusinessScheduleVersions(selectedSchedule!.id),
    enabled: selectedSchedule !== null,
    retry: false,
  })
  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['admin-business-schedules'] }),
      queryClient.invalidateQueries({
        queryKey: ['admin-business-schedule-versions'],
      }),
    ])
  }
  const saveMutation = useMutation({
    mutationFn: ({
      editing,
      definition,
    }: {
      definition: BusinessScheduleDefinition
      editing: BusinessSchedule | null
    }) =>
      editing
        ? createBusinessScheduleVersion(
            editing.id,
            editing.aggregateVersion,
            definition,
          )
        : createBusinessSchedule(definition),
    onSuccess: async (schedule) => {
      setSelectedSchedule(schedule)
      setSelectedVersionNumber(schedule.version)
      setDraft(copyDefinition(schedule))
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
  const previewMutation = useMutation({ mutationFn: previewBusinessSchedule })
  const activateMutation = useMutation({
    mutationFn: ({
      schedule,
      version,
    }: {
      schedule: BusinessSchedule
      version: number
    }) =>
      activateBusinessScheduleVersion(
        schedule.id,
        version,
        schedule.aggregateVersion,
      ),
    onSuccess: async (schedule) => {
      setSelectedSchedule(schedule)
      setSelectedVersionNumber(schedule.version)
      setActivationOpen(false)
      await refresh()
    },
  })

  useEffect(() => {
    if (!selectedSchedule) return
    const current = schedulesQuery.data?.find(
      (schedule) => schedule.id === selectedSchedule.id,
    )
    if (current) setSelectedSchedule(current)
  }, [schedulesQuery.data, selectedSchedule])

  const selectedVersion = useMemo(() => {
    const versions = versionsQuery.data ?? []
    if (selectedVersionNumber !== null) {
      return (
        versions.find((version) => version.version === selectedVersionNumber) ??
        null
      )
    }
    return selectedSchedule
      ? (versions.find(
          (version) => version.version === selectedSchedule.version,
        ) ?? null)
      : null
  }, [selectedSchedule, selectedVersionNumber, versionsQuery.data])

  if (schedulesQuery.isPending) {
    return (
      <AdminScheduleScreenState
        kind="loading"
        title="영업 시간표를 불러오는 중"
      />
    )
  }
  if (schedulesQuery.isError) {
    const denied =
      schedulesQuery.error instanceof ApiError &&
      schedulesQuery.error.status === 403
    return (
      <AdminScheduleScreenState
        action={
          denied ? undefined : (
            <RetryButton onClick={() => void schedulesQuery.refetch()} />
          )
        }
        description={
          denied
            ? '영업 시간표는 ADMIN만 관리할 수 있습니다.'
            : '잠시 후 영업 시간표를 다시 요청해 주세요.'
        }
        kind={denied ? 'denied' : 'error'}
        title={
          denied
            ? '영업 시간표 관리 권한이 없습니다.'
            : '영업 시간표를 불러오지 못했습니다.'
        }
      />
    )
  }

  const submitSchedule = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (saveOutcomeUnknown) return
    if (!draft.name.trim() || !draft.timeZone.trim()) {
      setSaveError('시간표 이름과 IANA timezone을 입력해 주세요.')
      return
    }
    if (
      draft.weekdays.some(
        (weekday) =>
          weekday.enabled &&
          weekday.intervals.some(
            (interval) => !interval.start || !interval.end,
          ),
      )
    ) {
      setSaveError(
        '활성 평일의 모든 시간 구간에 시작/종료 시각을 입력해 주세요.',
      )
      return
    }
    if (draft.exceptions.some((exception) => !exception.date)) {
      setSaveError('예외 일정의 날짜를 입력해 주세요.')
      return
    }
    setSaveError(null)
    saveMutation.mutate({
      definition: normalizeDefinition(draft),
      editing: selectedSchedule,
    })
  }

  const openNewSchedule = () => {
    if (saveOutcomeUnknown) return
    setSelectedSchedule(null)
    setSelectedVersionNumber(null)
    setDraft(blankDefinition())
    setEditorOpen(true)
    setSaveError(null)
    setSaveOutcomeUnknown(false)
    saveMutation.reset()
    previewMutation.reset()
  }
  const openNewVersion = () => {
    if (!selectedVersion) return
    if (saveOutcomeUnknown) return
    setDraft(copyDefinition(selectedVersion))
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
    <main aria-label="영업 시간표 관리" className="admin-page">
      <header className="admin-page-header">
        <div>
          <h1>영업 시간표</h1>
          <p>
            timezone, 평일 다중 시간 구간, 휴일 및 예외 일정을 immutable
            version으로 관리합니다.
          </p>
        </div>
        <div className="admin-inline-actions">
          <DsButton
            disabled={saveOutcomeUnknown}
            onClick={openNewSchedule}
            tone="primary"
          >
            새 영업 시간표
          </DsButton>
          <DsButton
            onClick={() => void schedulesQuery.refetch()}
            tone="secondary"
          >
            시간표 새로고침
          </DsButton>
        </div>
      </header>

      <section
        aria-labelledby="schedule-list-heading"
        className="admin-surface"
      >
        <h2 id="schedule-list-heading">영업 시간표 목록</h2>
        {schedulesQuery.data.length === 0 ? (
          <ScreenState
            compact
            description="새 시간표를 작성한 뒤 검토하고 활성화할 수 있습니다."
            kind="empty"
            title="등록된 영업 시간표가 없습니다."
          />
        ) : (
          <div className="admin-table-wrap">
            <table className="admin-table">
              <caption className="sr-only">영업 시간표 목록</caption>
              <thead>
                <tr>
                  <th scope="col">이름</th>
                  <th scope="col">timezone</th>
                  <th scope="col">최신 version</th>
                  <th scope="col">활성 version</th>
                  <th scope="col">상태</th>
                  <th scope="col">작업</th>
                </tr>
              </thead>
              <tbody>
                {schedulesQuery.data.map((schedule) => (
                  <tr key={schedule.id}>
                    <td>{schedule.name}</td>
                    <td>{schedule.timeZone}</td>
                    <td>{schedule.version}</td>
                    <td>{schedule.activeVersion ?? '—'}</td>
                    <td>{schedule.active ? '활성' : '비활성'}</td>
                    <td>
                      <DsButton
                        aria-expanded={selectedSchedule?.id === schedule.id}
                        onClick={() => {
                          setSelectedSchedule(schedule)
                          setSelectedVersionNumber(schedule.version)
                          closeEditor()
                          setActivationOpen(false)
                        }}
                        tone="secondary"
                      >
                        시간표 관리
                      </DsButton>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {selectedSchedule ? (
        <section
          aria-labelledby="schedule-detail-heading"
          className="admin-surface"
        >
          <div className="admin-page-header">
            <div>
              <h2 id="schedule-detail-heading">{selectedSchedule.name}</h2>
              <p>{`${selectedSchedule.timeZone} · aggregate version ${selectedSchedule.aggregateVersion}`}</p>
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
                onClick={() => setSelectedSchedule(null)}
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
              title="시간표 version 이력을 불러오는 중"
            />
          ) : versionsQuery.isError ? (
            <Notification
              title="시간표 version 이력을 불러오지 못했습니다."
              tone="danger"
            >
              <p>새로고침한 뒤 다시 시도해 주세요.</p>
            </Notification>
          ) : (
            <>
              <ul
                className="admin-version-list"
                aria-label="영업 시간표 version 이력"
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
                <ScheduleReadModel
                  onActivate={() => setActivationOpen(true)}
                  schedule={selectedVersion}
                  showActivate={
                    selectedSchedule.activeVersion !== selectedVersion.version
                  }
                />
              ) : null}
              {activationOpen && selectedVersion ? (
                <div
                  className="admin-confirmation"
                  role="group"
                  aria-label="영업 시간표 version 활성화 최종 확인"
                >
                  <p>{`version ${selectedVersion.version}을(를) 활성화할까요? 기존 SLA target의 시간표 snapshot은 변경하지 않습니다.`}</p>
                  <DsButton
                    disabled={activateMutation.isPending}
                    onClick={() =>
                      activateMutation.mutate({
                        schedule: selectedSchedule,
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
                    <ScheduleMutationNotification
                      action="시간표 version을 활성화"
                      error={activateMutation.error}
                    />
                  ) : null}
                  {activateMutation.isSuccess ? (
                    <Notification
                      title="시간표 version을 활성화했습니다."
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
          aria-labelledby="schedule-editor-heading"
          className="admin-surface"
        >
          <div className="admin-page-header">
            <div>
              <h2 id="schedule-editor-heading">
                {selectedSchedule
                  ? `${selectedSchedule.name} 새 version`
                  : '새 영업 시간표'}
              </h2>
              <p>저장 전 미리보기는 입력을 저장하거나 활성화하지 않습니다.</p>
            </div>
            <DsButton onClick={closeEditor} tone="secondary">
              작성 닫기
            </DsButton>
          </div>
          <form className="admin-form" onSubmit={submitSchedule}>
            <div className="admin-form-grid">
              <label className="admin-field" htmlFor="schedule-name">
                <span>시간표 이름</span>
                <input
                  id="schedule-name"
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
              <label className="admin-field" htmlFor="schedule-timezone">
                <span>IANA timezone</span>
                <input
                  id="schedule-timezone"
                  maxLength={100}
                  onChange={(event) =>
                    setDraft((current) => ({
                      ...current,
                      timeZone: event.target.value,
                    }))
                  }
                  value={draft.timeZone}
                />
              </label>
            </div>
            <fieldset className="admin-form">
              <legend>주간 영업 시간</legend>
              <div className="admin-weekday-list">
                {draft.weekdays.map((weekday) => (
                  <WeekdayEditor
                    key={weekday.weekday}
                    onChange={(next) =>
                      setDraft((current) => ({
                        ...current,
                        weekdays: current.weekdays.map((item) =>
                          item.weekday === next.weekday ? next : item,
                        ),
                      }))
                    }
                    weekday={weekday}
                  />
                ))}
              </div>
            </fieldset>
            <fieldset className="admin-form">
              <legend>예외 일정</legend>
              {draft.exceptions.length === 0 ? (
                <p className="admin-muted">등록된 예외 일정이 없습니다.</p>
              ) : (
                <div className="admin-weekday-list">
                  {draft.exceptions.map((exception, index) => (
                    <ExceptionEditor
                      exception={exception}
                      key={`${exception.date}-${index}`}
                      onChange={(next) =>
                        setDraft((current) => ({
                          ...current,
                          exceptions: current.exceptions.map(
                            (item, itemIndex) =>
                              itemIndex === index ? next : item,
                          ),
                        }))
                      }
                      onRemove={() =>
                        setDraft((current) => ({
                          ...current,
                          exceptions: current.exceptions.filter(
                            (_, itemIndex) => itemIndex !== index,
                          ),
                        }))
                      }
                    />
                  ))}
                </div>
              )}
              <DsButton
                onClick={() =>
                  setDraft((current) => ({
                    ...current,
                    exceptions: [...current.exceptions, blankException()],
                  }))
                }
                tone="secondary"
                type="button"
              >
                예외 일정 추가
              </DsButton>
            </fieldset>
            {saveError ? (
              <Notification title={saveError} tone="warning" />
            ) : null}
            {saveOutcomeUnknown ? (
              <Notification
                title="시간표 저장 결과를 확인할 수 없습니다."
                tone="warning"
              >
                <p>
                  서버 응답이 유실되었을 수 있어 같은 요청을 다시 제출하지
                  않습니다. 시간표 목록과 version 이력을 다시 읽었습니다. 서버
                  상태를 확인한 뒤 이 작성 화면을 닫고 다음 작업을 선택해
                  주세요.
                </p>
              </Notification>
            ) : saveMutation.isError ? (
              <ScheduleMutationNotification
                action="시간표를 저장"
                error={saveMutation.error}
              />
            ) : null}
            {saveMutation.isSuccess ? (
              <Notification
                title="시간표 version을 저장했습니다."
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
                  ? '시간표 저장 중…'
                  : selectedSchedule
                    ? '새 version 저장'
                    : '시간표 생성'}
              </DsButton>
            </div>
          </form>

          <SchedulePreview
            draft={draft}
            end={previewEnd}
            mutation={previewMutation}
            onEndChange={setPreviewEnd}
            onMinutesChange={setPreviewMinutes}
            onStartChange={setPreviewStart}
            minutes={previewMinutes}
            start={previewStart}
          />
        </section>
      ) : null}
    </main>
  )
}

function WeekdayEditor({
  onChange,
  weekday,
}: {
  onChange: (next: BusinessWeekdaySchedule) => void
  weekday: BusinessWeekdaySchedule
}) {
  const label =
    WEEKDAYS.find((item) => item.value === weekday.weekday)?.label ??
    weekday.weekday
  return (
    <div className="admin-weekday-row">
      <strong>{label}</strong>
      <label>
        <input
          checked={weekday.enabled}
          onChange={(event) =>
            onChange({
              ...weekday,
              enabled: event.target.checked,
              intervals:
                event.target.checked && weekday.intervals.length === 0
                  ? [{ start: '09:00', end: '18:00' }]
                  : weekday.intervals,
            })
          }
          type="checkbox"
        />
        영업
      </label>
      {weekday.enabled ? (
        <IntervalsEditor
          intervals={weekday.intervals}
          onChange={(intervals) => onChange({ ...weekday, intervals })}
        />
      ) : null}
    </div>
  )
}

function ExceptionEditor({
  exception,
  onChange,
  onRemove,
}: {
  exception: BusinessScheduleException
  onChange: (next: BusinessScheduleException) => void
  onRemove: () => void
}) {
  return (
    <div className="admin-weekday-row">
      <label>
        날짜
        <input
          aria-label="예외 일정 날짜"
          onChange={(event) =>
            onChange({ ...exception, date: event.target.value })
          }
          type="date"
          value={exception.date}
        />
      </label>
      <label>
        방식
        <select
          aria-label="예외 일정 방식"
          onChange={(event) =>
            onChange({
              ...exception,
              mode: event.target.value as BusinessScheduleException['mode'],
              intervals:
                event.target.value === 'OPEN' &&
                exception.intervals.length === 0
                  ? [{ start: '09:00', end: '18:00' }]
                  : exception.intervals,
            })
          }
          value={exception.mode}
        >
          <option value="CLOSED">휴무</option>
          <option value="OPEN">예외 영업</option>
        </select>
      </label>
      <label>
        라벨
        <input
          aria-label="예외 일정 라벨"
          maxLength={200}
          onChange={(event) =>
            onChange({ ...exception, label: event.target.value || null })
          }
          value={exception.label ?? ''}
        />
      </label>
      {exception.mode === 'OPEN' ? (
        <IntervalsEditor
          intervals={exception.intervals}
          onChange={(intervals) => onChange({ ...exception, intervals })}
        />
      ) : null}
      <DsButton onClick={onRemove} tone="secondary" type="button">
        예외 일정 제거
      </DsButton>
    </div>
  )
}

function IntervalsEditor({
  intervals,
  onChange,
}: {
  intervals: BusinessInterval[]
  onChange: (intervals: BusinessInterval[]) => void
}) {
  return (
    <>
      {intervals.map((interval, index) => (
        <span
          className="admin-inline-actions"
          key={`${interval.start}-${interval.end}-${index}`}
        >
          <label>
            시작
            <input
              aria-label={`시간 구간 ${index + 1} 시작`}
              onChange={(event) =>
                onChange(
                  intervals.map((item, itemIndex) =>
                    itemIndex === index
                      ? { ...item, start: event.target.value }
                      : item,
                  ),
                )
              }
              type="time"
              value={interval.start}
            />
          </label>
          <label>
            종료
            <input
              aria-label={`시간 구간 ${index + 1} 종료`}
              onChange={(event) =>
                onChange(
                  intervals.map((item, itemIndex) =>
                    itemIndex === index
                      ? { ...item, end: event.target.value }
                      : item,
                  ),
                )
              }
              type="time"
              value={interval.end}
            />
          </label>
          <DsButton
            disabled={intervals.length === 1}
            onClick={() =>
              onChange(intervals.filter((_, itemIndex) => itemIndex !== index))
            }
            tone="secondary"
            type="button"
          >
            시간 구간 제거
          </DsButton>
        </span>
      ))}
      <DsButton
        onClick={() =>
          onChange([...intervals, { start: '09:00', end: '18:00' }])
        }
        tone="secondary"
        type="button"
      >
        시간 구간 추가
      </DsButton>
    </>
  )
}

function ScheduleReadModel({
  onActivate,
  schedule,
  showActivate,
}: {
  onActivate: () => void
  schedule: BusinessSchedule
  showActivate: boolean
}) {
  return (
    <section
      aria-labelledby="schedule-version-review-heading"
      className="admin-surface"
    >
      <div className="admin-page-header">
        <div>
          <h3 id="schedule-version-review-heading">{`version ${schedule.version} 검토`}</h3>
          <p>{`${schedule.timeZone} · ${schedule.active ? '활성' : '비활성'}`}</p>
        </div>
        {showActivate ? (
          <DsButton onClick={onActivate} tone="primary">
            이 version 활성화
          </DsButton>
        ) : null}
      </div>
      <ul className="admin-weekday-list" aria-label="주간 영업 시간">
        {schedule.weekdays.map((weekday) => (
          <li className="admin-weekday-row" key={weekday.weekday}>
            <strong>
              {WEEKDAYS.find((item) => item.value === weekday.weekday)?.label}
            </strong>
            <span>
              {weekday.enabled
                ? weekday.intervals
                    .map((interval) => `${interval.start}–${interval.end}`)
                    .join(', ')
                : '휴무'}
            </span>
          </li>
        ))}
      </ul>
      {schedule.exceptions.length > 0 ? (
        <ul className="admin-weekday-list" aria-label="예외 일정 목록">
          {schedule.exceptions.map((exception) => (
            <li
              className="admin-weekday-row"
              key={`${exception.date}-${exception.label ?? ''}`}
            >
              <strong>{exception.date}</strong>
              <span>
                {exception.mode === 'CLOSED'
                  ? '휴무'
                  : exception.intervals
                      .map((interval) => `${interval.start}–${interval.end}`)
                      .join(', ')}
              </span>
              <span>{exception.label ?? '—'}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  )
}

function SchedulePreview({
  draft,
  end,
  minutes,
  mutation,
  onEndChange,
  onMinutesChange,
  onStartChange,
  start,
}: {
  draft: BusinessScheduleDefinition
  end: string
  minutes: string
  mutation: UseMutationResult<
    BusinessSchedulePreview,
    Error,
    BusinessSchedulePreviewInput,
    unknown
  >
  onEndChange: (value: string) => void
  onMinutesChange: (value: string) => void
  onStartChange: (value: string) => void
  start: string
}) {
  const [validationError, setValidationError] = useState<string | null>(null)
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const parsedMinutes = Number(minutes)
    if (
      !start ||
      !end ||
      !Number.isSafeInteger(parsedMinutes) ||
      parsedMinutes < 0
    ) {
      setValidationError('시작/종료 시각과 0 이상의 영업 분을 입력해 주세요.')
      return
    }
    setValidationError(null)
    mutation.mutate({
      schedule: normalizeDefinition(draft),
      startAt: new Date(start).toISOString(),
      endAt: new Date(end).toISOString(),
      businessMinutes: parsedMinutes,
    })
  }
  return (
    <section
      aria-labelledby="schedule-preview-heading"
      className="admin-surface"
    >
      <h3 id="schedule-preview-heading">저장 전 영업 시간 미리보기</h3>
      <form className="admin-form" onSubmit={submit}>
        <div className="admin-form-grid">
          <label className="admin-field" htmlFor="schedule-preview-start">
            <span>시작 시각</span>
            <input
              id="schedule-preview-start"
              onChange={(event) => onStartChange(event.target.value)}
              type="datetime-local"
              value={start}
            />
          </label>
          <label className="admin-field" htmlFor="schedule-preview-end">
            <span>종료 시각</span>
            <input
              id="schedule-preview-end"
              onChange={(event) => onEndChange(event.target.value)}
              type="datetime-local"
              value={end}
            />
          </label>
          <label className="admin-field" htmlFor="schedule-preview-minutes">
            <span>영업 분</span>
            <input
              id="schedule-preview-minutes"
              min="0"
              onChange={(event) => onMinutesChange(event.target.value)}
              type="number"
              value={minutes}
            />
          </label>
        </div>
        {validationError ? (
          <Notification title={validationError} tone="warning" />
        ) : null}
        {mutation.isError ? (
          <ScheduleMutationNotification
            action="시간표 미리보기를 계산"
            error={mutation.error}
          />
        ) : null}
        {mutation.data ? (
          <Notification title="시간표 미리보기 결과" tone="info">
            <p>{`경과 영업 분 ${mutation.data.elapsedBusinessMinutes}, 예정 시각 ${formatTimestamp(mutation.data.dueAt)}, 다음 영업 시작 ${formatTimestamp(mutation.data.nextOpenAt)}`}</p>
          </Notification>
        ) : null}
        <div className="admin-form-actions">
          <DsButton
            disabled={mutation.isPending}
            tone="secondary"
            type="submit"
          >
            {mutation.isPending ? '미리보기 계산 중…' : '미리보기 계산'}
          </DsButton>
        </div>
      </form>
    </section>
  )
}

function normalizeDefinition(
  definition: BusinessScheduleDefinition,
): BusinessScheduleDefinition {
  return {
    name: definition.name.trim(),
    timeZone: definition.timeZone.trim(),
    weekdays: definition.weekdays.map((weekday) => ({
      ...weekday,
      intervals: weekday.intervals.map((interval) => ({ ...interval })),
    })),
    exceptions: definition.exceptions.map((exception) => ({
      ...exception,
      label: exception.label?.trim() || null,
      intervals: exception.intervals.map((interval) => ({ ...interval })),
    })),
  }
}

function ScheduleMutationNotification({
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
          ? '작성 중인 시간표는 보존했습니다. 최신 version을 새로고침한 뒤 다시 검토해 주세요.'
          : '작성 중인 시간표는 보존했습니다. 서버 검증 오류를 확인한 뒤 다시 시도해 주세요.'}
      </p>
    </Notification>
  )
}

function AdminScheduleScreenState({
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
