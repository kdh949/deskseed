import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import {
  SeedButton,
  SeedCheckbox,
  SeedSelectField,
  SeedTextField,
  SeedFeedbackState,
  SeedSkeletonRows,
  SeedNotice,
} from '../../design-system/canonical'
import {
  FIELD_TYPES,
  activateField,
  listFields,
  listOptions,
  saveField,
  saveOption,
  type FieldDefinition,
  type FieldDraft,
  type FieldType,
} from './api'
import './configuration.css'

const EMPTY_FIELD: FieldDraft = {
  machineKey: '',
  type: 'SHORT_TEXT',
  staffLabel: '',
  customerLabel: '',
  customerVisible: false,
  customerEditable: false,
  agentVisible: true,
  agentEditable: true,
  searchable: false,
  analyticsEligible: false,
  sensitive: false,
}

export function ConfigurationError({ error }: { error: unknown }) {
  if (!error) return null
  const status = error instanceof ApiError ? error.status : undefined
  return (
    <SeedNotice
      tone={status === 409 || status === 412 ? 'warning' : 'danger'}
      title={
        status === 403
          ? '이 작업을 수행할 권한이 없습니다.'
          : status === 409 || status === 412
            ? '다른 변경 사항이 있습니다. 입력을 확인해 주세요.'
            : '요청을 완료하지 못했습니다.'
      }
    >
      {status === 409 || status === 412
        ? '현재 입력은 유지됩니다. 목록을 새로고침하고 편집을 다시 열어 최신 값과 비교해 주세요.'
        : error instanceof ApiError
          ? error.message
          : '저장 결과가 확인되지 않았습니다. 목록을 확인한 후 다시 시도해 주세요.'}
    </SeedNotice>
  )
}

export function AdminTicketFieldsPage() {
  const client = useQueryClient()
  const fields = useQuery({
    queryKey: ['admin-ticket-fields'],
    queryFn: listFields,
    retry: false,
  })
  const [editing, setEditing] = useState<FieldDefinition | 'new' | null>(null)
  const [optionsFor, setOptionsFor] = useState<FieldDefinition | null>(null)
  const activation = useMutation({
    mutationFn: activateField,
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ['admin-ticket-fields'] }),
  })
  return (
    <section className="configuration-page">
      <header>
        <h1>티켓 필드</h1>
        <p>
          문의 접수와 상담에 필요한 정보를 정의합니다. 폼에 배치한 뒤 발행하면
          사용할 수 있습니다.
        </p>
      </header>
      <div className="configuration-actions">
        <SeedButton onClick={() => setEditing('new')}>필드 만들기</SeedButton>
        <SeedButton onClick={() => void fields.refetch()}>
          목록 새로고침
        </SeedButton>
      </div>
      <ConfigurationError error={fields.error || activation.error} />
      {fields.isPending ? (
        <SeedSkeletonRows />
      ) : (
        <ul className="configuration-list">
          {fields.data?.map((field) => (
            <li key={field.id}>
              <div>
                <strong>{field.staffLabel}</strong>
                <p>
                  {FIELD_TYPES[field.type]} · {field.active ? '활성' : '비활성'}{' '}
                  · {field.customerVisible ? '고객에게 표시 가능' : '직원 전용'}{' '}
                  · 버전 {field.version}
                </p>
              </div>
              <div className="configuration-actions">
                <SeedButton onClick={() => setEditing(field)}>
                  편집: {field.staffLabel}
                </SeedButton>
                {field.type === 'SINGLE_SELECT' && (
                  <SeedButton onClick={() => setOptionsFor(field)}>
                    선택지: {field.staffLabel}
                  </SeedButton>
                )}
                <SeedButton
                  disabled={activation.isPending}
                  onClick={() => activation.mutate(field)}
                >
                  {field.active ? '비활성화' : '활성화'}: {field.staffLabel}
                </SeedButton>
              </div>
            </li>
          ))}
        </ul>
      )}
      {fields.data?.length === 0 && (
        <SeedFeedbackState
          kind="empty"
          title="등록된 필드가 없습니다."
          description="주문번호나 문의 유형처럼 반복해서 수집할 정보를 추가하세요."
        />
      )}
      {editing && (
        <FieldEditor
          key={editing === 'new' ? 'new' : editing.id}
          existing={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      {optionsFor && (
        <FieldOptions
          key={optionsFor.id}
          field={optionsFor}
          onClose={() => setOptionsFor(null)}
        />
      )}
    </section>
  )
}

function FieldEditor({
  existing,
  onClose,
}: {
  existing?: FieldDefinition
  onClose: () => void
}) {
  const client = useQueryClient()
  const [draft, setDraft] = useState<FieldDraft>(existing ?? EMPTY_FIELD)
  const mutation = useMutation({
    mutationFn: () =>
      saveField(
        { ...draft, customerLabel: draft.customerLabel || null },
        existing,
      ),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['admin-ticket-fields'] })
      onClose()
    },
  })
  const set = <K extends keyof FieldDraft>(key: K, value: FieldDraft[K]) =>
    setDraft((current) => ({ ...current, [key]: value }))
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!mutation.isPending) mutation.mutate()
  }
  return (
    <form
      className="configuration-editor"
      onSubmit={submit}
      aria-label="필드 편집"
    >
      <h2>{existing ? `${existing.staffLabel} 편집` : '새 필드'}</h2>
      <ConfigurationError error={mutation.error} />
      <fieldset disabled={mutation.isPending}>
        <legend>필드 정의</legend>
        <SeedTextField
          label="식별자"
          hint="영문 소문자로 시작하며 점과 하이픈으로 구분합니다. 생성 후 변경할 수 없습니다."
          value={draft.machineKey}
          onChange={(event) => set('machineKey', event.target.value)}
          required
          maxLength={120}
          pattern="[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*"
          readOnly={!!existing}
        />
        <SeedSelectField
          label="입력 유형"
          value={draft.type}
          onChange={(event) => set('type', event.target.value as FieldType)}
          disabled={!!existing}
        >
          {Object.entries(FIELD_TYPES).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </SeedSelectField>
        <SeedTextField
          label="직원 표시 이름"
          required
          maxLength={120}
          value={draft.staffLabel}
          onChange={(event) => set('staffLabel', event.target.value)}
        />
        <SeedCheckbox
          label="고객에게 표시 가능"
          checked={draft.customerVisible}
          onChange={(event) =>
            setDraft((d) => ({
              ...d,
              customerVisible: event.target.checked,
              customerEditable: event.target.checked && d.customerEditable,
            }))
          }
        />
        {draft.customerVisible && (
          <>
            <SeedTextField
              label="고객 표시 이름"
              required
              maxLength={120}
              value={draft.customerLabel ?? ''}
              onChange={(event) => set('customerLabel', event.target.value)}
            />
            <SeedCheckbox
              label="고객 입력 허용"
              checked={draft.customerEditable}
              onChange={(event) =>
                set('customerEditable', event.target.checked)
              }
            />
          </>
        )}
        <SeedCheckbox
          label="상담사에게 표시"
          checked={draft.agentVisible}
          onChange={(event) =>
            setDraft((d) => ({
              ...d,
              agentVisible: event.target.checked,
              agentEditable: event.target.checked && d.agentEditable,
            }))
          }
        />
        {draft.agentVisible && (
          <SeedCheckbox
            label="상담사 수정 허용"
            checked={draft.agentEditable}
            onChange={(event) => set('agentEditable', event.target.checked)}
          />
        )}
        <SeedCheckbox
          label="검색과 저장 보기에서 사용"
          checked={draft.searchable}
          onChange={(event) => set('searchable', event.target.checked)}
        />
        <SeedCheckbox
          label="통계에서 사용"
          checked={draft.analyticsEligible}
          onChange={(event) => set('analyticsEligible', event.target.checked)}
        />
        <SeedCheckbox
          label="민감한 정보"
          checked={draft.sensitive}
          onChange={(event) => set('sensitive', event.target.checked)}
        />
        <div className="configuration-actions">
          <SeedButton type="submit" variant="primary">
            {mutation.isPending ? '저장 중…' : '필드 저장'}
          </SeedButton>
          <SeedButton onClick={onClose}>편집 닫기</SeedButton>
        </div>
      </fieldset>
    </form>
  )
}

