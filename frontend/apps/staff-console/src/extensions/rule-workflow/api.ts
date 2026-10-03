import { requestStaffResource } from '../../api/client'
import type { TicketPriority } from '../../api/types'
export const EVENTS = {
  TICKET_CREATED: '티켓 생성',
  TICKET_UPDATED: '티켓 변경 (고객 재답변 포함)',
  CUSTOMER_REPLIED: '고객 재답변',
}
export const FIELDS = {
  EVENT: '이벤트',
  PRIORITY: '우선순위',
  GROUP: '담당 그룹',
  ASSIGNEE: '담당자',
  TAG: '태그',
  FORM: '최초 접수 폼',
}
export const ACTIONS = {
  SET_GROUP: '그룹 변경',
  SET_PRIORITY: '우선순위 변경',
  SET_ASSIGNEE: '담당자 변경',
  NOTIFY_UNASSIGNED_GROUP: '미배정 그룹에 알림',
  ENQUEUE_WEBHOOK: '연결된 웹훅에 알림',
}
export const PRIORITIES = {
  LOW: '낮음',
  NORMAL: '보통',
  HIGH: '높음',
  URGENT: '긴급',
}
export type RuleEvent = keyof typeof EVENTS
export type Condition = {
  group: 'ALL' | 'ANY'
  field: keyof typeof FIELDS
  operator: 'IS' | 'IS_NOT' | 'PRESENT' | 'NOT_PRESENT'
  value?: string | null
}
export type Action =
  | { type: 'SET_GROUP'; groupId: string }
  | { type: 'SET_PRIORITY'; priority: TicketPriority }
  | { type: 'SET_ASSIGNEE'; assigneeId: string | null }
  | { type: 'NOTIFY_UNASSIGNED_GROUP' }
  | { type: 'ENQUEUE_WEBHOOK'; eventType: 'ticket.trigger.executed' }
export type TriggerDraft = {
  name: string
  conditions: Condition[]
  actions: Action[]
}
export type Trigger = TriggerDraft & {
  id: string
  position: number
  currentVersion: number
  aggregateVersion: number
  activeVersion: number | null
  createdAt: string
  updatedAt: string
}
export type TriggerPreview = {
  ticketNumber: number
  triggerId: string
  triggerVersion: number
  matched: boolean
  matchedConditions: number[]
  unmatchedConditions: number[]
  proposedActions: Action['type'][]
  invariantFailures: string[]
}
export type TriggerHistory = {
  versions: {
    version: number
    name: string
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
  jobs: {
    id: string
    ticketNumber: number
    eventType: RuleEvent
    status: string
    attemptCount: number
    lastErrorCode?: string | null
    createdAt: string
  }[]
}
const record = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)
const text = (v: unknown): v is string => typeof v === 'string' && v.length > 0
const integer = (v: unknown) => Number.isSafeInteger(v) && Number(v) >= 0
const array = (v: unknown, valid: (item: Record<string, unknown>) => boolean) =>
  Array.isArray(v) && v.every((item) => record(item) && valid(item))
const action = (v: unknown) =>
  record(v) &&
  (v.type === 'SET_GROUP'
    ? text(v.groupId)
    : v.type === 'SET_PRIORITY'
      ? text(v.priority) && v.priority in PRIORITIES
      : v.type === 'SET_ASSIGNEE'
        ? v.assigneeId == null || text(v.assigneeId)
        : v.type === 'NOTIFY_UNASSIGNED_GROUP' ||
          (v.type === 'ENQUEUE_WEBHOOK' &&
            v.eventType === 'ticket.trigger.executed'))
