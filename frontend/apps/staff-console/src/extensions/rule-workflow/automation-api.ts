import { requestStaffResource } from '../../api/client'
export type AutomationDraft = {
  name: string
  solvedAgeMinutes: number
  actionType: 'CLOSE_TICKET'
}
export type Automation = AutomationDraft & {
  id: string
  position: number
  currentVersion: number
  activeVersion: number | null
  aggregateVersion: number
  createdAt: string
  updatedAt: string
}
export type AutomationPreview = {
  ticketNumber: number
  automationId: string
  automationVersion: number
  status: string
  solvedAt?: string | null
  eligibleAt?: string | null
  matched: boolean
  proposedAction: 'CLOSE_TICKET'
}
export type AutomationHistory = {
  versions: {
    version: number
    name: string
    solvedAgeMinutes: number
    createdByDisplay: string
    createdAt: string
  }[]
  activations: {
    version: number
    state: 'ACTIVE' | 'INACTIVE'
    actorDisplay: string
    occurredAt: string
  }[]
  executions: {
    id: string
    version: number
    ticketNumber: number
    outcome: string
    auditId?: string | null
    errorCode?: string | null
    completedAt: string
  }[]
  candidates: {
    id: string
    version: number
    ticketNumber: number
    status: string
    attemptCount: number
    lastErrorCode?: string | null
    eligibleAt: string
    discoveredAt: string
  }[]
}
const record = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)
const text = (v: unknown): v is string => typeof v === 'string' && v.length > 0
const positive = (v: unknown) => Number.isSafeInteger(v) && Number(v) > 0
const optionalText = (v: unknown) => v == null || text(v)
const array = (v: unknown, valid: (item: Record<string, unknown>) => boolean) =>
  Array.isArray(v) && v.every((item) => record(item) && valid(item))
export function decodeAutomation(v: unknown): Automation | undefined {
  if (
    !record(v) ||
    !text(v.id) ||
    !text(v.name) ||
    !positive(v.position) ||
    !positive(v.currentVersion) ||
    !positive(v.aggregateVersion) ||
    (v.activeVersion != null && !positive(v.activeVersion)) ||
    !positive(v.solvedAgeMinutes) ||
    Number(v.solvedAgeMinutes) > 525600 ||
    v.actionType !== 'CLOSE_TICKET' ||
    !text(v.createdAt) ||
    !text(v.updatedAt)
  )
    return undefined
  return { ...v, activeVersion: v.activeVersion ?? null } as Automation
}
function decodeList(v: unknown) {
  if (!Array.isArray(v)) return undefined
  const items = v.map(decodeAutomation)
  return items.every((item) => item !== undefined) ? items : undefined
}
function decodePreview(v: unknown): AutomationPreview | undefined {
  if (
    !record(v) ||
    !positive(v.ticketNumber) ||
    !text(v.automationId) ||
    !positive(v.automationVersion) ||
    !text(v.status) ||
    !optionalText(v.solvedAt) ||
    !optionalText(v.eligibleAt) ||
    typeof v.matched !== 'boolean' ||
    v.proposedAction !== 'CLOSE_TICKET'
  )
    return undefined
  return v as AutomationPreview
}
function decodeHistory(v: unknown): AutomationHistory | undefined {
  if (
    !record(v) ||
    !array(
      v.versions,
      (x) =>
        positive(x.version) &&
        text(x.name) &&
        positive(x.solvedAgeMinutes) &&
        text(x.createdByDisplay) &&
        text(x.createdAt),
    ) ||
    !array(
      v.activations,
      (x) =>
        positive(x.version) &&
        ['ACTIVE', 'INACTIVE'].includes(String(x.state)) &&
        text(x.actorDisplay) &&
        text(x.occurredAt),
    ) ||
    !array(
      v.executions,
      (x) =>
        text(x.id) &&
        positive(x.version) &&
        positive(x.ticketNumber) &&
        ['CLOSED', 'SKIPPED_STATE_CHANGED', 'FAILED'].includes(
          String(x.outcome),
        ) &&
        optionalText(x.auditId) &&
        optionalText(x.errorCode) &&
        text(x.completedAt),
    ) ||
    !array(
      v.candidates,
      (x) =>
        text(x.id) &&
        positive(x.version) &&
        positive(x.ticketNumber) &&
        [
          'PENDING',
          'LEASED',
          'SUCCEEDED',
          'SKIPPED',
          'RETRY_SCHEDULED',
          'DEAD_LETTERED',
        ].includes(String(x.status)) &&
        Number.isSafeInteger(x.attemptCount) &&
        Number(x.attemptCount) >= 0 &&
        optionalText(x.lastErrorCode) &&
        text(x.eligibleAt) &&
        text(x.discoveredAt),
    )
  )
    return undefined
  return v as AutomationHistory
}
export const listAutomations = () =>
  requestStaffResource('/api/v1/admin/automations', decodeList)
export const automationVersion = (id: string, version: number) =>
  requestStaffResource(
    `/api/v1/admin/automations/${id}/versions/${version}`,
    decodeAutomation,
  )
export const automationHistory = (id: string) =>
  requestStaffResource(`/api/v1/admin/automations/${id}/history`, decodeHistory)
export const saveAutomation = (
  current: Automation | null,
  draft: AutomationDraft,
  position: number,
) =>
  requestStaffResource(
    current
      ? `/api/v1/admin/automations/${current.id}/versions`
      : '/api/v1/admin/automations',
    decodeAutomation,
    {
      method: 'POST',
      body: current ? draft : { ...draft, position },
      ...(current ? { version: current.aggregateVersion } : {}),
    },
  )
export const activateAutomation = (current: Automation, version: number) =>
  requestStaffResource(
    `/api/v1/admin/automations/${current.id}/activation`,
    decodeAutomation,
    { method: 'PUT', body: { version }, version: current.aggregateVersion },
  )
export const deactivateAutomation = (current: Automation) =>
  requestStaffResource(
    `/api/v1/admin/automations/${current.id}/activation`,
    decodeAutomation,
    { method: 'DELETE', version: current.aggregateVersion },
  )
export const previewAutomation = (
  id: string,
  version: number,
  ticketNumber: number,
) =>
  requestStaffResource(
    `/api/v1/admin/automations/${id}/versions/${version}/dry-run`,
    decodePreview,
    { method: 'POST', body: { ticketNumber } },
  )