function FieldOptions({
  field,
  onClose,
}: {
  field: FieldDefinition
  onClose: () => void
}) {
  const client = useQueryClient()
  const options = useQuery({
    queryKey: ['ticket-field-options', field.id],
    queryFn: () => listOptions(field.id),
    retry: false,
  })
  const [key, setKey] = useState('')
  const [label, setLabel] = useState('')
  const [customerLabel, setCustomerLabel] = useState('')
  const create = useMutation({
    mutationFn: () =>
      saveOption(field.id, {
        machineKey: key,
        staffLabel: label,
        customerLabel: customerLabel || null,
        order: Math.max(-1, ...(options.data ?? []).map((o) => o.order)) + 1,
      }),
    onSuccess: async () => {
      setKey('')
      setLabel('')
      setCustomerLabel('')
      await client.invalidateQueries({
        queryKey: ['ticket-field-options', field.id],
      })
    },
  })
  const toggle = useMutation({
    mutationFn: (option: NonNullable<typeof options.data>[number]) =>
      saveOption(field.id, { ...option, active: !option.active }, option),
    onSuccess: () =>
      client.invalidateQueries({
        queryKey: ['ticket-field-options', field.id],
      }),
  })
  return (
    <section className="configuration-editor" aria-label="필드 선택지">
      <h2>{field.staffLabel} 선택지</h2>
      <ConfigurationError
        error={options.error || create.error || toggle.error}
      />
      {options.isPending ? (
        <SeedSkeletonRows />
      ) : (
        <ul className="configuration-list">
          {options.data?.map((o) => (
            <li key={o.id}>
              <span>
                {o.staffLabel} · {o.active ? '활성' : '비활성'}
              </span>
              <SeedButton
                disabled={toggle.isPending}
                onClick={() => toggle.mutate(o)}
              >
                {o.active ? '비활성화' : '활성화'}: {o.staffLabel}
              </SeedButton>
            </li>
          ))}
        </ul>
      )}
      {options.data?.length === 0 && (
        <p>
          선택지를 추가하세요. 고객 표시 이름이 있는 선택지만 고객에게
          제공됩니다.
        </p>
      )}
      <form
        onSubmit={(event) => {
          event.preventDefault()
          if (options.data && !create.isPending) create.mutate()
        }}
      >
        <fieldset disabled={create.isPending || !options.data}>
          <legend>선택지 추가</legend>
          <SeedTextField
            label="선택지 식별자"
            required
            maxLength={80}
            pattern="[a-z][a-z0-9-]*"
            value={key}
            onChange={(event) => setKey(event.target.value)}
          />
          <SeedTextField
            label="선택지 직원 이름"
            required
            maxLength={120}
            value={label}
            onChange={(event) => setLabel(event.target.value)}
          />
          <SeedTextField
            label="선택지 고객 이름"
            maxLength={120}
            value={customerLabel}
            onChange={(event) => setCustomerLabel(event.target.value)}
          />
          <SeedButton type="submit" variant="primary">
            선택지 추가
          </SeedButton>
        </fieldset>
      </form>
      <SeedButton onClick={onClose}>선택지 닫기</SeedButton>
    </section>
  )
}
