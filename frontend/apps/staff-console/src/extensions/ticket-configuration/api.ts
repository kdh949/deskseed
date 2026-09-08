import { requestStaffResource } from '../../api/client'

export const FIELD_TYPES = {
  SHORT_TEXT: '짧은 텍스트',
  LONG_TEXT: '긴 텍스트',
  NUMBER: '숫자',
  CHECKBOX: '체크박스',
  SINGLE_SELECT: '단일 선택',
} as const
export type FieldType = keyof typeof FIELD_TYPES
export type FieldDraft = {
  machineKey: string
  type: FieldType
  staffLabel: string
  customerLabel: string | null
  customerVisible: boolean
  customerEditable: boolean
  agentVisible: boolean
  agentEditable: boolean
  searchable: boolean
  analyticsEligible: boolean
  sensitive: boolean
}
export type FieldDefinition = FieldDraft & {
  id: string
  version: number
  active: boolean
}
export type FieldOption = {
  id: string
  machineKey: string
  staffLabel: string
  customerLabel: string | null
  active: boolean
  order: number
  version: number
}
export type ActorPolicy = {
  visible: boolean
  editable: boolean
  required: boolean
}
export type FormPlacement = {
  fieldId: string
  order: number
  customer: ActorPolicy
  agent: ActorPolicy
}
export type FormRule = {
  id: string
  priority: number
  condition: { schemaVersion: number; root: unknown }
  effects: { fieldId: string; behavior: string }[]
}
export type FormDraft = {
  name: string
  description?: string | null
  defaultForCustomer: boolean
  defaultForAgent: boolean
  placements: FormPlacement[]
  conditionalRules: FormRule[]
  allowedCustomStatusIds: string[]
}
export type TicketForm = FormDraft & {
  id: string
  version: number
  lifecycle: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
}

const record = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)
const identity = (v: Record<string, unknown>) =>
  typeof v.id === 'string' &&
  typeof v.version === 'number' &&
  Number.isSafeInteger(v.version) &&
  v.version > 0
export function decodeField(v: unknown): FieldDefinition | undefined {
  if (
    !record(v) ||
    !identity(v) ||
    typeof v.machineKey !== 'string' ||
    typeof v.type !== 'string' ||
    !(v.type in FIELD_TYPES) ||
    typeof v.staffLabel !== 'string' ||
    !(v.customerLabel === null || typeof v.customerLabel === 'string')
  )
    return undefined
  if (
    ![
      'active',
      'customerVisible',
      'customerEditable',
      'agentVisible',
      'agentEditable',
      'searchable',
      'analyticsEligible',
      'sensitive',
    ].every((k) => typeof v[k] === 'boolean')
  )
    return undefined
  return v as FieldDefinition
}
export function decodeOption(v: unknown): FieldOption | undefined {
  if (
    !record(v) ||
    !identity(v) ||
    typeof v.machineKey !== 'string' ||
    typeof v.staffLabel !== 'string' ||
    !(v.customerLabel === null || typeof v.customerLabel === 'string') ||
    typeof v.active !== 'boolean' ||
    typeof v.order !== 'number'
  )
    return undefined
  return v as FieldOption
}
const policy = (v: unknown) =>
  record(v) &&
  ['visible', 'editable', 'required'].every((k) => typeof v[k] === 'boolean')