export function decodeTrigger(v: unknown): Trigger | undefined {
  if (
    !record(v) ||
    !text(v.id) ||
    !text(v.name) ||
    !integer(v.position) ||
    !integer(v.currentVersion) ||
    !integer(v.aggregateVersion) ||
    (v.activeVersion != null && !integer(v.activeVersion)) ||
    !text(v.createdAt) ||
    !text(v.updatedAt) ||
    !array(
      v.conditions,
      (c) =>
        ['ALL', 'ANY'].includes(String(c.group)) &&
        text(c.field) &&
        c.field in FIELDS &&
        ['IS', 'IS_NOT', 'PRESENT', 'NOT_PRESENT'].includes(
          String(c.operator),
        ) &&
        (c.value == null || text(c.value)),
    ) ||
    !Array.isArray(v.actions) ||
    !v.actions.every(action)
  )
    return undefined
  return {
    ...v,
    activeVersion: v.activeVersion ?? null,
    actions: v.actions.map((a) =>
      a.type === 'SET_ASSIGNEE'
        ? { type: a.type, assigneeId: a.assigneeId ?? null }
        : a,
    ),
  } as Trigger
}
const decodeList = (v: unknown) => {
  if (!Array.isArray(v)) return undefined
  const items = v.map(decodeTrigger)
  return items.every((item) => item !== undefined) ? items : undefined
}
function decodePreview(v: unknown): TriggerPreview | undefined {
  if (
    !record(v) ||
    !integer(v.ticketNumber) ||
    !text(v.triggerId) ||
    !integer(v.triggerVersion) ||
    typeof v.matched !== 'boolean' ||
    !Array.isArray(v.matchedConditions) ||
    !v.matchedConditions.every(integer) ||
    !Array.isArray(v.unmatchedConditions) ||
    !v.unmatchedConditions.every(integer) ||
    !Array.isArray(v.proposedActions) ||
    !v.proposedActions.every((a) => text(a) && a in ACTIONS) ||
    !Array.isArray(v.invariantFailures) ||
    !v.invariantFailures.every(text)
  )
    return undefined
  return v as TriggerPreview
}
function decodeHistory(v: unknown): TriggerHistory | undefined {
  if (
    !record(v) ||
    !array(
      v.versions,
      (x) =>
        integer(x.version) &&
        text(x.name) &&
        text(x.createdByDisplay) &&
        text(x.createdAt),
    ) ||
    !array(
      v.activations,
      (x) =>
        integer(x.version) &&
        ['ACTIVE', 'INACTIVE'].includes(String(x.state)) &&
        text(x.actorDisplay) &&
        text(x.occurredAt),
    ) ||
    !array(
      v.executions,
      (x) =>
        text(x.id) &&
        integer(x.version) &&
        integer(x.ticketNumber) &&
        text(x.outcome) &&
        text(x.completedAt),
    ) ||
    !array(
      v.jobs,
      (x) =>
        text(x.id) &&
        integer(x.ticketNumber) &&
        text(x.eventType) &&
        x.eventType in EVENTS &&
        text(x.status) &&
        integer(x.attemptCount) &&
        text(x.createdAt),
    )
  )
    return undefined
  return v as TriggerHistory
}
export const listTriggers = () =>
  requestStaffResource('/api/v1/admin/triggers', decodeList)
export const triggerVersion = (id: string, version: number) =>
  requestStaffResource(
    `/api/v1/admin/triggers/${id}/versions/${version}`,
    decodeTrigger,
  )
export const triggerHistory = (id: string) =>
  requestStaffResource(`/api/v1/admin/triggers/${id}/history`, decodeHistory)
export const saveTrigger = (
  current: Trigger | null,
  draft: TriggerDraft,
  position: number,
) =>
  requestStaffResource(
    current
      ? `/api/v1/admin/triggers/${current.id}/versions`
      : '/api/v1/admin/triggers',
    decodeTrigger,
    {
      method: 'POST',
      body: current ? draft : { ...draft, position },
      ...(current ? { version: current.aggregateVersion } : {}),
    },
  )
export const activateTrigger = (current: Trigger, version: number) =>
  requestStaffResource(
    `/api/v1/admin/triggers/${current.id}/activation`,
    decodeTrigger,
    { method: 'PUT', body: { version }, version: current.aggregateVersion },
  )
export const deactivateTrigger = (current: Trigger) =>
  requestStaffResource(
    `/api/v1/admin/triggers/${current.id}/activation`,
    decodeTrigger,
    { method: 'DELETE', version: current.aggregateVersion },
  )
export const repositionTrigger = (current: Trigger, position: number) =>
  requestStaffResource(
    `/api/v1/admin/triggers/${current.id}/position`,
    decodeTrigger,
    { method: 'PUT', body: { position }, version: current.aggregateVersion },
  )
export const previewTrigger = (
  id: string,
  version: number,
  ticketNumber: number,
  eventType: RuleEvent,
) =>
  requestStaffResource(
    `/api/v1/admin/triggers/${id}/versions/${version}/dry-run`,
    decodePreview,
    { method: 'POST', body: { ticketNumber, eventType } },
  )
