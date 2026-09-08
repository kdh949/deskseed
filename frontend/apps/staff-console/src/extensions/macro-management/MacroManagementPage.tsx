import { useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import type { AgentMacroDefinition } from '../../api/types'
import {
  SeedButton,
  SeedFeedbackState,
  SeedNotice,
  SeedSelectField,
  SeedSkeletonRows,
  SeedTextAreaField,
  SeedTextField,
} from '../../design-system/canonical'
import {
  activateMacro,
  editableDraft,
  getMacroHistory,
  listManagedMacros,
  saveMacro,
  refreshMacroDraft,
  type EditableMacroField,
  toMacroDraft,
  type MacroScope,
} from './api'
import './macros.css'

const STATUS = {
  NEW: '신규',
  OPEN: '진행 중',
  PENDING: '고객 답변 대기',
  ON_HOLD: '보류',
  SOLVED: '해결',
}
const PRIORITY = { LOW: '낮음', NORMAL: '보통', HIGH: '높음', URGENT: '긴급' }

export function MacroManagementPage({ scope }: { scope: MacroScope }) {
  const client = useQueryClient()
  const queryKey = ['managed-macros', scope]
  const macros = useQuery({
    queryKey,
    queryFn: () => listManagedMacros(scope),
    retry: false,
  })
  const [editing, setEditing] = useState<AgentMacroDefinition | 'new' | null>(
    null,
  )
  const [historyFor, setHistoryFor] = useState<AgentMacroDefinition | null>(
    null,
  )
  const history = useQuery({
    queryKey: ['macro-history', scope, historyFor?.id],
    queryFn: () => getMacroHistory(scope, historyFor!.id),
    enabled: !!historyFor,
    retry: false,
  })
  const [draft, setDraft] = useState(editableDraft())
  const [conflicts, setConflicts] = useState<EditableMacroField[]>([])
  const customStatusConflict =
    conflicts.includes('status') &&
    draft.preserved.some((action) => action.type === 'CUSTOM_STATUS')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const [success, setSuccess] = useState('')
  const [refreshRequired, setRefreshRequired] = useState(false)
  const refresh = async () => {
    if (busy || macros.isFetching) return
    const result = await macros.refetch()
    if (!result.error) {
      if (editing && editing !== 'new') {
        const latest = result.data?.find((m) => m.id === editing.id)
        if (latest) {
          const refreshed = refreshMacroDraft(draft, editing, latest)
          setDraft(refreshed.draft)
          setConflicts((pending) =>
            [...new Set([...pending, ...refreshed.conflicts])].filter(
              (field) =>
                refreshed.draft[field] !== editableDraft(latest)[field],
            ),
          )
          setEditing(latest)
        } else return
      }
      setRefreshRequired(false)
    }
  }
  const begin = (macro: AgentMacroDefinition | 'new') => {
    if (busy || macros.isFetching) return
    setEditing(macro)
    setConflicts([])
    setDraft(editableDraft(macro === 'new' ? undefined : macro))
    setError(null)
    setSuccess('')
    setRefreshRequired(false)
  }
  const mutate = async (
    command: () => Promise<AgentMacroDefinition>,
    saved: boolean,
  ) => {
    if (busy || macros.isFetching || refreshRequired || conflicts.length > 0)
      return
    setBusy(true)
    setError(null)
    setSuccess('')
    try {
      const result = await command()
      await client.invalidateQueries({ queryKey })
      await client.invalidateQueries({ queryKey: ['macro-history', scope] })
      if (saved) {
        setEditing(null)
        setSuccess(
          `버전 ${result.currentVersion}을 저장했습니다. 확인 후 활성화하세요.`,
        )
      } else
        setSuccess(
          result.activeVersion
            ? `버전 ${result.activeVersion}이 활성화되었습니다.`
            : '매크로를 비활성화했습니다.',
        )
    } catch (failure) {
      setError(failure)
      setRefreshRequired(
        !(failure instanceof ApiError) ||
          [409, 412].includes(failure.status) ||
          failure.status >= 500,
      )
    } finally {
      setBusy(false)
    }
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!editing) return
    void mutate(
      () =>
        saveMacro(
          scope,
          toMacroDraft(draft),
          editing === 'new' ? undefined : editing,
        ),
      true,
    )
  }
  const failure = error || macros.error
  const status = failure instanceof ApiError ? failure.status : undefined
  return (
    <section className="macro-page">
      <header>
        <h1>{scope === 'SHARED' ? '공유 매크로' : '내 매크로'}</h1>
        <p>
          {scope === 'SHARED'
            ? '상담팀이 함께 사용할 답변과 티켓 변경을 관리합니다.'
            : '내가 반복해서 사용하는 답변과 티켓 변경을 관리합니다.'}{' '}
          저장한 버전은 별도로 활성화해야 상담 화면에 나타납니다.
        </p>
      </header>
      <div className="macro-actions">
        <SeedButton
          disabled={busy || macros.isFetching || !macros.data}
          onClick={() => begin('new')}
        >
          매크로 만들기
        </SeedButton>
        <SeedButton
          disabled={busy || macros.isFetching}
          onClick={() => void refresh()}
        >
          최신 목록 확인
        </SeedButton>
      </div>
      {Boolean(macros.error || error) && (
        <SeedNotice
          tone={refreshRequired ? 'warning' : 'danger'}
          title={
            status === 403
              ? '매크로를 관리할 권한이 없습니다.'
              : refreshRequired
                ? '최신 내용을 확인한 뒤 다시 저장하세요.'
                : '요청을 완료하지 못했습니다.'
          }
        >
          {refreshRequired
            ? '입력은 유지됩니다. 최신 목록 확인으로 저장 결과와 활성 버전을 비교하세요.'
            : '권한과 입력 내용을 확인한 뒤 다시 시도하세요.'}
        </SeedNotice>
      )}
      {success && <p role="status">{success}</p>}
      {macros.isPending ? (
        <SeedSkeletonRows />
      ) : (
        <ul className="macro-list">
          {macros.data?.map((macro) => (
            <li key={macro.id}>
              <div>
                <strong>{macro.name}</strong>
                <p>
                  최신 버전 {macro.currentVersion} ·{' '}
                  {macro.activeVersion
                    ? `활성 버전 ${macro.activeVersion}`
                    : '비활성'}
                </p>
              </div>
              <div className="macro-actions">
                <SeedButton
                  disabled={busy || macros.isFetching}
                  onClick={() => begin(macro)}
                >
                  편집: {macro.name}
                </SeedButton>
                <SeedButton
                  disabled={
                    busy ||
                    refreshRequired ||
                    macro.activeVersion === macro.currentVersion
                  }
                  onClick={() =>
                    void mutate(() => activateMacro(scope, macro, true), false)
                  }
                >
                  최신 버전 활성화: {macro.name}
                </SeedButton>
                {macro.activeVersion && (
                  <SeedButton
                    disabled={busy || refreshRequired}
                    onClick={() =>
                      void mutate(
                        () => activateMacro(scope, macro, false),
                        false,
                      )
                    }
                  >
                    비활성화: {macro.name}
                  </SeedButton>
                )}
                <SeedButton onClick={() => setHistoryFor(macro)}>
                  이력: {macro.name}
                </SeedButton>
              </div>
            </li>
          ))}
        </ul>
      )}
      {macros.data?.length === 0 && (
        <SeedFeedbackState
          kind="empty"
          title="등록된 매크로가 없습니다."
          description="자주 쓰는 안내 문구와 티켓 변경을 함께 저장하세요."
        />
      )}
      {editing && (
        <form
          className="macro-editor"
          aria-label="매크로 편집"
          onSubmit={submit}
        >
          <h2>
            {editing === 'new' ? '매크로 만들기' : `${editing.name} 새 버전`}
          </h2>
          {conflicts.length > 0 && editing !== 'new' && (
            <SeedNotice
              title="같은 항목에 다른 변경이 있습니다."
              tone="warning"
            >
              <p>
                최신 변경:{' '}
                {conflicts
                  .map(
                    (field) =>
                      `${{ name: '이름', template: '답변 문구', visibility: '공개 범위', status: '상태', priority: '우선순위' }[field]} = ${editableDraft(editing)[field] || '없음'}`,
                  )
                  .join(', ')}
                . 아래 편집값을 유지할지 선택하세요.
              </p>
              {customStatusConflict && (
                <p>
                  최신 사용자 정의 상태와 기본 상태를 함께 적용할 수 없습니다.
                  충돌 항목의 최신 값을 사용해 주세요.
                </p>
              )}
              <SeedButton
                disabled={busy || macros.isFetching}
                onClick={() => {
                  const latest = editableDraft(editing)
                  setDraft((current) => {
                    const next = { ...current }
                    for (const field of conflicts) next[field] = latest[field]
                    return next
                  })
                  setConflicts([])
                }}
              >
                충돌 항목의 최신 값 사용
              </SeedButton>
              <SeedButton
                disabled={busy || macros.isFetching || customStatusConflict}
                onClick={() => setConflicts([])}
              >
                내 편집값 유지
              </SeedButton>
            </SeedNotice>
          )}
          <fieldset disabled={busy || macros.isFetching}>
            <legend>답변과 변경 사항</legend>
            <SeedTextField
              label="매크로 이름"
              required
              maxLength={120}
              value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            />
            <SeedSelectField
              label="답변 공개 범위"
              value={draft.visibility}
              onChange={(e) =>
                setDraft({ ...draft, visibility: e.target.value })
              }
            >
              <option value="PUBLIC">고객에게 공개</option>
              <option value="INTERNAL">내부 메모</option>
            </SeedSelectField>
            <SeedTextAreaField
              label="답변 문구"
              value={draft.template}
              maxLength={10000}
              onChange={(e) => setDraft({ ...draft, template: e.target.value })}
            />
            <p>
              치환값: {'{{requester.name}}'}, {'{{agent.name}}'},{' '}
              {'{{ticket.number}}'}, {'{{ticket.subject}}'},{' '}
              {'{{ticket.status}}'}, {'{{ticket.priority}}'}. 실제 고객과 티켓
              값은 상담 화면의 적용 미리보기에서 확인합니다.
            </p>
            <SeedSelectField
              label="변경할 상태"
              disabled={draft.preserved.some((a) => a.type === 'CUSTOM_STATUS')}
              value={draft.status}
              onChange={(e) => setDraft({ ...draft, status: e.target.value })}
            >
              <option value="">변경하지 않음</option>
              {Object.entries(STATUS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </SeedSelectField>
            <SeedSelectField
              label="변경할 우선순위"
              value={draft.priority}
              onChange={(e) => setDraft({ ...draft, priority: e.target.value })}
            >
              <option value="">변경하지 않음</option>
              {Object.entries(PRIORITY).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </SeedSelectField>
            {draft.preserved.length > 0 && (
              <SeedNotice title="추가 변경 사항 유지" tone="warning">
                기존 그룹·담당자·태그·사용자 정의 필드 변경{' '}
                {draft.preserved.length}건은 저장된 설정을 그대로 유지합니다.
                실제 티켓에 적용하기 전에 상담 화면에서 모든 변경을 확인하세요.
              </SeedNotice>
            )}
            <section aria-label="저장 내용 미리보기">
              <h3>저장 내용 미리보기</h3>
              <p>
                {draft.visibility === 'PUBLIC' ? '공개 답변' : '내부 메모'} ·
                상태 {STATUS[draft.status as keyof typeof STATUS] ?? '유지'} ·
                우선순위{' '}
                {PRIORITY[draft.priority as keyof typeof PRIORITY] ?? '유지'}
              </p>
              <p className="macro-preview">
                {draft.template || '답변을 추가하지 않습니다.'}
              </p>
            </section>
            <div className="macro-actions">
              <SeedButton
                type="submit"
                variant="primary"
                disabled={
                  refreshRequired ||
                  conflicts.length > 0 ||
                  !draft.name.trim() ||
                  !toMacroDraft(draft).actions.length
                }
              >
                {editing === 'new' ? '매크로 저장' : '새 버전 저장'}
              </SeedButton>
              <SeedButton
                onClick={() => {
                  setEditing(null)
                  setConflicts([])
                }}
              >
                편집 닫기
              </SeedButton>
            </div>
          </fieldset>
        </form>
      )}
      {historyFor && (
        <section className="macro-editor" aria-label="매크로 이력">
          <h2>{historyFor.name} 이력</h2>
          <p>버전과 활성 변경을 각각 최근 100건까지 표시합니다.</p>
          <SeedButton onClick={() => setHistoryFor(null)}>이력 닫기</SeedButton>
          {history.isPending ? (
            <SeedSkeletonRows />
          ) : history.error ? (
            <SeedFeedbackState
              kind="error"
              title="이력을 불러오지 못했습니다."
              description="권한을 확인하고 다시 열어 주세요."
            />
          ) : (
            <>
              <h3>버전 저장</h3>
              <ul>
                {history.data?.versions.map((v) => (
                  <li key={v.version}>
                    버전 {v.version} · {v.name} · {v.createdByDisplay} ·{' '}
                    {new Date(v.createdAt).toLocaleString('ko-KR')}
                  </li>
                ))}
              </ul>
              <h3>활성 변경</h3>
              {history.data?.activations.length === 0 && (
                <p>활성 변경 이력이 없습니다.</p>
              )}
              <ul>
                {history.data?.activations.map((v, i) => (
                  <li key={i}>
                    버전 {v.version}{' '}
                    {v.state === 'ACTIVE' ? '활성화' : '비활성화'} ·{' '}
                    {v.actorDisplay} ·{' '}
                    {new Date(v.occurredAt).toLocaleString('ko-KR')}
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}
    </section>
  )
}
