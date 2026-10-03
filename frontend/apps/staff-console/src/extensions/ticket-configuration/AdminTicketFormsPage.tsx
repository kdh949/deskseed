import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  SeedButton,
  SeedCheckbox,
  SeedSelectField,
  SeedTextField,
  SeedFeedbackState,
  SeedSkeletonRows,
  SeedDrawer,
} from '../../design-system/canonical'
import { AdminTicketFormPreview } from './AdminTicketFormPreview'
import { ConfigurationError } from './AdminTicketFieldsPage'
import {
  listFields,
  listForms,
  listOptions,
  saveForm,
  transitionForm,
  type FieldDefinition,
  type FormDraft,
  type FormPlacement,
  type FormRule,
  type TicketForm,
} from './api'
import { useAdminDraftExit } from '../../features/admin/useAdminDraftExit'
import './configuration.css'

const EMPTY_FORM: FormDraft = {
  name: '',
  defaultForCustomer: false,
  defaultForAgent: false,
  placements: [],
  conditionalRules: [],
  allowedCustomStatusIds: [],
}
const LIFECYCLE = { DRAFT: '초안', PUBLISHED: '발행됨', ARCHIVED: '보관됨' }

export function AdminTicketFormsPage() {
  const client = useQueryClient()
  const forms = useQuery({
    queryKey: ['admin-ticket-forms'],
    queryFn: listForms,
    retry: false,
  })
  const fields = useQuery({
    queryKey: ['admin-ticket-fields'],
    queryFn: listFields,
    retry: false,
  })
  const [editing, setEditing] = useState<TicketForm | 'new' | null>(null)
  const [preview, setPreview] = useState<TicketForm | null>(null)
  const trigger = useRef<HTMLButtonElement | null>(null)
  const [search, setSearch] = useState('')
  const [state, setState] = useState('all')
  const shown =
    forms.data?.filter(
      (form) =>
        form.name.toLowerCase().includes(search.trim().toLowerCase()) &&
        (state === 'all' || form.lifecycle === state),
    ) ?? []
  const close = () => {
    setEditing(null)
    setPreview(null)
    requestAnimationFrame(() => trigger.current?.focus())
  }

  const transition = useMutation({
    mutationFn: ({
      form,
      action,
    }: {
      form: TicketForm
      action: 'publish' | 'archive'
    }) => transitionForm(form, action),
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ['admin-ticket-forms'] }),
  })
  return (
    <main className="configuration-page">
      <header className="configuration-header">
        <h1>티켓 폼</h1>
        <p>
          필드와 조건을 설정하고 발행하세요. 수정한 초안은 다시 발행하기 전까지
          접수에 적용되지 않습니다.
        </p>
      </header>
      <div
        className="configuration-overview"
        hidden={Boolean(editing || preview)}
      >
        <div className="configuration-actions">
          <SeedButton
            disabled={!fields.data?.some((f) => f.active)}
            onClick={(event) => {
              trigger.current = event.currentTarget
              setEditing('new')
            }}
          >
            폼 만들기
          </SeedButton>
          <SeedButton
            onClick={() => {
              void forms.refetch()
              void fields.refetch()
            }}
          >
            목록 새로고침
          </SeedButton>
        </div>
        <div className="configuration-toolbar">
          <SeedTextField
            label="폼 검색"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          <SeedSelectField
            label="발행 상태"
            value={state}
            onChange={(event) => setState(event.target.value)}
          >
            <option value="all">전체</option>
            {Object.entries(LIFECYCLE).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </SeedSelectField>
          <SeedButton
            onClick={() => {
              setSearch('')
              setState('all')
            }}
          >
            검색 초기화
          </SeedButton>
        </div>
        {forms.isSuccess && (
          <p role="status">
            불러온 {forms.data.length}개 중 {shown.length}개
          </p>
        )}
        <ConfigurationError
          error={forms.error || fields.error || transition.error}
        />
        {forms.isPending || fields.isPending ? (
          <SeedSkeletonRows />
        ) : (
          <ul className="configuration-list">
            {shown.map((form) => (
              <li key={form.id}>
                <div>
                  <strong>{form.name}</strong>
                  <p>
                    {LIFECYCLE[form.lifecycle]} · 설정 버전 {form.version}
                    {form.defaultForCustomer
                      ? ' · 고객 기본 폼'
                      : ' · 고객 기본 아님'}
                    {form.defaultForAgent
                      ? ' · 상담사 기본 폼'
                      : ' · 상담사 기본 아님'}
                  </p>
                </div>
                <div className="configuration-actions">
                  <SeedButton
                    onClick={(event) => {
                      trigger.current = event.currentTarget
                      setEditing(form)
                    }}
                  >
                    편집: {form.name}
                  </SeedButton>
                  <SeedButton
                    disabled={form.lifecycle === 'ARCHIVED'}
                    onClick={(event) => {
                      trigger.current = event.currentTarget
                      setPreview(form)
                    }}
                  >
                    저장본 미리보기: {form.name}
                  </SeedButton>
                  <SeedButton
                    disabled={
                      transition.isPending || form.lifecycle === 'ARCHIVED'
                    }
                    onClick={() =>
                      transition.mutate({ form, action: 'publish' })
                    }
                  >
                    발행: {form.name}
                  </SeedButton>
                  <SeedButton
                    disabled={
                      transition.isPending || form.lifecycle === 'ARCHIVED'
                    }
                    onClick={() =>
                      transition.mutate({ form, action: 'archive' })
                    }
                  >
                    보관: {form.name}
                  </SeedButton>
                </div>
              </li>
            ))}
          </ul>
        )}
        {forms.data?.length === 0 && (
          <SeedFeedbackState
            kind="empty"
            title="등록된 폼이 없습니다."
            description="필드를 먼저 만든 후 고객 또는 상담사 기본 폼을 설정하세요."
          />
        )}
        {forms.isSuccess && forms.data.length > 0 && !shown.length && (
          <SeedFeedbackState
            kind="empty"
            title="조건에 맞는 폼이 없습니다."
            description="검색어나 발행 상태를 바꿔 주세요."
          />
        )}
      </div>
      {preview && (
        <AdminTicketFormPreview
          key={preview.id}
          form={preview}
          onClose={close}
        />
      )}
      {editing && fields.data && (
        <FormEditor
          key={editing === 'new' ? 'new' : editing.id}
          fields={fields.data}
          existing={editing === 'new' ? undefined : editing}
          onClose={close}
        />
      )}
    </main>
  )
}

