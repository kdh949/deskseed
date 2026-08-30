import { useEffect, useId, useRef, useState, type RefObject } from 'react'
import { ApiError } from '../../api/client'
import type {
  CreateSavedViewInput,
  SavedAgentView,
  SavedViewColumn,
  SavedViewCondition,
  SavedViewConditionField,
  SavedViewConditionOperator,
  SavedViewDefinition,
  SavedViewPreview,
  SavedViewScope,
} from '../../api/types'
import {
  SeedButton,
  SeedCheckbox,
  SeedDrawer,
  SeedNotice,
  SeedSelect,
  SeedTextAreaField,
  SeedTextField,
} from '../../design-system/canonical'

export type ViewEditor =
  | { mode: 'create' }
  | { mode: 'edit'; view: SavedAgentView; pendingOrderOnly?: boolean }

export type SavedViewEditorSave = {
  definition: SavedViewDefinition
  expectedVersion?: number
  scope: Exclude<SavedViewScope, 'SYSTEM'>
}

const FIELDS: SavedViewConditionField[] = [
  'STATUS',
  'PRIORITY',
  'GROUP',
  'ASSIGNEE',
  'FIRST_REPLY_SLA_STATE',
  'TICKET_KIND',
  'UPDATED_AT',
]
const OPERATORS: SavedViewConditionOperator[] = [
  'EQUALS',
  'NOT_EQUALS',
  'IN',
  'NOT_IN',
  'IS_CURRENT_ACTOR',
  'IS_UNASSIGNED',
  'IS_CURRENT_ACTOR_GROUP',
  'LESS_THAN_SOLVED',
  'WITHIN_LAST_DAYS',
]
const COLUMNS: SavedViewColumn[] = [
  'TICKET_NUMBER',
  'SUBJECT',
  'STATUS',
  'PRIORITY',
  'GROUP',
  'ASSIGNEE',
  'UPDATED_AT',
  'FIRST_REPLY_SLA',
]
const EMPTY_CONDITION: SavedViewCondition = {
  field: 'STATUS',
  operator: 'LESS_THAN_SOLVED',
  values: [],
}

type ViewConfigurationDrawerProps = {
  editor: ViewEditor | null
  onClose: () => void
  onDelete?: (view: SavedAgentView) => Promise<void>
  onMove?: (direction: 'down' | 'up') => Promise<void> | void
  onPreview: (definition: SavedViewDefinition) => Promise<SavedViewPreview>
  onReload?: () => Promise<void>
  onSave: (values: SavedViewEditorSave) => Promise<void>
  position?: { index: number; total: number }
  returnFocusRef?: RefObject<HTMLElement>
}

