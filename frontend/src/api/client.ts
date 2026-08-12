import type {
  ActorSummary,
  AgentComment,
  AgentReadIntent,
  AgentTicketStatus,
  AgentTicketDetail,
  AgentTicketFilters,
  AgentTicketPage,
  AgentTicketSummary,
  CreateStaffInput,
  CurrentStaff,
  GroupReference,
  GroupMembership,
  ProblemDetails,
  PublicComment,
  PublicRequest,
  StaffAccount,
  StaffRole,
  SubmitRequestInput,
  SubmittedRequest,
  SupportGroup,
  SavedAgentView,
  TicketHistoryItem,
  TicketPriority,
  TicketStatus,
  TicketVisibility,
} from './types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
export const STAFF_SESSION_INVALID_EVENT = 'deskseed:staff-session-invalid'
const TICKET_STATUSES = new Set<TicketStatus>([
  'NEW',
  'OPEN',
  'PENDING',
  'SOLVED',
])
const AGENT_TICKET_STATUSES = new Set<AgentTicketStatus>([
  'NEW',
  'OPEN',
  'PENDING',
  'ON_HOLD',
  'SOLVED',
  'CLOSED',
])
const ACCESS_TOKEN_MIN_LENGTH = 32
const ACCESS_TOKEN_MAX_LENGTH = 256
const TICKET_PRIORITIES = new Set<TicketPriority>([
  'LOW',
  'NORMAL',
  'HIGH',
  'URGENT',
])
const TICKET_VISIBILITIES = new Set<TicketVisibility>(['PUBLIC', 'INTERNAL'])
const ACTOR_TYPES = new Set<ActorSummary['type']>([
  'CUSTOMER',
  'STAFF',
  'INTEGRATION_CLIENT',
  'TRIGGER',
  'AUTOMATION',
  'SYSTEM',
])

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly problem?: ProblemDetails,
    readonly requestId?: string,
    readonly retryAfter?: string,
    readonly fieldErrors: Record<string, string> = {},
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isTimestamp(value: unknown): value is string {
  return isNonBlankString(value) && Number.isFinite(Date.parse(value))
}

function isTicketStatus(value: unknown): value is TicketStatus {
  return typeof value === 'string' && TICKET_STATUSES.has(value as TicketStatus)
}

function isAgentTicketStatus(value: unknown): value is AgentTicketStatus {
  return (
    typeof value === 'string' &&
    AGENT_TICKET_STATUSES.has(value as AgentTicketStatus)
  )
}

function isTicketNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isTicketPriority(value: unknown): value is TicketPriority {
  return (
    typeof value === 'string' && TICKET_PRIORITIES.has(value as TicketPriority)
  )
}

function isTicketVisibility(value: unknown): value is TicketVisibility {
  return (
    typeof value === 'string' &&
    TICKET_VISIBILITIES.has(value as TicketVisibility)
  )
}

function decodeFieldErrors(value: unknown): ProblemDetails['fieldErrors'] {
  if (!Array.isArray(value)) return undefined
  return value.flatMap((fieldError) => {
    if (!isRecord(fieldError)) return []
    if (
      !isNonBlankString(fieldError.field) ||
      !isNonBlankString(fieldError.message)
    ) {
      return []
    }
    return [
      {
        field: fieldError.field,
        message: fieldError.message,
        ...(typeof fieldError.code === 'string'
          ? { code: fieldError.code }
          : {}),
      },
    ]
  })
}

function decodeProblem(value: unknown): ProblemDetails | undefined {
  if (!isRecord(value)) return undefined
  const fieldErrors = decodeFieldErrors(value.fieldErrors)
  return {
    ...(typeof value.type === 'string' ? { type: value.type } : {}),
    ...(typeof value.title === 'string' ? { title: value.title } : {}),
    ...(typeof value.status === 'number' ? { status: value.status } : {}),
    ...(typeof value.detail === 'string' ? { detail: value.detail } : {}),
    ...(typeof value.instance === 'string' ? { instance: value.instance } : {}),
    ...(typeof value.requestId === 'string'
      ? { requestId: value.requestId }
      : {}),
    ...(typeof value.code === 'string' ? { code: value.code } : {}),
    ...(fieldErrors ? { fieldErrors } : {}),
  }
}