function FormEditor({
  fields,
  existing,
  onClose,
}: {
  fields: FieldDefinition[]
  existing?: TicketForm
  onClose: () => void
}) {
  const client = useQueryClient()
  const [draft, setDraft] = useState<FormDraft>(existing ?? EMPTY_FORM)
  const [conditionDirty, setConditionDirty] = useState(false)
  const save = useMutation({
    mutationFn: () => saveForm(draft, existing),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['admin-ticket-forms'] })
      onClose()
    },
  })
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    heading.current?.focus()
  }, [])
  const exit = useAdminDraftExit(
    conditionDirty ||
      JSON.stringify(draft) !== JSON.stringify(existing ?? EMPTY_FORM),
    save.isPending,
  )
  const selected = fields.filter((f) =>
    draft.placements.some((p) => p.fieldId === f.id),
  )
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (draft.placements.length && !conditionDirty && !save.isPending)
      save.mutate()
  }
  const toggleField = (field: FieldDefinition, checked: boolean) =>
    setDraft((current) => ({
      ...current,
      placements: checked
        ? [
            ...current.placements,
            {
              fieldId: field.id,
              order:
                Math.max(-1, ...current.placements.map((p) => p.order)) + 1,
              customer: {
                visible: field.customerVisible,
                editable: field.customerEditable,
                required: false,
              },
              agent: {
                visible: field.agentVisible,
                editable: field.agentEditable,
                required: false,
              },
            },
          ]
        : current.placements.filter((p) => p.fieldId !== field.id),
    }))
  const changePolicy = (
    placement: FormPlacement,
    actor: 'customer' | 'agent',
    key: 'visible' | 'editable' | 'required',
    checked: boolean,
  ) =>
    setDraft((current) => ({
      ...current,
      placements: current.placements.map((p) =>
        p.fieldId !== placement.fieldId
          ? p
          : {
              ...p,
              [actor]: {
                ...p[actor],
                [key]: checked,
                ...(key === 'visible' && !checked
                  ? { editable: false, required: false }
                  : {}),
              },
            },
      ),
    }))
  return (
    <form
      className="configuration-editor"
      onSubmit={submit}
      aria-label="폼 편집"
    >
      <h2 ref={heading} tabIndex={-1}>
        {existing ? `${existing.name} 편집` : '새 폼'}
      </h2>
      <ConfigurationError error={save.error} />
      <fieldset disabled={save.isPending || existing?.lifecycle === 'ARCHIVED'}>
        <legend>접수 폼 설정</legend>
        <SeedTextField
          label="폼 이름"
          required
          maxLength={120}
          value={draft.name}
          onChange={(e) => setDraft({ ...draft, name: e.target.value })}
        />
        <SeedCheckbox
          label="고객 기본 폼"
          checked={draft.defaultForCustomer}
          onChange={(e) =>
            setDraft({ ...draft, defaultForCustomer: e.target.checked })
          }
        />
        <SeedCheckbox
          label="상담사 기본 폼"
          checked={draft.defaultForAgent}
          onChange={(e) =>
            setDraft({ ...draft, defaultForAgent: e.target.checked })
          }
        />
        <p>
          목록에서 선택한 순서로 표시됩니다. 표시와 입력 권한은 필드 정의가
          허용한 범위를 넘을 수 없습니다.
        </p>
        {fields
          .filter((f) => f.active || selected.includes(f))
          .map((field) => {
            const placement = draft.placements.find(
              (p) => p.fieldId === field.id,
            )
            return (
              <fieldset key={field.id}>
                <legend>{field.staffLabel}</legend>
                <SeedCheckbox
                  label={`${field.staffLabel} 포함`}
                  checked={!!placement}
                  onChange={(e) => toggleField(field, e.target.checked)}
                />
                {placement &&
                  (['customer', 'agent'] as const).map((actor) => (
                    <fieldset key={actor}>
                      <legend>
                        {actor === 'customer' ? '고객' : '상담사'}
                      </legend>
                      {(['visible', 'editable', 'required'] as const).map(
                        (key) => (
                          <SeedCheckbox
                            key={key}
                            label={`${field.staffLabel} ${actor === 'customer' ? '고객' : '상담사'} ${{ visible: '표시', editable: '입력 허용', required: '필수' }[key]}`}
                            checked={placement[actor][key]}
                            disabled={
                              (key !== 'visible' &&
                                !placement[actor].visible) ||
                              (actor === 'customer' &&
                                !field.customerVisible) ||
                              (key === 'editable' &&
                                !(actor === 'customer'
                                  ? field.customerEditable
                                  : field.agentEditable))
                            }
                            onChange={(e) =>
                              changePolicy(
                                placement,
                                actor,
                                key,
                                e.target.checked,
                              )
                            }
                          />
                        ),
                      )}
                    </fieldset>
                  ))}
              </fieldset>
            )
          })}
        <h3>조건부 동작</h3>
        <p>
          조건에 맞을 때 선택한 필드의 표시 또는 필수 여부를 바꿉니다. 저장 시
          서버가 잘못된 참조와 순환 조건을 검사합니다.
        </p>
        <ol>
          {draft.conditionalRules.map((rule) => (
            <li key={rule.id}>
              {ruleLabel(rule, fields)}{' '}
              <SeedButton
                onClick={() =>
                  setDraft({
                    ...draft,
                    conditionalRules: draft.conditionalRules.filter(
                      (r) => r.id !== rule.id,
                    ),
                  })
                }
              >
                조건 삭제 {draft.conditionalRules.indexOf(rule) + 1}
              </SeedButton>
            </li>
          ))}
        </ol>
        <RuleEditor
          fields={selected}
          onDraftChange={setConditionDirty}
          onAdd={(rule) =>
            setDraft({
              ...draft,
              conditionalRules: [
                ...draft.conditionalRules,
                {
                  ...rule,
                  priority:
                    Math.max(
                      -1,
                      ...draft.conditionalRules.map((r) => r.priority),
                    ) + 1,
                },
              ],
            })
          }
        />
        <div className="configuration-actions">
          <SeedButton
            type="submit"
            variant="primary"
            disabled={!draft.placements.length || conditionDirty}
          >
            {save.isPending ? '저장 중…' : '폼 초안 저장'}
          </SeedButton>
        </div>
      </fieldset>
      <SeedButton
        disabled={save.isPending}
        onClick={() => exit.request(onClose)}
      >
        편집 닫기
      </SeedButton>
      <SeedDrawer
        open={exit.open}
        onClose={exit.cancel}
        title="작성한 내용을 버릴까요?"
      >
        <p>저장하지 않은 폼 변경 사항이 있습니다.</p>
        <SeedButton onClick={exit.cancel}>계속 편집</SeedButton>
        <SeedButton disabled={save.isPending} onClick={exit.discard}>
          변경 버리기
        </SeedButton>
      </SeedDrawer>
    </form>
  )
}