export function ViewConfigurationDrawer({
  editor,
  onClose,
  onDelete,
  onMove,
  onPreview,
  onReload,
  onSave,
  position,
  returnFocusRef,
}: ViewConfigurationDrawerProps) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [scope, setScope] =
    useState<Exclude<SavedViewScope, 'SYSTEM'>>('PERSONAL')
  const [all, setAll] = useState<SavedViewCondition[]>([EMPTY_CONDITION])
  const [any, setAny] = useState<SavedViewCondition[]>([])
  const [columns, setColumns] = useState<SavedViewColumn[]>([
    'TICKET_NUMBER',
    'SUBJECT',
    'STATUS',
    'FIRST_REPLY_SLA',
  ])
  const [preview, setPreview] = useState<SavedViewPreview | null>(null)
  const [busy, setBusy] = useState<
    'delete' | 'preview' | 'reload' | 'save' | null
  >(null)
  const [error, setError] = useState<{
    message: string
    conflict: boolean
  } | null>(null)
  const errorRef = useRef<HTMLDivElement>(null)
  const nameId = useId()
  const descriptionId = useId()
  const editingView = editor?.mode === 'edit' ? editor.view : null

  useEffect(() => {
    if (!editor) return
    const saved = editor.mode === 'edit' ? editor.view : null
    setName(saved?.name ?? '')
    setDescription(saved?.description ?? '')
    setScope(
      saved?.scope === 'SHARED' || saved?.scope === 'PERSONAL'
        ? saved.scope
        : 'PERSONAL',
    )
    setAll(saved?.conditions.all ?? [EMPTY_CONDITION])
    setAny(saved?.conditions.any ?? [])
    setColumns(
      saved?.columns ?? [
        'TICKET_NUMBER',
        'SUBJECT',
        'STATUS',
        'FIRST_REPLY_SLA',
      ],
    )
    setPreview(null)
    setError(null)
    setBusy(null)
  }, [editor])

  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])

  const definition = (): SavedViewDefinition => ({
    name: name.trim(),
    description: description.trim(),
    conditions: { version: 1, all, any },
    columns,
    sort: 'updatedAt:desc,ticketNumber:desc',
  })
  const validationError = validate(definition())

  const run = async (
    kind: 'delete' | 'preview' | 'reload' | 'save',
    action: () => Promise<void>,
  ) => {
    setBusy(kind)
    setError(null)
    try {
      await action()
    } catch (caught) {
      const orderOnlyFailure =
        caught instanceof Error && caught.name === 'SavedViewOrderSaveError'
      const conflict =
        caught instanceof ApiError &&
        (caught.status === 409 || caught.status === 412)
      setError({
        conflict,
        message: orderOnlyFailure
          ? '보기 정의는 저장되었습니다. 순서 저장만 실패했습니다. 최신 순서 버전으로 다시 시도하세요.'
          : conflict
            ? '다른 사용자가 이 보기를 변경했습니다. 입력은 유지됩니다. 최신 목록을 확인한 뒤 다시 시도하세요.'
            : '보기 요청을 완료하지 못했습니다. 입력은 유지됩니다.',
      })
    } finally {
      setBusy(null)
    }
  }

  return (
    <SeedDrawer
      description="서버에 저장되는 versioned 보기 정의입니다."
      onClose={onClose}
      open={editor !== null}
      returnFocusRef={returnFocusRef}
      title={editingView ? `${editingView.name} 편집` : '새 보기 만들기'}
    >
      <form
        className="seed-view-editor"
        onSubmit={(event) => {
          event.preventDefault()
          if (validationError) return
          void run('save', () =>
            onSave({
              definition: definition(),
              scope,
              ...(editingView
                ? { expectedVersion: editingView.definitionVersion }
                : {}),
            }),
          )
        }}
      >
        {error ? (
          <div ref={errorRef} tabIndex={-1}>
            <SeedNotice
              title={
                editor?.mode === 'edit' && editor.pendingOrderOnly
                  ? '보기 정의 저장 완료 · 순서 저장 실패'
                  : error.conflict
                    ? '보기 버전 충돌'
                    : '보기 요청 실패'
              }
              tone="danger"
            >
              <p>{error.message}</p>
              {error.conflict && onReload ? (
                <SeedButton
                  disabled={Boolean(busy)}
                  onClick={() => void run('reload', onReload)}
                  type="button"
                >
                  {busy === 'reload'
                    ? '불러오는 중…'
                    : '최신 버전 다시 불러오기'}
                </SeedButton>
              ) : null}
            </SeedNotice>
          </div>
        ) : null}

        <SeedTextField
          autoFocus
          id={nameId}
          label="보기 이름"
          maxLength={120}
          onChange={(event) => setName(event.target.value)}
          value={name}
        />
        <SeedTextAreaField
          aria-label="설명"
          hint={`${description.length.toLocaleString('ko-KR')} / 500자`}
          id={descriptionId}
          label="설명"
          maxLength={500}
          onChange={(event) => setDescription(event.target.value)}
          rows={4}
          value={description}
        />
        <label className="seed-view-editor__field">
          <span>공유 범위</span>
          <SeedSelect
            aria-label="보기 공유 범위"
            disabled={Boolean(editingView)}
            onChange={(event) =>
              setScope(event.target.value as 'PERSONAL' | 'SHARED')
            }
            value={scope}
          >
            <option value="PERSONAL">PERSONAL · 나만</option>
            <option value="SHARED">SHARED · 권한 있는 상담사</option>
          </SeedSelect>
        </label>
        <ConditionGroup
          conditions={all}
          label="모든 조건 (all)"
          onChange={setAll}
        />
        <ConditionGroup
          conditions={any}
          label="하나 이상 조건 (any)"
          onChange={setAny}
        />

        <fieldset className="seed-view-editor__columns">
          <legend>표시 컬럼</legend>
          {COLUMNS.map((column) => (
            <SeedCheckbox
              key={column}
              label={column}
              checked={columns.includes(column)}
              onChange={(event) =>
                setColumns((current) =>
                  event.target.checked
                    ? [...current, column]
                    : current.filter((item) => item !== column),
                )
              }
            />
          ))}
        </fieldset>
        <label className="seed-view-editor__field">
          <span>정렬</span>
          <SeedSelect
            aria-label="보기 정렬"
            value="updatedAt:desc,ticketNumber:desc"
            disabled
          >
            <option value="updatedAt:desc,ticketNumber:desc">
              최근 업데이트 내림차순
            </option>
          </SeedSelect>
        </label>

        {validationError ? <p role="alert">{validationError}</p> : null}
        {preview ? (
          <SeedNotice
            title={`Preview: 정확히 ${preview.ticketCount.toLocaleString('ko-KR')}개`}
            tone="positive"
          >
            샘플 {preview.items.length}개 ·{' '}
            <time dateTime={preview.ticketCountAsOf}>
              {formatCountBasis(preview.ticketCountAsOf)} 기준
            </time>{' '}
            · 서버 권한 predicate와 같은 조건 compiler를 사용했습니다.
          </SeedNotice>
        ) : null}

        {editingView && position && onMove ? (
          <section aria-label="보기 순서" className="seed-view-editor__order">
            <div>
              <strong>사이드바 순서</strong>
              <p>
                {position.index + 1} / {position.total}
              </p>
            </div>
            <div>
              <SeedButton
                disabled={position.index === 0 || Boolean(busy)}
                onClick={() => void onMove('up')}
                type="button"
              >
                위로
              </SeedButton>
              <SeedButton
                disabled={
                  position.index === position.total - 1 || Boolean(busy)
                }
                onClick={() => void onMove('down')}
                type="button"
              >
                아래로
              </SeedButton>
            </div>
          </section>
        ) : null}

        <footer className="seed-view-editor__actions">
          {editingView && onDelete ? (
            <SeedButton
              disabled={Boolean(busy)}
              onClick={() => void run('delete', () => onDelete(editingView))}
              type="button"
            >
              삭제
            </SeedButton>
          ) : null}
          <SeedButton
            disabled={Boolean(busy) || Boolean(validationError)}
            onClick={() =>
              void run('preview', async () =>
                setPreview(await onPreview(definition())),
              )
            }
            type="button"
          >
            {busy === 'preview' ? 'Preview 중…' : 'Preview'}
          </SeedButton>
          <SeedButton onClick={onClose} type="button">
            취소
          </SeedButton>
          <SeedButton
            disabled={Boolean(busy) || Boolean(validationError)}
            variant="primary"
            type="submit"
          >
            {busy === 'save'
              ? '저장 중…'
              : editingView
                ? '변경 저장'
                : '보기 만들기'}
          </SeedButton>
        </footer>
      </form>
    </SeedDrawer>
  )
}