export function decodeForm(v: unknown): TicketForm | undefined {
  if (
    !record(v) ||
    !identity(v) ||
    typeof v.name !== 'string' ||
    (v.description != null && typeof v.description !== 'string') ||
    !['DRAFT', 'PUBLISHED', 'ARCHIVED'].includes(String(v.lifecycle)) ||
    typeof v.defaultForCustomer !== 'boolean' ||
    typeof v.defaultForAgent !== 'boolean'
  )
    return undefined
  if (
    !Array.isArray(v.placements) ||
    !v.placements.every(
      (p) =>
        record(p) &&
        typeof p.fieldId === 'string' &&
        typeof p.order === 'number' &&
        policy(p.customer) &&
        policy(p.agent),
    )
  )
    return undefined
  if (
    !Array.isArray(v.conditionalRules) ||
    !v.conditionalRules.every(
      (r) =>
        record(r) &&
        typeof r.id === 'string' &&
        typeof r.priority === 'number' &&
        record(r.condition) &&
        r.condition.schemaVersion === 1 &&
        record(r.condition.root) &&
        Array.isArray(r.effects) &&
        r.effects.every(
          (e) =>
            record(e) &&
            typeof e.fieldId === 'string' &&
            typeof e.behavior === 'string',
        ),
    )
  )
    return undefined
  if (
    !Array.isArray(v.allowedCustomStatusIds) ||
    !v.allowedCustomStatusIds.every((id) => typeof id === 'string')
  )
    return undefined
  return v as TicketForm
}
export function listDecoder<T>(decode: (value: unknown) => T | undefined) {
  return (value: unknown): T[] | undefined => {
    if (!Array.isArray(value)) return undefined
    const items = value.map(decode)
    return items.some((item) => item === undefined) ? undefined : (items as T[])
  }
}
export const listFields = () =>
  requestStaffResource('/api/v1/admin/ticket-fields', listDecoder(decodeField))
export const saveField = (draft: FieldDraft, existing?: FieldDefinition) =>
  requestStaffResource(
    existing
      ? `/api/v1/admin/ticket-fields/${existing.id}`
      : '/api/v1/admin/ticket-fields',
    decodeField,
    {
      method: existing ? 'PUT' : 'POST',
      body: {
        ...(existing ? {} : { machineKey: draft.machineKey, type: draft.type }),
        staffLabel: draft.staffLabel,
        customerLabel: draft.customerLabel,
        customerVisible: draft.customerVisible,
        customerEditable: draft.customerEditable,
        agentVisible: draft.agentVisible,
        agentEditable: draft.agentEditable,
        searchable: draft.searchable,
        analyticsEligible: draft.analyticsEligible,
        sensitive: draft.sensitive,
      },
      version: existing?.version,
    },
  )
export const activateField = (field: FieldDefinition) =>
  requestStaffResource(
    `/api/v1/admin/ticket-fields/${field.id}/activation`,
    decodeField,
    { method: 'PUT', body: { active: !field.active }, version: field.version },
  )
export const listOptions = (id: string) =>
  requestStaffResource(
    `/api/v1/admin/ticket-fields/${id}/options`,
    listDecoder(decodeOption),
  )
export const saveOption = (
  fieldId: string,
  body: {
    machineKey: string
    staffLabel: string
    customerLabel: string | null
    order: number
    active?: boolean
  },
  existing?: FieldOption,
) =>
  requestStaffResource(
    existing
      ? `/api/v1/admin/ticket-fields/${fieldId}/options/${existing.id}`
      : `/api/v1/admin/ticket-fields/${fieldId}/options`,
    decodeOption,
    {
      method: existing ? 'PUT' : 'POST',
      body: {
        ...(existing
          ? { active: body.active ?? existing.active }
          : { machineKey: body.machineKey, order: body.order }),
        staffLabel: body.staffLabel,
        customerLabel: body.customerLabel,
      },
      version: existing?.version,
    },
  )
export const listForms = () =>
  requestStaffResource('/api/v1/admin/ticket-forms', listDecoder(decodeForm))
export const saveForm = (draft: FormDraft, existing?: TicketForm) =>
  requestStaffResource(
    existing
      ? `/api/v1/admin/ticket-forms/${existing.id}`
      : '/api/v1/admin/ticket-forms',
    decodeForm,
    {
      method: existing ? 'PUT' : 'POST',
      body: {
        name: draft.name,
        description: draft.description ?? null,
        defaultForCustomer: draft.defaultForCustomer,
        defaultForAgent: draft.defaultForAgent,
        placements: draft.placements,
        conditionalRules: draft.conditionalRules,
        allowedCustomStatusIds: draft.allowedCustomStatusIds,
      },
      version: existing?.version,
    },
  )
export const transitionForm = (
  form: TicketForm,
  transition: 'publish' | 'archive',
) =>
  requestStaffResource(
    `/api/v1/admin/ticket-forms/${form.id}/${transition}`,
    decodeForm,
    { method: 'POST', version: form.version },
  )
