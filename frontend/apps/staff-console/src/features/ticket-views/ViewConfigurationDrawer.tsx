import { useEffect, useId, useRef, useState, type RefObject } from 'react'
import type {
  ViewConfigurationCatalog,
  ViewChoice,
} from './viewConfigurationCatalog'
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
  'TAG',
  'FORM',
  'CUSTOM_STATUS',
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
  catalog?: ViewConfigurationCatalog
  catalogError?: boolean
  onReloadCatalog?: () => void
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
  catalog,
  catalogError,
  onReloadCatalog,
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
  const validationError = validate(definition(), catalog)

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
      description="조건에 맞는 티켓을 모아 보고 팀과 공유하세요."
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

        {catalogError ? (
          <SeedNotice title="추가 필터를 불러오지 못했습니다" tone="warning">
            <p>기본 조건은 사용할 수 있습니다. 기존 추가 조건은 유지됩니다.</p>
            <SeedButton type="button" onClick={onReloadCatalog}>
              필터 다시 불러오기
            </SeedButton>
          </SeedNotice>
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
            <option value="PERSONAL">나만</option>
            <option value="SHARED">팀과 공유</option>
          </SeedSelect>
        </label>
        <ConditionGroup
          catalog={catalog}
          conditions={all}
          label="모든 조건 (all)"
          onChange={setAll}
        />
        <ConditionGroup
          catalog={catalog}
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
            title={`미리보기: 정확히 ${preview.ticketCount.toLocaleString('ko-KR')}개`}
            tone="positive"
          >
            샘플 {preview.items.length}개 ·{' '}
            <time dateTime={preview.ticketCountAsOf}>
              {formatCountBasis(preview.ticketCountAsOf)} 기준
            </time>{' '}
            · 조회 권한이 있는 티켓만 표시합니다.
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
            {busy === 'preview' ? '미리보기 중…' : '미리보기'}
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
  catalog,
  conditions,
  label,
  onChange,
}: {
  catalog?: ViewConfigurationCatalog
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
            onChange={(event) => {
              const selected = event.target.value
              const custom = selected.startsWith('CUSTOM_FIELD:')
              const field = custom
                ? 'CUSTOM_FIELD'
                : (selected as SavedViewConditionField)
              update(index, {
                field,
                ...(custom ? { fieldKey: selected.slice(13) } : {}),
                operator: configurationField(field)
                  ? 'EQUALS'
                  : condition.operator,
                values: [],
              })
            }}
            value={
              condition.field === 'CUSTOM_FIELD'
                ? `CUSTOM_FIELD:${condition.fieldKey}`
                : condition.field
            }
          >
            {FIELDS.map((field) => (
              <option key={field} value={field}>
                {(
                  {
                    TAG: '태그',
                    FORM: '접수 폼',
                    CUSTOM_STATUS: '업무 상태',
                  } as Record<string, string>
                )[field] ?? field}
              </option>
            ))}
            {catalog?.fields.map((field) => (
              <option
                key={field.machineKey}
                value={`CUSTOM_FIELD:${field.machineKey}`}
              >
                {field.label}
              </option>
            ))}
            {condition.field === 'CUSTOM_FIELD' &&
            !catalog?.fields.some(
              (field) => field.machineKey === condition.fieldKey,
            ) ? (
              <option value={`CUSTOM_FIELD:${condition.fieldKey}`}>
                사용 불가 필드: {condition.fieldKey}
              </option>
            ) : null}
          </SeedSelect>
          <SeedSelect
            aria-label={`${label} ${index + 1} 연산자`}
            onChange={(event) => {
              const operator = event.target.value as SavedViewConditionOperator
              update(index, {
                ...condition,
                operator,
                values: operatorNeedsNoValues(operator)
                  ? []
                  : ['EQUALS', 'NOT_EQUALS'].includes(operator)
                    ? condition.values.slice(0, 1)
                    : condition.values,
              })
            }}
            value={condition.operator}
          >
            {(configurationField(condition.field)
              ? OPERATORS.slice(0, 4)
              : OPERATORS
            ).map((operator) => (
              <option key={operator} value={operator}>
                {
                  {
                    EQUALS: '같음',
                    NOT_EQUALS: '다름',
                    IN: '하나라도 포함',
                    NOT_IN: '모두 제외',
                    IS_CURRENT_ACTOR: '현재 상담사',
                    IS_UNASSIGNED: '미배정',
                    IS_CURRENT_ACTOR_GROUP: '내 그룹',
                    LESS_THAN_SOLVED: '해결 전',
                    WITHIN_LAST_DAYS: '최근 일수',
                  }[operator]
                }
              </option>
            ))}
          </SeedSelect>
          <ConditionValues
            condition={condition}
            catalog={catalog}
            label={`${label} ${index + 1} 값`}
            onChange={(values) => update(index, { ...condition, values })}
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

function configurationField(field: SavedViewConditionField) {
  return ['TAG', 'FORM', 'CUSTOM_STATUS', 'CUSTOM_FIELD'].includes(field)
}

function ConditionValues({
  condition,
  catalog,
  label,
  onChange,
}: {
  condition: SavedViewCondition
  catalog?: ViewConfigurationCatalog
  label: string
  onChange: (values: string[]) => void
}) {
  const field = catalog?.fields.find(
    (item) => item.machineKey === condition.fieldKey,
  )
  let choices: ViewChoice[] | undefined
  if (condition.field === 'TAG') choices = catalog?.tags ?? []
  if (condition.field === 'FORM') choices = catalog?.forms ?? []
  if (condition.field === 'CUSTOM_STATUS') choices = catalog?.statuses ?? []
  if (condition.field === 'CUSTOM_FIELD' && field?.type === 'SINGLE_SELECT')
    choices = field.options
  if (condition.field === 'CUSTOM_FIELD' && field?.type === 'CHECKBOX')
    choices = [
      { id: 'true', label: '선택됨' },
      { id: 'false', label: '선택 안 됨' },
    ]
  if (choices) {
    const available = [
      ...choices,
      ...condition.values
        .filter((id) => !choices!.some((choice) => choice.id === id))
        .map((id) => ({ id, label: `현재 선택 (사용 중지): ${id}` })),
    ]
    if (['IN', 'NOT_IN'].includes(condition.operator))
      return (
        <fieldset>
          <legend>{label}</legend>
          {available.length ? (
            available.map((choice) => (
              <SeedCheckbox
                key={choice.id}
                label={choice.label}
                checked={condition.values.includes(choice.id)}
                onChange={(event) =>
                  onChange(
                    event.target.checked
                      ? [...condition.values, choice.id]
                      : condition.values.filter((value) => value !== choice.id),
                  )
                }
              />
            ))
          ) : (
            <p>선택할 항목이 없습니다.</p>
          )}
        </fieldset>
      )
    return (
      <SeedSelect
        aria-label={label}
        value={condition.values[0] ?? ''}
        onChange={(event) =>
          onChange(event.target.value ? [event.target.value] : [])
        }
      >
        <option value="">값 선택</option>
        {available.map((choice) => (
          <option key={choice.id} value={choice.id}>
            {choice.label}
          </option>
        ))}
      </SeedSelect>
    )
  }
  if (condition.field === 'CUSTOM_FIELD' && field?.type === 'SHORT_TEXT') {
    if (['IN', 'NOT_IN'].includes(condition.operator))
      return (
        <SeedTextAreaField
          aria-label={label}
          label={label}
          hint="한 줄에 값 하나씩 입력하세요. 쉼표는 값에 포함됩니다."
          rows={3}
          value={condition.values.join('\n')}
          onChange={(event) =>
            onChange(event.target.value ? event.target.value.split('\n') : [])
          }
        />
      )
    return (
      <SeedTextField
        label={label}
        value={condition.values[0] ?? ''}
        onChange={(event) =>
          onChange(event.target.value ? [event.target.value] : [])
        }
      />
    )
  }
  return (
    <input
      aria-label={label}
      disabled={operatorNeedsNoValues(condition.operator)}
      onChange={(event) =>
        onChange(
          event.target.value
            .split(',')
            .map((value) => value.trim())
            .filter(Boolean),
        )
      }
      placeholder="쉼표로 여러 값 구분"
      value={condition.values.join(', ')}
    />
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

function validate(
  definition: SavedViewDefinition,
  catalog?: ViewConfigurationCatalog,
) {
  if (!definition.name) return '보기 이름을 입력하세요.'
  if (hasIsoControlCharacters(definition.description))
    return '설명에는 제어 문자를 입력할 수 없습니다.'
  if (!definition.conditions.all.length && !definition.conditions.any.length)
    return 'all 또는 any 조건을 하나 이상 추가하세요.'
  if (!definition.columns.length) return '표시 컬럼을 하나 이상 선택하세요.'
  for (const condition of [
    ...definition.conditions.all,
    ...definition.conditions.any,
  ]) {
    if (configurationField(condition.field)) {
      if (
        !catalog ||
        (condition.field === 'CUSTOM_FIELD' &&
          !catalog.fields.some(
            (field) => field.machineKey === condition.fieldKey,
          ))
      )
        return '사용 가능한 필터를 확인한 뒤 조건을 변경하거나 삭제하세요.'
      if (!condition.values.length || condition.values.length > 10)
        return '필터 값을 1개 이상, 10개 이하로 선택하세요.'
      if (condition.values.some((value) => !value.trim()))
        return '비어 있는 필터 값을 입력하거나 삭제하세요.'
    }
  }
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
