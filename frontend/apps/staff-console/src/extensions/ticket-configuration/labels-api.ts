import { requestStaffResource } from '../../api/client'
import { listDecoder } from './api'
export type Tag = {
  id: string
  value: string
  label: string
  active: boolean
  version: number
  highCardinalityWarning: boolean
}
export type CustomStatus = {
  id: string
  machineKey: string
  agentLabel: string
  customerLabel: string | null
  statusCategory: string
  active: boolean
  order: number
  defaultForCategory: boolean
  allowedFormIds: string[]
  description: string | null
  version: number
}
const record = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)
export const decodeTag = (v: unknown): Tag | undefined =>
  record(v) &&
  typeof v.id === 'string' &&
  typeof v.value === 'string' &&
  typeof v.label === 'string' &&
  typeof v.active === 'boolean' &&
  Number.isSafeInteger(v.version)
    ? (v as Tag)
    : undefined
export const decodeStatus = (v: unknown): CustomStatus | undefined =>
  record(v) &&
  typeof v.id === 'string' &&
  typeof v.machineKey === 'string' &&
  typeof v.agentLabel === 'string' &&
  ['NEW', 'OPEN', 'PENDING', 'ON_HOLD', 'SOLVED'].includes(
    String(v.statusCategory),
  ) &&
  typeof v.active === 'boolean' &&
  Number.isSafeInteger(v.version) &&
  Number.isSafeInteger(v.order) &&
  Array.isArray(v.allowedFormIds) &&
  v.allowedFormIds.every((id) => typeof id === 'string') &&
  typeof v.defaultForCategory === 'boolean'
    ? (v as CustomStatus)
    : undefined
export const listTags = () =>
  requestStaffResource('/api/v1/admin/ticket-tags', listDecoder(decodeTag))
export const listStatuses = () =>
  requestStaffResource(
    '/api/v1/admin/ticket-statuses',
    listDecoder(decodeStatus),
  )
export const saveTag = (
  body: Pick<Tag, 'value' | 'label' | 'active'>,
  current?: Tag,
) =>
  requestStaffResource(
    `/api/v1/admin/ticket-tags${current ? `/${current.id}` : ''}`,
    decodeTag,
    { method: current ? 'PUT' : 'POST', version: current?.version, body },
  )
export const saveStatus = (
  body: Omit<CustomStatus, 'id' | 'version'>,
  current?: CustomStatus,
) =>
  requestStaffResource(
    `/api/v1/admin/ticket-statuses${current ? `/${current.id}` : ''}`,
    decodeStatus,
    { method: current ? 'PUT' : 'POST', version: current?.version, body },
  )