function ConditionGroup({
  conditions,
  label,
  onChange,
}: {
  conditions: SavedViewCondition[]
  label: string
  onChange: (conditions: SavedViewCondition[]) => void
}) {
  const update = (index: number, next: SavedViewCondition) =>
    onChange(
      conditions.map((condition, current) =>
        current === index ? next : condition,
      ),
    )
  return (
    <fieldset className="seed-view-editor__conditions">
      <legend>{label}</legend>
      {conditions.map((condition, index) => (
        <div
          className="seed-view-editor__condition"
          key={`${index}:${condition.field}`}
        >
          <SeedSelect
            aria-label={`${label} ${index + 1} 필드`}
            onChange={(event) =>
              update(index, {
                ...condition,
                field: event.target.value as SavedViewConditionField,
              })
            }
            value={condition.field}
          >
            {FIELDS.map((field) => (
              <option key={field} value={field}>
                {field}
              </option>
            ))}
          </SeedSelect>
          <SeedSelect
            aria-label={`${label} ${index + 1} 연산자`}
            onChange={(event) => {
              const operator = event.target.value as SavedViewConditionOperator
              update(index, {
                ...condition,
                operator,
                values: operatorNeedsNoValues(operator) ? [] : condition.values,
              })
            }}
            value={condition.operator}
          >
            {OPERATORS.map((operator) => (
              <option key={operator} value={operator}>
                {operator}
              </option>
            ))}
          </SeedSelect>
          <input
            aria-label={`${label} ${index + 1} 값`}
            disabled={operatorNeedsNoValues(condition.operator)}
            onChange={(event) =>
              update(index, {
                ...condition,
                values: event.target.value
                  .split(',')
                  .map((value) => value.trim())
                  .filter(Boolean),
              })
            }
            placeholder="쉼표로 여러 값 구분"
            value={condition.values.join(', ')}
          />
          <SeedButton
            aria-label={`${label} ${index + 1} 삭제`}
            onClick={() =>
              onChange(conditions.filter((_, current) => current !== index))
            }
            type="button"
          >
            삭제
          </SeedButton>
        </div>
      ))}
      <SeedButton
        onClick={() => onChange([...conditions, { ...EMPTY_CONDITION }])}
        type="button"
      >
        조건 추가
      </SeedButton>
    </fieldset>
  )
}

function operatorNeedsNoValues(operator: SavedViewConditionOperator) {
  return [
    'IS_CURRENT_ACTOR',
    'IS_UNASSIGNED',
    'IS_CURRENT_ACTOR_GROUP',
    'LESS_THAN_SOLVED',
  ].includes(operator)
}

function validate(definition: SavedViewDefinition) {
  if (!definition.name) return '보기 이름을 입력하세요.'
  if (hasIsoControlCharacters(definition.description))
    return '설명에는 제어 문자를 입력할 수 없습니다.'
  if (!definition.conditions.all.length && !definition.conditions.any.length)
    return 'all 또는 any 조건을 하나 이상 추가하세요.'
  if (!definition.columns.length) return '표시 컬럼을 하나 이상 선택하세요.'
  return ''
}

function hasIsoControlCharacters(value: string) {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0) ?? 0
    return codePoint <= 31 || (codePoint >= 127 && codePoint <= 159)
  })
}

function formatCountBasis(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(value))
}

export function toCreateSavedViewInput(
  values: SavedViewEditorSave,
): CreateSavedViewInput {
  return { scope: values.scope, ...values.definition }
}