function decodeSubmittedRequest(value: unknown): SubmittedRequest | undefined {
  if (!isRecord(value)) return undefined
  if (
    !isTicketNumber(value.ticketNumber) ||
    !isTicketStatus(value.status) ||
    !isNonBlankString(value.accessToken) ||
    value.accessToken.length < ACCESS_TOKEN_MIN_LENGTH ||
    value.accessToken.length > ACCESS_TOKEN_MAX_LENGTH ||
    !isTimestamp(value.createdAt)
  ) {
    return undefined
  }
  return {
    ticketNumber: value.ticketNumber,
    status: value.status,
    accessToken: value.accessToken,
    createdAt: value.createdAt,
  }
}

function decodePublicComment(value: unknown): PublicComment | undefined {
  if (!isRecord(value)) return undefined
  if (
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.authorDisplayName) ||
    !isNonBlankString(value.body) ||
    !isTimestamp(value.createdAt)
  ) {
    return undefined
  }
  return {
    id: value.id,
    authorDisplayName: value.authorDisplayName,
    body: value.body,
    createdAt: value.createdAt,
  }
}

function decodePublicRequest(value: unknown): PublicRequest | undefined {
  if (!isRecord(value) || !Array.isArray(value.comments)) return undefined
  if (
    !isTicketNumber(value.ticketNumber) ||
    !isNonBlankString(value.subject) ||
    !isTicketStatus(value.status) ||
    !isTimestamp(value.createdAt) ||
    !isTimestamp(value.updatedAt)
  ) {
    return undefined
  }
  const comments: PublicComment[] = []
  for (const commentValue of value.comments) {
    const comment = decodePublicComment(commentValue)
    if (!comment) return undefined
    comments.push(comment)
  }
  return {
    ticketNumber: value.ticketNumber,
    subject: value.subject,
    status: value.status,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    comments,
  }
}

async function readJson(response: Response): Promise<unknown | undefined> {
  try {
    return await response.json()
  } catch {
    return undefined
  }
}

function failure(
  response: Response,
  problem: ProblemDetails | undefined,
): ApiError {
  const fieldErrors = Object.fromEntries(
    (problem?.fieldErrors ?? []).map((error) => [error.field, error.message]),
  )
  return new ApiError(
    problem?.detail ??
      problem?.title ??
      `요청이 실패했습니다. (${response.status})`,
    response.status,
    problem,
    problem?.requestId ?? response.headers.get('X-Request-Id') ?? undefined,
    response.headers.get('Retry-After') ?? undefined,
    fieldErrors,
  )
}

function malformedSuccess(response: Response): ApiError {
  return new ApiError(
    '서버 응답을 안전하게 처리할 수 없습니다.',
    response.status,
    undefined,
    response.headers.get('X-Request-Id') ?? undefined,
  )
}

async function successfulResponseBody(response: Response): Promise<unknown> {
  const body = await readJson(response)
  if (!response.ok) throw failure(response, decodeProblem(body))
  return body
}

export async function submitRequest(
  input: SubmitRequestInput,
): Promise<SubmittedRequest> {
  const response = await fetch(`${API_BASE_URL}/api/v1/requests`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    cache: 'no-store',
    body: JSON.stringify(input),
  })
  const body = await successfulResponseBody(response)
  const submitted = decodeSubmittedRequest(body)
  if (!submitted) throw malformedSuccess(response)
  return submitted
}

