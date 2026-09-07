import { useRef, useState, type RefObject } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import {
  SeedButton,
  SeedDrawer,
  SeedFeedbackState,
  SeedNotice,
  SeedSkeletonRows,
  SeedTextField,
} from '../../design-system/canonical'
import {
  activateAutomation,
  automationHistory,
  automationVersion,
  deactivateAutomation,
  listAutomations,
  previewAutomation,
  saveAutomation,
  type Automation,
  type AutomationDraft,
  type AutomationPreview,
} from './automation-api'
import './rules.css'

const draftOf = (rule: Automation): AutomationDraft => ({
  name: rule.name,
  solvedAgeMinutes: rule.solvedAgeMinutes,
  actionType: 'CLOSE_TICKET',
})
const when = (value?: string | null) =>
  value ? new Date(value).toLocaleString('ko-KR') : '해당 없음'
const stateLabels: Record<string, string> = {
  PENDING: '대기',
  LEASED: '처리 중',
  SUCCEEDED: '완료',
  SKIPPED: '건너뜀',
  RETRY_SCHEDULED: '재시도 대기',
  DEAD_LETTERED: '재시도 종료',
  CLOSED: '티켓 종료',
  SKIPPED_STATE_CHANGED: '티켓 상태 변경으로 건너뜀',
  FAILED: '실패',
  SOLVED: '해결',
  OPEN: '열림',
  NEW: '신규',
  PENDING_CUSTOMER: '고객 대기',
  ON_HOLD: '보류',
}
function Failure({ error }: { error: unknown }) {
  if (!error) return null
  const status = error instanceof ApiError ? error.status : 0
  return (
    <SeedNotice
      tone={status === 409 || status === 412 ? 'warning' : 'danger'}
      title={
        status === 403
          ? '자동화를 관리할 권한이 없습니다.'
          : status === 404
            ? '자동화 또는 샘플 티켓을 찾을 수 없습니다.'
            : status === 409 || status === 412
              ? '다른 변경과 충돌했습니다.'
              : '요청 결과를 확인하지 못했습니다.'
      }
    >
      <p>작성 중인 입력은 유지됩니다.</p>
    </SeedNotice>
  )
}
export function AdminAutomationsPage() {
  const rules = useQuery({
    queryKey: ['admin-automations'],
    queryFn: listAutomations,
    retry: false,
  })
  const [editing, setEditing] = useState<Automation | 'new' | null>(null)
  const returnFocusRef = useRef<HTMLElement | null>(null)
  return (
    <section className="rule-page">
      <header>
        <h1>시간 자동화</h1>
        <p>해결된 문의를 설정한 시간이 지나면 종료합니다.</p>
      </header>
      <div className="rule-actions">
        <SeedButton
          onClick={(event) => {
            returnFocusRef.current = event.currentTarget
            setEditing('new')
          }}
        >
          자동화 만들기
        </SeedButton>
        <SeedButton onClick={() => void rules.refetch()}>
          목록 새로고침
        </SeedButton>
      </div>
      <Failure error={rules.error} />
      {rules.isPending ? (
        <SeedSkeletonRows rows={3} />
      ) : rules.data?.length === 0 ? (
        <SeedFeedbackState
          kind="empty"
          title="시간 자동화가 없습니다"
          description="해결 후 종료할 시간을 설정하세요."
        />
      ) : (
        <ul className="rule-list">
          {rules.data?.map((rule) => (
            <li key={rule.id}>
              <div>
                <strong>{rule.name}</strong>
                <p>
                  해결 후 {rule.solvedAgeMinutes.toLocaleString('ko-KR')}분 ·{' '}
                  {rule.activeVersion
                    ? `활성 버전 ${rule.activeVersion}`
                    : '비활성'}{' '}
                  · 최신 버전 {rule.currentVersion}
                </p>
              </div>
              <SeedButton
                onClick={(event) => {
                  returnFocusRef.current = event.currentTarget
                  setEditing(rule)
                }}
              >
                {rule.name} 관리
              </SeedButton>
            </li>
          ))}
        </ul>
      )}
      {editing !== null && (
        <AutomationEditor
          key={typeof editing === 'string' ? editing : editing.id}
          initial={editing === 'new' ? null : editing}
          initialPosition={Math.min(
            10000,
            Math.max(0, ...(rules.data ?? []).map((rule) => rule.position)) +
              10,
          )}
          onClose={() => setEditing(null)}
          onRefresh={async () => {
            const result = await rules.refetch()
            if (result.error) throw result.error
          }}
          returnFocusRef={returnFocusRef}
        />
      )}
    </section>
  )
}
function AutomationEditor({
  initial,
  initialPosition,
  onClose,
  onRefresh,
  returnFocusRef,
}: {
  initial: Automation | null
  initialPosition: number
  onClose: () => void
  onRefresh: () => Promise<void>
  returnFocusRef: RefObject<HTMLElement>
}) {
  const [current, setCurrent] = useState(initial)
  const [name, setName] = useState(initial?.name ?? '')
  const [minutes, setMinutes] = useState(
    String(initial?.solvedAgeMinutes ?? 1440),
  )
  const [position, setPosition] = useState(String(initialPosition))
  const [selectedVersion, setSelectedVersion] = useState(
    initial?.currentVersion ?? 1,
  )
  const [versionInput, setVersionInput] = useState(
    String(initial?.currentVersion ?? 1),
  )
  const [sample, setSample] = useState('')
  const [preview, setPreview] = useState<AutomationPreview | null>(null)
  const [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [message, setMessage] = useState('')
  const feedbackRef = useRef<HTMLDivElement>(null)
  const history = useQuery({
    queryKey: ['automation-history', current?.id],
    queryFn: () => automationHistory(current!.id),
    enabled: current !== null,
    retry: false,
  })
  const draft: AutomationDraft = {
    name,
    solvedAgeMinutes: Number(minutes),
    actionType: 'CLOSE_TICKET',
  }
  const dirty =
    !current || JSON.stringify(draft) !== JSON.stringify(draftOf(current))
  const validNumber = (value: string, max: number) =>
    value.trim() !== '' &&
    Number.isSafeInteger(Number(value)) &&
    Number(value) > 0 &&
    Number(value) <= max
  const invalid =
    !name.trim() ||
    !validNumber(minutes, 525600) ||
    (!current && !validNumber(position, 10000))
  const change = () => {
    setPreview(null)
    setMessage('')
  }
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
  const accept = (rule: Automation, version: number) => {
    setCurrent(rule)
    setName(rule.name)
    setMinutes(String(rule.solvedAgeMinutes))
    setSelectedVersion(version)
    setVersionInput(String(version))
    setPreview(null)
    setUncertain(false)
  }
  const review = (version: number) =>
    run(async () => {
      if (!current) return
      accept(await automationVersion(current.id, version), version)
      setMessage(`버전 ${version}을 불러왔습니다.`)
    })
  return (
    <SeedDrawer
      open
      title={current ? `${current.name} 관리` : '자동화 만들기'}
      description="저장한 정책으로 샘플 티켓을 확인한 후 활성화하세요."
      onClose={onClose}
      returnFocusRef={returnFocusRef}
    >
      <div className="rule-editor">
        <div ref={feedbackRef} tabIndex={-1}>
          <Failure error={error} />
          {message && <p role="status">{message}</p>}
        </div>
        {uncertain && (
          <SeedNotice title="최신 상태 확인이 필요합니다" tone="warning">
            <p>
              입력을 유지하면서 저장된 목록과 버전을 확인하세요. 새 자동화의
              응답이 불명확하면 목록에서 생성 여부를 확인한 뒤 다시 선택하세요.
            </p>
            <SeedButton
              disabled={busy}
              onClick={() =>
                void run(async () => {
                  await onRefresh()
                  if (current) {
                    setCurrent(
                      await automationVersion(current.id, selectedVersion),
                    )
                    setUncertain(false)
                    void history.refetch()
                  }
                  setMessage(
                    current
                      ? '최신 상태를 확인했습니다. 현재 입력과 비교하세요.'
                      : '목록을 새로고침했습니다. 편집기를 닫고 생성 여부를 확인하세요.',
                  )
                })
              }
            >
              최신 상태 확인 (입력 유지)
            </SeedButton>
          </SeedNotice>
        )}
        <fieldset className="rule-form" disabled={busy || uncertain}>
          <legend>종료 정책</legend>
          <SeedTextField
            label="자동화 이름"
            value={name}
            maxLength={120}
            required
            onChange={(event) => {
              setName(event.target.value)
              change()
            }}
          />
          <SeedTextField
            label="해결 후 경과 시간 (분)"
            type="number"
            min={1}
            max={525600}
            step={1}
            required
            value={minutes}
            onChange={(event) => {
              setMinutes(event.target.value)
              change()
            }}
          />
          <p>
            1~525,600분의 실제 경과 시간을 사용합니다. 1일은 1,440분입니다.
            조건이 맞으면 티켓을 종료합니다.
          </p>
          {!current && (
            <SeedTextField
              label="처리 순서"
              type="number"
              min={1}
              max={10000}
              value={position}
              onChange={(event) => setPosition(event.target.value)}
            />
          )}
          <SeedButton
            disabled={invalid || !dirty}
            onClick={() =>
              void run(async () => {
                const saved = await saveAutomation(
                  current,
                  draft,
                  Number(position),
                )
                accept(saved, saved.currentVersion)
                await onRefresh()
                setMessage('새 버전을 저장했습니다. 활성 버전은 유지됩니다.')
                if (current) void history.refetch()
              }, true)
            }
          >
            {current ? '새 버전 저장' : '초안 저장'}
          </SeedButton>
        </fieldset>
        {current && (
          <>
            <section aria-label="정책 미리보기">
              <h3>버전 {selectedVersion} 미리보기</h3>
              <p>
                현재 활성:{' '}
                {current.activeVersion
                  ? `버전 ${current.activeVersion}`
                  : '없음'}
                . 미리보기는 티켓을 변경하지 않습니다.
              </p>
              {dirty && (
                <SeedNotice
                  tone="warning"
                  title="변경한 입력을 먼저 저장하세요"
                />
              )}
              <SeedTextField
                label="샘플 티켓 번호"
                type="number"
                min={1}
                step={1}
                value={sample}
                disabled={busy}
                onChange={(event) => {
                  setSample(event.target.value)
                  setPreview(null)
                }}
              />
              <SeedButton
                disabled={
                  busy ||
                  uncertain ||
                  dirty ||
                  !validNumber(sample, Number.MAX_SAFE_INTEGER)
                }
                onClick={() =>
                  void run(async () =>
                    setPreview(
                      await previewAutomation(
                        current.id,
                        selectedVersion,
                        Number(sample),
                      ),
                    ),
                  )
                }
              >
                종료 조건 미리보기
              </SeedButton>
              {preview && (
                <SeedNotice
                  tone={preview.matched ? 'positive' : 'warning'}
                  title={
                    preview.matched
                      ? '현재 종료 조건에 해당합니다'
                      : '현재 종료 조건에 해당하지 않습니다'
                  }
                >
                  <p>
                    티켓 #{preview.ticketNumber} · 상태:{' '}
                    {stateLabels[preview.status] ?? preview.status}
                  </p>
                  <p>해결 시각: {when(preview.solvedAt)}</p>
                  <p>실행 가능 시각: {when(preview.eligibleAt)}</p>
                  <p>
                    실행 직전에 티켓 상태와 해결 시각을 다시 확인합니다.
                    재개방된 문의는 건너뜁니다.
                  </p>
                </SeedNotice>
              )}
              <div className="rule-actions">
                <SeedButton
                  disabled={
                    busy ||
                    uncertain ||
                    dirty ||
                    !preview ||
                    current.activeVersion === selectedVersion
                  }
                  onClick={() =>
                    void run(async () => {
                      accept(
                        await activateAutomation(current, selectedVersion),
                        selectedVersion,
                      )
                      await onRefresh()
                      setMessage(`버전 ${selectedVersion}을 활성화했습니다.`)
                      void history.refetch()
                    }, true)
                  }
                >
                  버전 {selectedVersion} 활성화
                </SeedButton>
                <SeedButton
                  disabled={busy || uncertain || current.activeVersion === null}
                  onClick={() =>
                    void run(async () => {
                      const saved = await deactivateAutomation(current)
                      setCurrent({
                        ...current,
                        activeVersion: saved.activeVersion,
                        aggregateVersion: saved.aggregateVersion,
                        updatedAt: saved.updatedAt,
                      })
                      setPreview(null)
                      await onRefresh()
                      setMessage(
                        '새 후보 발견을 중단했습니다. 이미 발견된 작업은 계속 처리됩니다.',
                      )
                      void history.refetch()
                    }, true)
                  }
                >
                  자동화 비활성화
                </SeedButton>
              </div>
              <p>
                활성 버전은 새 후보에 적용됩니다. 비활성화해도 이미 발견된
                작업은 저장된 버전으로 계속 처리될 수 있습니다.
              </p>
            </section>
            <section aria-label="정책 이력">
              <h3>버전과 실행 이력</h3>
              <SeedButton
                disabled={busy}
                onClick={() => void history.refetch()}
              >
                이력 새로고침
              </SeedButton>
              <Failure error={history.error} />
              {history.isPending && <SeedSkeletonRows rows={2} />}
              <SeedTextField
                label="조회할 버전"
                type="number"
                min={1}
                max={current.currentVersion}
                step={1}
                value={versionInput}
                disabled={busy}
                onChange={(event) => setVersionInput(event.target.value)}
              />
              <SeedButton
                disabled={
                  busy ||
                  uncertain ||
                  dirty ||
                  !validNumber(versionInput, current.currentVersion)
                }
                onClick={() => void review(Number(versionInput))}
              >
                저장된 버전 불러오기
              </SeedButton>
              {dirty && (
                <p>작성 중인 입력을 저장한 후 다른 버전을 불러오세요.</p>
              )}
              {history.data && (
                <>
                  <h4>최근 버전 (최대 20개)</h4>
                  <ul>
                    {history.data.versions.map((version) => (
                      <li key={version.version}>
                        버전 {version.version} · {version.name} ·{' '}
                        {version.solvedAgeMinutes}분 ·{' '}
                        {version.createdByDisplay} · {when(version.createdAt)}
                      </li>
                    ))}
                  </ul>
                  <h4>활성화 이력 (최대 50개)</h4>
                  {history.data.activations.length === 0 ? (
                    <p>활성화 이력이 없습니다.</p>
                  ) : (
                    <ul>
                      {history.data.activations.map((entry, index) => (
                        <li key={index}>
                          버전 {entry.version} ·{' '}
                          {entry.state === 'ACTIVE' ? '활성' : '비활성'} ·{' '}
                          {entry.actorDisplay} · {when(entry.occurredAt)}
                        </li>
                      ))}
                    </ul>
                  )}
                  <h4>최근 실행 (최대 50개)</h4>
                  {history.data.executions.length === 0 ? (
                    <p>실행 이력이 없습니다.</p>
                  ) : (
                    <ul>
                      {history.data.executions.map((entry) => (
                        <li key={entry.id}>
                          티켓 #{entry.ticketNumber} · 버전 {entry.version} ·{' '}
                          {stateLabels[entry.outcome] ?? entry.outcome} ·{' '}
                          {when(entry.completedAt)}
                          {entry.errorCode && ` · ${entry.errorCode}`}
                        </li>
                      ))}
                    </ul>
                  )}
                  <h4>최근 후보 (최대 50개)</h4>
                  {history.data.candidates.length === 0 ? (
                    <p>발견된 후보가 없습니다.</p>
                  ) : (
                    <ul>
                      {history.data.candidates.map((entry) => (
                        <li key={entry.id}>
                          티켓 #{entry.ticketNumber} · 버전 {entry.version} ·{' '}
                          {stateLabels[entry.status] ?? entry.status} · 시도{' '}
                          {entry.attemptCount}회 · 실행 가능{' '}
                          {when(entry.eligibleAt)}
                          {entry.lastErrorCode && ` · ${entry.lastErrorCode}`}
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              )}
            </section>
          </>
        )}
      </div>
    </SeedDrawer>
  )
}
