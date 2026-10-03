import { requestStaffResource } from '../../api/client'
export type ViewChoice = { id: string; label: string }
export type ViewConfigurationCatalog = {
  fields: {
    id: string
    machineKey: string
    label: string
    type: 'CHECKBOX' | 'NUMBER' | 'SINGLE_SELECT' | 'SHORT_TEXT'
    options: ViewChoice[]
  }[]
  tags: ViewChoice[]
  forms: ViewChoice[]
  statuses: ViewChoice[]
}
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const choices = (value: unknown) =>
  Array.isArray(value) &&
  value.every(
    (item) =>
      record(item) &&
      typeof item.id === 'string' &&
      typeof item.label === 'string',
  )
export function decodeViewConfigurationCatalog(
  value: unknown,
): ViewConfigurationCatalog | undefined {
  if (
    !record(value) ||
    !choices(value.tags) ||
    !choices(value.forms) ||
    !choices(value.statuses) ||
    !Array.isArray(value.fields) ||
    !value.fields.every(
      (field) =>
        record(field) &&
        typeof field.id === 'string' &&
        typeof field.machineKey === 'string' &&
        typeof field.label === 'string' &&
        ['CHECKBOX', 'NUMBER', 'SINGLE_SELECT', 'SHORT_TEXT'].includes(
          String(field.type),
        ) &&
        choices(field.options),
    )
  )
    return undefined
  return value as ViewConfigurationCatalog
}
export const getViewConfigurationCatalog = () =>
  requestStaffResource(
    '/api/v1/agent/ticket-configuration/filter-catalog',
    decodeViewConfigurationCatalog,
  )