function ruleLabel(rule: FormRule, fields: FieldDefinition[]) {
  const effects = rule.effects
    .map(
      (e) =>
        `${fields.find((f) => f.id === e.fieldId)?.staffLabel ?? '제거된 필드'}: ${{ SHOW: '표시', HIDE: '숨김', REQUIRED: '필수', OPTIONAL: '선택', READ_ONLY: '읽기 전용', EDITABLE: '입력 허용' }[e.behavior] ?? e.behavior}`,
    )
    .join(', ')
  const root = rule.condition.root as {
    kind?: string
    config?: { fact?: string; equals?: string }
  }
  return `${fields.find((f) => `field.${f.id}` === root.config?.fact)?.staffLabel ?? '기존 조건'} = ${root.config?.equals ?? '복합 조건'} → ${effects}`
}

function RuleEditor({
  fields,
  onAdd,
  onDraftChange,
}: {
  fields: FieldDefinition[]
  onAdd: (rule: FormRule) => void
  onDraftChange: (dirty: boolean) => void
}) {
  const [source, setSource] = useState('')
  const [value, setValue] = useState('')
  const [target, setTarget] = useState('')
  const [behavior, setBehavior] = useState('REQUIRED')
  const dirty = Boolean(source || value || target || behavior !== 'REQUIRED')
  useEffect(() => onDraftChange(dirty), [dirty, onDraftChange])
  const reset = () => {
    setSource('')
    setValue('')
    setTarget('')
    setBehavior('REQUIRED')
  }
  const field = fields.find((f) => f.id === source)
  const options = useQuery({
    queryKey: ['ticket-field-options', source],
    queryFn: () => listOptions(source),
    enabled: field?.type === 'SINGLE_SELECT',
    retry: false,
  })
  const canAdd =
    !!field &&
    !field.sensitive &&
    !!value &&
    source !== target &&
    fields.some((f) => f.id === target) &&
    (field.type !== 'SINGLE_SELECT' ||
      options.data?.some((o) => o.id === value && o.active))
  return (
    <fieldset>
      <legend>조건 추가</legend>
      <SeedSelectField
        label="조건 필드"
        value={source}
        onChange={(e) => {
          setSource(e.target.value)
          setValue('')
        }}
      >
        <option value="">필드 선택</option>
        {fields
          .filter((f) => !f.sensitive)
          .map((f) => (
            <option key={f.id} value={f.id}>
              {f.staffLabel}
            </option>
          ))}
      </SeedSelectField>
      {field?.type === 'SINGLE_SELECT' || field?.type === 'CHECKBOX' ? (
        <SeedSelectField
          label="일치하는 값"
          value={value}
          onChange={(e) => setValue(e.target.value)}
        >
          <option value="">값 선택</option>
          {field.type === 'CHECKBOX' ? (
            <>
              <option value="true">선택함</option>
              <option value="false">선택하지 않음</option>
            </>
          ) : (
            options.data
              ?.filter((o) => o.active)
              .map((o) => (
                <option key={o.id} value={o.id}>
                  {o.staffLabel}
                </option>
              ))
          )}
        </SeedSelectField>
      ) : (
        <SeedTextField
          label="일치하는 값"
          maxLength={120}
          value={value}
          onChange={(e) => setValue(e.target.value)}
        />
      )}
      <ConfigurationError error={options.error} />
      <SeedSelectField
        label="변경할 필드"
        value={target}
        onChange={(e) => setTarget(e.target.value)}
      >
        <option value="">필드 선택</option>
        {fields
          .filter((f) => f.id !== source)
          .map((f) => (
            <option key={f.id} value={f.id}>
              {f.staffLabel}
            </option>
          ))}
      </SeedSelectField>
      <SeedSelectField
        label="조건 충족 시 동작"
        value={behavior}
        onChange={(e) => setBehavior(e.target.value)}
      >
        {Object.entries({
          SHOW: '표시',
          HIDE: '숨김',
          REQUIRED: '필수',
          OPTIONAL: '선택',
          READ_ONLY: '읽기 전용',
          EDITABLE: '입력 허용',
        }).map(([key, label]) => (
          <option key={key} value={key}>
            {label}
          </option>
        ))}
      </SeedSelectField>
      <SeedButton
        disabled={!canAdd}
        onClick={() => {
          if (canAdd) {
            onAdd({
              id: crypto.randomUUID(),
              priority: 0,
              condition: {
                schemaVersion: 1,
                root: {
                  kind: 'LEAF',
                  typeKey: 'ticket.form.fact-equals',
                  schemaVersion: 1,
                  config: { fact: `field.${source}`, equals: value },
                },
              },
              effects: [{ fieldId: target, behavior }],
            })
            reset()
          }
        }}
      >
        조건 추가
      </SeedButton>
      {dirty && (
        <>
          <p>작성 중인 조건을 추가하거나 지운 뒤 폼을 저장하세요.</p>
          <SeedButton onClick={reset}>작성 중인 조건 지우기</SeedButton>
        </>
      )}
    </fieldset>
  )
}
