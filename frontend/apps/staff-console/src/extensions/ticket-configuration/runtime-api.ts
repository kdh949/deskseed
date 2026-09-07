import { requestStaffResource } from '../../api/client'
import type { FieldType } from './api'
export type FieldValue = {
  booleanValue?: boolean
  numberValue?: number
  optionId?: string
  shortTextValue?: string
  longTextValue?: string
}
export type Choice = { id: string; label: string }
export type AgentField = {
  id: string
  machineKey: string
  type: FieldType
  label: string
  description: string | null
  validation: {
    minimum?: number | null
    maximum?: number | null
    minLength?: number | null
    maxLength?: number | null
  }
  visible: boolean
  editable: boolean
  required: boolean
  options: Choice[]
}
export type AgentConfiguration = {
  ticketNumber: number
  version: number
  writable: boolean
  form: { formId: string; formVersion: number; fields: AgentField[] } | null
  fieldValues: Record<string, FieldValue>
  tags: Choice[]
  statusCategory: string
  customStatus: { id: string; agentLabel: string } | null
  availableTags: Choice[]
  availableStatuses: Choice[]
}
export type ConfigurationCommand = {
  formId?: string
  formVersion?: number
  fieldValues: Record<string, FieldValue>
  addTagIds: string[]
  removeTagIds: string[]
  customStatusId?: string
  clientCommandId: string
}
const record = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)
const choices = (v: unknown) =>
  Array.isArray(v) &&
  v.every(
    (c) => record(c) && typeof c.id === 'string' && typeof c.label === 'string',
  )
export function decodeConfiguration(
  v: unknown,
): AgentConfiguration | undefined {
  if (
    !record(v) ||
    !Number.isSafeInteger(v.ticketNumber) ||
    !Number.isSafeInteger(v.version) ||
    typeof v.writable !== 'boolean' ||
    typeof v.statusCategory !== 'string' ||
    !record(v.fieldValues) ||
    !choices(v.tags) ||
    !choices(v.availableTags) ||
    !choices(v.availableStatuses)
  )
    return undefined
  if (
    v.form !== null &&
    (!record(v.form) ||
      typeof v.form.formId !== 'string' ||
      !Number.isSafeInteger(v.form.formVersion) ||
      !Array.isArray(v.form.fields) ||
      !v.form.fields.every(
        (f) =>
          record(f) &&
          typeof f.id === 'string' &&
          typeof f.machineKey === 'string' &&
          typeof f.label === 'string' &&
          [
            'SHORT_TEXT',
            'LONG_TEXT',
            'NUMBER',
            'CHECKBOX',
            'SINGLE_SELECT',
          ].includes(String(f.type)) &&
          record(f.validation) &&
          ['visible', 'editable', 'required'].every(
            (key) => typeof f[key] === 'boolean',
          ) &&
          choices(f.options),
      ))
  )
    return undefined
  const normalized: Record<string, FieldValue> = {}
  for (const [key, raw] of Object.entries(v.fieldValues)) {
    if (!record(raw)) return undefined
    const entries = Object.entries(raw).filter(
      ([, value]) => value !== null && value !== undefined,
    )
    if (entries.length !== 1) return undefined
    const entry = entries[0]!
    const [type, value] = entry
    if (
      type === 'booleanValue'
        ? typeof value !== 'boolean'
        : type === 'numberValue'
          ? typeof value !== 'number' || !Number.isFinite(value)
          : !['optionId', 'shortTextValue', 'longTextValue'].includes(type) ||
            typeof value !== 'string'
    )
      return undefined
    normalized[key] = { [type]: value }
  }
  return { ...v, fieldValues: normalized } as AgentConfiguration
}
export const getConfiguration = (ticketNumber: number) =>
  requestStaffResource(
    `/api/v1/agent/tickets/${ticketNumber}/configuration`,
    decodeConfiguration,
  )
export const projectConfiguration = (
  ticketNumber: number,
  fieldValues: Record<string, FieldValue>,
  customStatusId?: string,
) =>
  requestStaffResource(
    `/api/v1/agent/tickets/${ticketNumber}/configuration/projection`,
    decodeConfiguration,
    {
      method: 'POST',
      body: { fieldValues, ...(customStatusId ? { customStatusId } : {}) },
    },
  )
export const saveConfiguration = (
  ticketNumber: number,
  version: number,
  body: ConfigurationCommand,
) =>
  requestStaffResource(
    `/api/v1/agent/tickets/${ticketNumber}/configuration`,
    (v) =>
      record(v) &&
      Number.isSafeInteger(v.version) &&
      typeof v.replayed === 'boolean'
        ? { version: Number(v.version), replayed: v.replayed }
        : undefined,
    { method: 'PUT', body, version },
  )