export async function getPublicRequest(
  ticketNumber: number,
  accessToken: string,
): Promise<PublicRequest> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/requests/${ticketNumber}`,
    {
      headers: { 'X-Request-Access-Token': accessToken },
      cache: 'no-store',
    },
  )
  const body = await successfulResponseBody(response)
  const request = decodePublicRequest(body)
  if (!request) throw malformedSuccess(response)
  return request
}

const STAFF_ROLES = new Set<StaffRole>(['ADMIN', 'AGENT', 'SECURITY_AUDITOR'])

function isStaffRole(value: unknown): value is StaffRole {
  return typeof value === 'string' && STAFF_ROLES.has(value as StaffRole)
}

function decodeCurrentStaff(value: unknown): CurrentStaff | undefined {
  if (!isRecord(value) || !Array.isArray(value.capabilities)) return undefined
  if (
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.email) ||
    !isNonBlankString(value.displayName) ||
    !isStaffRole(value.role) ||
    !value.capabilities.every(isNonBlankString)
  ) {
    return undefined
  }
  return {
    id: value.id,
    email: value.email,
    displayName: value.displayName,
    role: value.role,
    capabilities: value.capabilities,
  }
}

function decodeStaffAccount(value: unknown): StaffAccount | undefined {
  if (!isRecord(value) || !Array.isArray(value.memberships)) return undefined
  if (
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.email) ||
    !isNonBlankString(value.displayName) ||
    !isStaffRole(value.role) ||
    (value.status !== 'ACTIVE' && value.status !== 'DISABLED') ||
    (value.lastLoginAt !== null && !isTimestamp(value.lastLoginAt))
  ) {
    return undefined
  }
  const memberships = value.memberships.flatMap((membership) => {
    if (
      !isRecord(membership) ||
      !isNonBlankString(membership.id) ||
      !isNonBlankString(membership.name)
    ) {
      return []
    }
    return [{ id: membership.id, name: membership.name }]
  })
  if (memberships.length !== value.memberships.length) return undefined
  return {
    id: value.id,
    email: value.email,
    displayName: value.displayName,
    role: value.role,
    status: value.status,
    memberships,
    lastLoginAt: value.lastLoginAt,
  }
}

function decodeSupportGroup(value: unknown): SupportGroup | undefined {
  if (!isRecord(value)) return undefined
  if (
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.name) ||
    (value.status !== 'ACTIVE' && value.status !== 'DISABLED') ||
    typeof value.memberCount !== 'number'
  ) {
    return undefined
  }
  return {
    id: value.id,
    name: value.name,
    status: value.status,
    memberCount: value.memberCount,
  }
}

function decodeMembership(value: unknown): GroupMembership | undefined {
  if (!isRecord(value)) return undefined
  if (
    !isNonBlankString(value.groupId) ||
    !isNonBlankString(value.staffId) ||
    !isNonBlankString(value.staffDisplayName) ||
    !isStaffRole(value.role)
  ) {
    return undefined
  }
  return {
    groupId: value.groupId,
    staffId: value.staffId,
    staffDisplayName: value.staffDisplayName,
    role: value.role,
  }
}

async function staffFetch(path: string, init: RequestInit = {}) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    credentials: 'include',
    cache: 'no-store',
    ...init,
  })
  if (response.status === 401 && typeof window !== 'undefined') {
    window.dispatchEvent(new Event(STAFF_SESSION_INVALID_EVENT))
  }
  return response
}

async function checkedBody(response: Response): Promise<unknown> {
  return successfulResponseBody(response)
}

async function checkedEmpty(response: Response): Promise<void> {
  if (response.ok) return
  throw failure(response, decodeProblem(await readJson(response)))
}

async function csrfHeaders(): Promise<Record<string, string>> {
  const response = await staffFetch('/api/v1/agent/csrf')
  const body = await checkedBody(response)
  if (
    !isRecord(body) ||
    !isNonBlankString(body.token) ||
    !isNonBlankString(body.headerName)
  ) {
    throw malformedSuccess(response)
  }
  return { [body.headerName]: body.token }
}

async function unsafeStaffFetch(
  path: string,
  method: 'POST' | 'PATCH' | 'DELETE',
  body?: unknown,
) {
  const csrf = await csrfHeaders()
  return staffFetch(path, {
    method,
    headers: {
      ...csrf,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
}

export async function loginStaff(
  email: string,
  password: string,
): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch('/api/v1/agent/session', 'POST', {
      email,
      password,
    }),
  )
}

export async function logoutStaff(): Promise<void> {
  await checkedEmpty(await unsafeStaffFetch('/api/v1/agent/session', 'DELETE'))
}

export async function getCurrentStaff(): Promise<CurrentStaff> {
  const response = await staffFetch('/api/v1/agent/me')
  const decoded = decodeCurrentStaff(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function listStaff(): Promise<StaffAccount[]> {
  const response = await staffFetch('/api/v1/admin/staff')
  const body = await checkedBody(response)
  if (!Array.isArray(body)) throw malformedSuccess(response)
  const decoded = body.map(decodeStaffAccount)
  if (decoded.some((staff) => !staff)) throw malformedSuccess(response)
  return decoded as StaffAccount[]
}

export async function createStaff(
  input: CreateStaffInput,
): Promise<StaffAccount> {
  const response = await unsafeStaffFetch('/api/v1/admin/staff', 'POST', input)
  const decoded = decodeStaffAccount(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function disableStaff(staffId: string): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch(`/api/v1/admin/staff/${staffId}`, 'DELETE'),
  )
}

export async function listGroups(): Promise<SupportGroup[]> {
  const response = await staffFetch('/api/v1/admin/groups')
  const body = await checkedBody(response)
  if (!Array.isArray(body)) throw malformedSuccess(response)
  const decoded = body.map(decodeSupportGroup)
  if (decoded.some((group) => !group)) throw malformedSuccess(response)
  return decoded as SupportGroup[]
}

export async function createGroup(name: string): Promise<SupportGroup> {
  const response = await unsafeStaffFetch('/api/v1/admin/groups', 'POST', {
    name,
  })
  const decoded = decodeSupportGroup(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function renameGroup(
  groupId: string,
  name: string,
): Promise<SupportGroup> {
  const response = await unsafeStaffFetch(
    `/api/v1/admin/groups/${groupId}`,
    'PATCH',
    { name },
  )
  const decoded = decodeSupportGroup(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function disableGroup(groupId: string): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch(`/api/v1/admin/groups/${groupId}`, 'DELETE'),
  )
}

export async function listGroupMembers(
  groupId: string,
): Promise<GroupMembership[]> {
  const response = await staffFetch(`/api/v1/admin/groups/${groupId}/members`)
  const body = await checkedBody(response)
  if (!Array.isArray(body)) throw malformedSuccess(response)
  const decoded = body.map(decodeMembership)
  if (decoded.some((membership) => !membership))
    throw malformedSuccess(response)
  return decoded as GroupMembership[]
}

export async function addGroupMember(
  groupId: string,
  staffId: string,
): Promise<GroupMembership> {
  const response = await unsafeStaffFetch(
    `/api/v1/admin/groups/${groupId}/members`,
    'POST',
    { staffId },
  )
  const decoded = decodeMembership(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function removeGroupMember(
  groupId: string,
  staffId: string,
): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch(
      `/api/v1/admin/groups/${groupId}/members/${staffId}`,
      'DELETE',
    ),
  )
}

function decodeActorSummary(value: unknown): ActorSummary | undefined {
  if (!isRecord(value)) return undefined
  if (
    (value.id !== null && !isNonBlankString(value.id)) ||
    typeof value.type !== 'string' ||
    !ACTOR_TYPES.has(value.type as ActorSummary['type']) ||
    !isNonBlankString(value.displayName)
  ) {
    return undefined
  }
  return {
    id: value.id,
    type: value.type as ActorSummary['type'],
    displayName: value.displayName,
  }
}

function decodeGroupReference(value: unknown): GroupReference | undefined {
  if (
    !isRecord(value) ||
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.name)
  ) {
    return undefined
  }
  return { id: value.id, name: value.name }
}

function decodeTicketReference(
  value: unknown,
): AgentTicketSummary['assignee'] | undefined {
  if (
    !isRecord(value) ||
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.displayName)
  ) {
    return undefined
  }
  return { id: value.id, displayName: value.displayName }
}

function decodeAgentTicketSummary(
  value: unknown,
): AgentTicketSummary | undefined {
  if (!isRecord(value)) return undefined
  const requester = decodeActorSummary(value.requester)
  const group = value.group === null ? null : decodeGroupReference(value.group)
  const assignee =
    value.assignee === null ? null : decodeTicketReference(value.assignee)
  if (
    !isTicketNumber(value.ticketNumber) ||
    !isNonBlankString(value.subject) ||
    !isAgentTicketStatus(value.status) ||
    !isTicketPriority(value.priority) ||
    !requester ||
    group === undefined ||
    assignee === undefined ||
    !isTimestamp(value.updatedAt) ||
    typeof value.version !== 'number' ||
    !Number.isSafeInteger(value.version) ||
    typeof value.isChild !== 'boolean' ||
    typeof value.openChildCount !== 'number' ||
    !Number.isSafeInteger(value.openChildCount) ||
    value.sla !== null
  ) {
    return undefined
  }
  return {
    ticketNumber: value.ticketNumber,
    subject: value.subject,
    status: value.status,
    priority: value.priority,
    requester,
    group,
    assignee,
    updatedAt: value.updatedAt,
    version: value.version,
    isChild: value.isChild,
    openChildCount: value.openChildCount,
    sla: null,
  }
}

function decodeSavedAgentView(value: unknown): SavedAgentView | undefined {
  if (!isRecord(value) || !Array.isArray(value.categoryPath)) return undefined
  if (
    !isNonBlankString(value.key) ||
    !isNonBlankString(value.name) ||
    !['PERSONAL', 'SHARED', 'SYSTEM'].includes(String(value.scope)) ||
    !value.categoryPath.every(isNonBlankString) ||
    (value.ticketCount !== null && typeof value.ticketCount !== 'number') ||
    value.readScope !== 'ALL_TICKETS'
  ) {
    return undefined
  }
  return {
    key: value.key,
    name: value.name,
    scope: value.scope as SavedAgentView['scope'],
    categoryPath: value.categoryPath,
    ticketCount: value.ticketCount,
    readScope: value.readScope,
  }
}

function decodeAgentComment(value: unknown): AgentComment | undefined {
  if (!isRecord(value) || !Array.isArray(value.attachments)) return undefined
  const actor = decodeActorSummary(value.actor)
  if (
    !isNonBlankString(value.id) ||
    !isTicketVisibility(value.visibility) ||
    !actor ||
    !isNonBlankString(value.body) ||
    !isTimestamp(value.createdAt) ||
    !isNonBlankString(value.source)
  ) {
    return undefined
  }
  return {
    id: value.id,
    visibility: value.visibility,
    actor,
    body: value.body,
    createdAt: value.createdAt,
    source: value.source,
    attachments: [...value.attachments],
  }
}

function decodeHistory(value: unknown): TicketHistoryItem | undefined {
  if (!isRecord(value)) return undefined
  const actor = decodeActorSummary(value.actor)
  if (
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.eventType) ||
    !actor ||
    !isTimestamp(value.occurredAt)
  ) {
    return undefined
  }
  return {
    id: value.id,
    eventType: value.eventType,
    actor,
    occurredAt: value.occurredAt,
  }
}

function decodeAgentTicketDetail(
  value: unknown,
): AgentTicketDetail | undefined {
  if (
    !isRecord(value) ||
    !Array.isArray(value.comments) ||
    !Array.isArray(value.capabilities) ||
    !isRecord(value.context) ||
    !Array.isArray(value.history) ||
    !Array.isArray(value.warnings)
  ) {
    return undefined
  }
  const ticket = decodeAgentTicketSummary(value.ticket)
  const comments = value.comments.map(decodeAgentComment)
  const customer = value.context.customer
  const parent =
    value.context.parent === null
      ? null
      : decodeAgentTicketSummary(value.context.parent)
  const children = Array.isArray(value.context.children)
    ? value.context.children.map(decodeAgentTicketSummary)
    : []
  const history = value.history.map(decodeHistory)
  if (
    !ticket ||
    comments.some((comment) => !comment) ||
    !isRecord(customer) ||
    !isNonBlankString(customer.id) ||
    !isNonBlankString(customer.displayName) ||
    !isNonBlankString(customer.email) ||
    parent === undefined ||
    !Array.isArray(value.context.children) ||
    children.some((child) => !child) ||
    !Array.isArray(value.context.externalReferences) ||
    history.some((item) => !item) ||
    !value.capabilities.every(isNonBlankString)
  ) {
    return undefined
  }
  return {
    ticket,
    comments: comments as AgentComment[],
    capabilities: value.capabilities,
    context: {
      customer: {
        id: customer.id,
        displayName: customer.displayName,
        email: customer.email,
      },
      parent,
      children: children as AgentTicketSummary[],
      externalReferences: [...value.context.externalReferences],
    },
    history: history as TicketHistoryItem[],
    warnings: [...value.warnings],
  }
}

export async function listAgentViews(): Promise<SavedAgentView[]> {
  const response = await staffFetch('/api/v1/agent/views')
  const body = await checkedBody(response)
  if (!Array.isArray(body)) throw malformedSuccess(response)
  const views = body.map(decodeSavedAgentView)
  if (views.some((view) => !view)) throw malformedSuccess(response)
  return views as SavedAgentView[]
}

export async function listTicketsInView(
  viewKey: string,
  filters: AgentTicketFilters = {},
): Promise<AgentTicketPage> {
  const search = new URLSearchParams()
  if (filters.status) search.set('status', filters.status)
  if (filters.priority) search.set('priority', filters.priority)
  if (filters.groupId) search.set('groupId', filters.groupId)
  if (filters.assigneeId) search.set('assigneeId', filters.assigneeId)
  if (filters.cursor) search.set('cursor', filters.cursor)
  if (filters.limit) search.set('limit', String(filters.limit))
  const query = search.size ? `?${search.toString()}` : ''
  const response = await staffFetch(
    `/api/v1/agent/views/${encodeURIComponent(viewKey)}/tickets${query}`,
  )
  const body = await checkedBody(response)
  if (!isRecord(body) || !Array.isArray(body.items))
    throw malformedSuccess(response)
  const items = body.items.map(decodeAgentTicketSummary)
  if (
    items.some((ticket) => !ticket) ||
    (body.nextCursor !== null && typeof body.nextCursor !== 'string') ||
    (body.totalApproximate !== null &&
      typeof body.totalApproximate !== 'number') ||
    body.sort !== 'updatedAt:desc,ticketNumber:desc'
  ) {
    throw malformedSuccess(response)
  }
  return {
    items: items as AgentTicketSummary[],
    nextCursor: body.nextCursor,
    totalApproximate: body.totalApproximate,
    sort: body.sort,
  }
}

export async function getAgentTicket(
  ticketNumber: number,
  interactionId: string,
  intent: AgentReadIntent,
): Promise<AgentTicketDetail> {
  const response = await staffFetch(`/api/v1/agent/tickets/${ticketNumber}`, {
    headers: {
      'X-Interaction-Id': interactionId,
      'X-Deskseed-Read-Intent': intent,
    },
  })
  const detail = decodeAgentTicketDetail(await checkedBody(response))
  if (!detail) throw malformedSuccess(response)
  return detail
}
