import type {
  ActorSummary,
  AgentComment,
  AgentReadIntent,
  AgentTicketStatus,
  AgentTicketDetail,
  AgentTicketFilters,
  AgentTicketPage,
  AgentTicketSearchInput,
  AgentTicketSearchPage,
  AgentTicketSummary,
  AuditActivity,
  AuditActivityDetail,
  AuditActivityFilters,
  AuditActivityPage,
  AuditExportJob,
  AuditProjectionRebuildResult,
  AuditProjectionStatus,
  AuditSearchContext,
  CreateAuditExportInput,
  CreateChildTicketCommand,
  CreateChildTicketResult,
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
  SearchQueryRevealResult,
  TicketHistoryItem,
  TicketAssignmentGroupOption,
  TicketAssignmentOptions,
  TicketCommandResult,
  TicketFieldName,
  TicketPriority,
  TicketStatus,
  TicketVisibility,
  TransferTicketCommand,
  UpdateTicketCommand,
} from './types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
export const STAFF_SESSION_INVALID_EVENT = 'deskseed:staff-session-invalid'
export const STAFF_SESSION_ACTOR_MISMATCH_EVENT =
  'deskseed:staff-session-actor-mismatch'
const EXPECTED_STAFF_ACTOR_HEADER = 'X-Deskseed-Expected-Staff-Id'
let staffSessionGeneration = 0
let confirmedStaffActor: string | null = null

export function setConfirmedStaffActor(staffId: string | null) {
  confirmedStaffActor = isCanonicalUuid(staffId) ? staffId : null
}

export function advanceStaffSessionGeneration() {
  staffSessionGeneration += 1
}

export function isCurrentStaffSessionInvalidation(event: Event) {
  return isCurrentStaffSessionEvent(event)
}

export function isCurrentStaffSessionActorMismatch(event: Event) {
  return isCurrentStaffSessionEvent(event)
}

function isCurrentStaffSessionEvent(event: Event) {
  if (!(event instanceof CustomEvent)) return true
  const detail = event.detail as { generation?: unknown } | null
  return (
    typeof detail?.generation !== 'number' ||
    detail.generation === staffSessionGeneration
  )
}
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
const TICKET_FIELD_NAMES = new Set<TicketFieldName>([
  'status',
  'priority',
  'groupId',
  'assigneeId',
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

function isUuid(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
      value,
    )
  )
}

function isCanonicalUuid(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      value,
    )
  )
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
  const conflictingFields = Array.isArray(value.conflictingFields)
    ? value.conflictingFields.filter(
        (field): field is TicketFieldName =>
          typeof field === 'string' &&
          TICKET_FIELD_NAMES.has(field as TicketFieldName),
      )
    : undefined
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
    ...(typeof value.currentVersion === 'number' &&
    Number.isSafeInteger(value.currentVersion)
      ? { currentVersion: value.currentVersion }
      : {}),
    ...(Array.isArray(value.conflictingFields) &&
    conflictingFields?.length === value.conflictingFields.length
      ? { conflictingFields }
      : {}),
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

interface StaffFetchOptions {
  invalidateSessionOn401?: boolean
  omitExpectedStaffActor?: boolean
  onMutationRequestStart?: () => void
}

interface StaffRequestSnapshot {
  generation: number
  actor: string | null
}

function captureStaffRequestSnapshot(): StaffRequestSnapshot {
  return {
    generation: staffSessionGeneration,
    actor: confirmedStaffActor,
  }
}

function isCurrentStaffRequestSnapshot(snapshot: StaffRequestSnapshot) {
  return (
    snapshot.generation === staffSessionGeneration &&
    snapshot.actor === confirmedStaffActor
  )
}

async function staffFetch(
  path: string,
  init: RequestInit = {},
  {
    invalidateSessionOn401 = true,
    omitExpectedStaffActor = false,
  }: StaffFetchOptions = {},
  requestSnapshot = captureStaffRequestSnapshot(),
) {
  const requestSessionGeneration = requestSnapshot.generation
  let headers: Record<string, string>
  if (init.headers instanceof Headers) {
    headers = {}
    init.headers.forEach((value, name) => {
      headers[name] = value
    })
  } else if (Array.isArray(init.headers)) {
    headers = Object.fromEntries(init.headers)
  } else {
    headers = { ...(init.headers ?? {}) }
  }
  for (const headerName of Object.keys(headers)) {
    if (
      headerName.toLowerCase() === EXPECTED_STAFF_ACTOR_HEADER.toLowerCase()
    ) {
      delete headers[headerName]
    }
  }
  if (!omitExpectedStaffActor && requestSnapshot.actor !== null) {
    headers[EXPECTED_STAFF_ACTOR_HEADER] = requestSnapshot.actor
  }
  const response = await fetch(`${API_BASE_URL}${path}`, {
    credentials: 'include',
    cache: 'no-store',
    ...init,
    headers,
  })
  if (
    invalidateSessionOn401 &&
    response.status === 401 &&
    typeof window !== 'undefined'
  ) {
    window.dispatchEvent(
      new CustomEvent(STAFF_SESSION_INVALID_EVENT, {
        detail: { generation: requestSessionGeneration },
      }),
    )
  }
  if (response.status === 409 && typeof window !== 'undefined') {
    const problem = decodeProblem(await readJson(response.clone()))
    if (problem?.type === '/problems/staff-session-actor-mismatch') {
      window.dispatchEvent(
        new CustomEvent(STAFF_SESSION_ACTOR_MISMATCH_EVENT, {
          detail: { generation: requestSessionGeneration },
        }),
      )
    }
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

async function csrfHeaders(
  options: StaffFetchOptions = {},
  requestSnapshot = captureStaffRequestSnapshot(),
): Promise<Record<string, string>> {
  const response = await staffFetch(
    '/api/v1/agent/csrf',
    {},
    options,
    requestSnapshot,
  )
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
  additionalHeaders: Record<string, string> = {},
  options: StaffFetchOptions = {},
) {
  const requestSnapshot = captureStaffRequestSnapshot()
  const csrf = await csrfHeaders(options, requestSnapshot)
  if (!isCurrentStaffRequestSnapshot(requestSnapshot)) {
    throw new Error('Staff session changed before mutation')
  }
  options.onMutationRequestStart?.()
  if (!isCurrentStaffRequestSnapshot(requestSnapshot)) {
    throw new Error('Staff session changed before mutation')
  }
  return staffFetch(
    path,
    {
      method,
      headers: {
        ...csrf,
        ...additionalHeaders,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    },
    options,
    requestSnapshot,
  )
}

export async function loginStaff(
  email: string,
  password: string,
  onMutationRequestStart?: () => void,
): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch(
      '/api/v1/agent/session',
      'POST',
      {
        email,
        password,
      },
      {},
      {
        invalidateSessionOn401: false,
        omitExpectedStaffActor: true,
        onMutationRequestStart,
      },
    ),
  )
}

export async function logoutStaff(
  options: StaffFetchOptions = {},
): Promise<void> {
  await checkedEmpty(
    await unsafeStaffFetch(
      '/api/v1/agent/session',
      'DELETE',
      undefined,
      {},
      options,
    ),
  )
}

export async function getCurrentStaff(
  options: StaffFetchOptions = {},
): Promise<CurrentStaff> {
  const response = await staffFetch('/api/v1/agent/me', {}, options)
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
    !isRecord(value.assignmentOptions) ||
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
  const assignmentOptions = decodeTicketAssignmentOptions(
    value.assignmentOptions,
  )
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
    !assignmentOptions ||
    !value.capabilities.every(isNonBlankString)
  ) {
    return undefined
  }
  return {
    ticket,
    comments: comments as AgentComment[],
    capabilities: value.capabilities,
    assignmentOptions,
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

function decodeTicketAssignmentOptions(
  value: Record<string, unknown>,
): TicketAssignmentOptions | undefined {
  if (!Array.isArray(value.groups)) return undefined
  const groups = value.groups.map(decodeTicketAssignmentGroupOption)
  if (groups.some((group) => !group)) return undefined
  return { groups: groups as TicketAssignmentGroupOption[] }
}

function decodeTicketAssignmentGroupOption(
  value: unknown,
): TicketAssignmentGroupOption | undefined {
  if (
    !isRecord(value) ||
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.name) ||
    !Array.isArray(value.members)
  ) {
    return undefined
  }
  const members = value.members.flatMap((member) => {
    if (
      !isRecord(member) ||
      !isNonBlankString(member.id) ||
      !isNonBlankString(member.displayName)
    ) {
      return []
    }
    return [{ id: member.id, displayName: member.displayName }]
  })
  if (members.length !== value.members.length) return undefined
  return { id: value.id, name: value.name, members }
}

function decodeTicketCommandResult(
  value: unknown,
): TicketCommandResult | undefined {
  if (
    !isRecord(value) ||
    !isTicketNumber(value.ticketNumber) ||
    typeof value.version !== 'number' ||
    !Number.isSafeInteger(value.version) ||
    value.version < 0 ||
    !isNonBlankString(value.auditId) ||
    !Array.isArray(value.warnings)
  ) {
    return undefined
  }
  const warnings = value.warnings.flatMap((warning) => {
    if (
      !isRecord(warning) ||
      !isNonBlankString(warning.code) ||
      !isNonBlankString(warning.message) ||
      typeof warning.count !== 'number' ||
      !Number.isSafeInteger(warning.count) ||
      warning.count < 1 ||
      !Array.isArray(warning.relatedTicketNumbers) ||
      !warning.relatedTicketNumbers.every(isTicketNumber) ||
      warning.count !== warning.relatedTicketNumbers.length
    ) {
      return []
    }
    return [
      {
        code: warning.code,
        message: warning.message,
        count: warning.count,
        relatedTicketNumbers: warning.relatedTicketNumbers,
      },
    ]
  })
  if (warnings.length !== value.warnings.length) return undefined
  return {
    ticketNumber: value.ticketNumber,
    version: value.version,
    auditId: value.auditId,
    warnings,
  }
}

function decodeCreateChildTicketResult(
  value: unknown,
): CreateChildTicketResult | undefined {
  if (
    !isRecord(value) ||
    !isTicketNumber(value.parentTicketNumber) ||
    typeof value.parentVersion !== 'number' ||
    !Number.isSafeInteger(value.parentVersion) ||
    value.parentVersion < 0 ||
    !isTicketNumber(value.childTicketNumber) ||
    !isNonBlankString(value.parentAuditId) ||
    !isNonBlankString(value.childAuditId)
  ) {
    return undefined
  }
  return {
    parentTicketNumber: value.parentTicketNumber,
    parentVersion: value.parentVersion,
    childTicketNumber: value.childTicketNumber,
    parentAuditId: value.parentAuditId,
    childAuditId: value.childAuditId,
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

export async function searchAgentTickets(
  input: AgentTicketSearchInput,
  interactionId: string,
): Promise<AgentTicketSearchPage> {
  const response = await unsafeStaffFetch(
    '/api/v1/agent/search',
    'POST',
    input,
    { 'X-Interaction-Id': interactionId },
  )
  const body = await checkedBody(response)
  if (!isRecord(body) || !Array.isArray(body.items)) {
    throw malformedSuccess(response)
  }
  const items = body.items.map(decodeAgentTicketSummary)
  if (
    !isUuid(body.searchEventId) ||
    !isUuid(body.searchInteractionId) ||
    items.some((ticket) => !ticket) ||
    typeof body.resultCount !== 'number' ||
    !Number.isSafeInteger(body.resultCount) ||
    body.resultCount < 0 ||
    body.sort !== 'updatedAt:desc,ticketNumber:desc'
  ) {
    throw malformedSuccess(response)
  }
  return {
    searchEventId: body.searchEventId,
    searchInteractionId: body.searchInteractionId,
    items: items as AgentTicketSummary[],
    resultCount: body.resultCount,
    sort: body.sort,
  }
}

export async function getAgentTicket(
  ticketNumber: number,
  interactionId: string,
  intent: AgentReadIntent,
  originSearchEventId?: string,
): Promise<AgentTicketDetail> {
  const response = await staffFetch(`/api/v1/agent/tickets/${ticketNumber}`, {
    headers: {
      'X-Interaction-Id': interactionId,
      'X-Deskseed-Read-Intent': intent,
      ...(originSearchEventId
        ? { 'X-Origin-Search-Event-Id': originSearchEventId }
        : {}),
    },
  })
  const detail = decodeAgentTicketDetail(await checkedBody(response))
  if (!detail) throw malformedSuccess(response)
  return detail
}

export async function updateAgentTicket(
  ticketNumber: number,
  command: UpdateTicketCommand,
): Promise<TicketCommandResult> {
  const response = await unsafeStaffFetch(
    `/api/v1/agent/tickets/${ticketNumber}/commands`,
    'POST',
    command,
  )
  const result = decodeTicketCommandResult(await checkedBody(response))
  if (!result) throw malformedSuccess(response)
  return result
}

export async function transferAgentTicket(
  ticketNumber: number,
  command: TransferTicketCommand,
): Promise<TicketCommandResult> {
  const response = await unsafeStaffFetch(
    `/api/v1/agent/tickets/${ticketNumber}/transfer`,
    'POST',
    command,
    { 'If-Match': `"${command.expectedVersion}"` },
  )
  const result = decodeTicketCommandResult(await checkedBody(response))
  if (!result) throw malformedSuccess(response)
  return result
}

export async function createChildTicket(
  ticketNumber: number,
  command: CreateChildTicketCommand,
): Promise<CreateChildTicketResult> {
  const response = await unsafeStaffFetch(
    `/api/v1/agent/tickets/${ticketNumber}/children`,
    'POST',
    command,
    { 'If-Match': `"${command.expectedVersion}"` },
  )
  const result = decodeCreateChildTicketResult(await checkedBody(response))
  if (!result) throw malformedSuccess(response)
  return result
}

const AUDIT_LEDGERS = new Set([
  'TICKET_CHANGE',
  'ACCESS_SEARCH',
  'ADMIN_SECURITY',
])
const AUDIT_OUTCOMES = new Set(['SUCCEEDED', 'DENIED', 'FAILED'])
const AUDIT_PROJECTION_STATES = new Set(['CURRENT', 'DEGRADED', 'REBUILDING'])

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function decodeAuditActivity(value: unknown): AuditActivity | undefined {
  if (!isRecord(value)) return undefined
  const actor = decodeActorSummary(value.actor)
  if (
    !isCanonicalUuid(value.id) ||
    typeof value.ledger !== 'string' ||
    !AUDIT_LEDGERS.has(value.ledger) ||
    !isNonBlankString(value.action) ||
    !actor ||
    !isTimestamp(value.occurredAt) ||
    (value.ticketNumber !== null && !isTicketNumber(value.ticketNumber)) ||
    !isNullableString(value.groupId) ||
    !isNullableString(value.field) ||
    !isNullableString(value.resourceType) ||
    !isNullableString(value.resourceId) ||
    !isNonBlankString(value.summary) ||
    !isNonBlankString(value.source) ||
    typeof value.outcome !== 'string' ||
    !AUDIT_OUTCOMES.has(value.outcome) ||
    !isNullableString(value.requestId) ||
    !isNullableString(value.correlationId) ||
    typeof value.protectedContentAvailable !== 'boolean' ||
    !isNullableString(value.searchFingerprint)
  ) {
    return undefined
  }
  return {
    id: value.id,
    ledger: value.ledger as AuditActivity['ledger'],
    action: value.action,
    actor,
    occurredAt: value.occurredAt,
    ticketNumber: value.ticketNumber,
    groupId: value.groupId,
    field: value.field,
    resourceType: value.resourceType,
    resourceId: value.resourceId,
    summary: value.summary,
    source: value.source,
    outcome: value.outcome as AuditActivity['outcome'],
    requestId: value.requestId,
    correlationId: value.correlationId,
    protectedContentAvailable: value.protectedContentAvailable,
    searchFingerprint: value.searchFingerprint,
  }
}

function decodeAuditProjectionStatus(
  value: unknown,
): AuditProjectionStatus | undefined {
  if (
    !isRecord(value) ||
    typeof value.state !== 'string' ||
    !AUDIT_PROJECTION_STATES.has(value.state) ||
    typeof value.projectedCount !== 'number' ||
    !Number.isSafeInteger(value.projectedCount) ||
    value.projectedCount < 0 ||
    (value.lastRebuiltAt !== null && !isTimestamp(value.lastRebuiltAt))
  ) {
    return undefined
  }
  return {
    state: value.state as AuditProjectionStatus['state'],
    projectedCount: value.projectedCount,
    lastRebuiltAt: value.lastRebuiltAt,
  }
}

function decodeAuditSearchContext(
  value: unknown,
): AuditSearchContext | null | undefined {
  if (value === null) return null
  if (
    !isRecord(value) ||
    !isNonBlankString(value.queryRedacted) ||
    !isNonBlankString(value.queryFingerprint) ||
    !isRecord(value.filters) ||
    !Object.values(value.filters).every((item) => typeof item === 'string') ||
    !isNullableString(value.sort) ||
    typeof value.resultCount !== 'number' ||
    !Number.isSafeInteger(value.resultCount) ||
    value.resultCount < 0 ||
    !isNullableString(value.originSearchActivityId) ||
    !Array.isArray(value.openedActivities)
  ) {
    return undefined
  }
  const openedActivities = value.openedActivities.flatMap((item) => {
    if (
      !isRecord(item) ||
      !isCanonicalUuid(item.activityId) ||
      !isTicketNumber(item.ticketNumber) ||
      !isTimestamp(item.occurredAt)
    ) {
      return []
    }
    return [
      {
        activityId: item.activityId,
        ticketNumber: item.ticketNumber,
        occurredAt: item.occurredAt,
      },
    ]
  })
  if (openedActivities.length !== value.openedActivities.length)
    return undefined
  return {
    queryRedacted: value.queryRedacted,
    queryFingerprint: value.queryFingerprint,
    filters: value.filters as Record<string, string>,
    sort: value.sort,
    resultCount: value.resultCount,
    originSearchActivityId: value.originSearchActivityId,
    openedActivities,
  }
}

function decodeAuditActivityPage(
  value: unknown,
): AuditActivityPage | undefined {
  if (!isRecord(value) || !Array.isArray(value.items)) return undefined
  const items = value.items.map(decodeAuditActivity)
  const projection = decodeAuditProjectionStatus(value.projection)
  if (
    items.some((item) => !item) ||
    !isNullableString(value.nextCursor) ||
    !isTimestamp(value.snapshotAt) ||
    !projection
  ) {
    return undefined
  }
  return {
    items: items as AuditActivity[],
    nextCursor: value.nextCursor,
    snapshotAt: value.snapshotAt,
    projection,
  }
}

function decodeAuditActivityDetail(
  value: unknown,
): AuditActivityDetail | undefined {
  const activity = decodeAuditActivity(value)
  if (!activity || !isRecord(value)) return undefined
  const search = decodeAuditSearchContext(value.search)
  const fieldChange = value.fieldChange
  if (
    !isCanonicalUuid(value.canonicalEventId) ||
    !isNullableString(value.canonicalParentId) ||
    (fieldChange !== null &&
      (!isRecord(fieldChange) || !isNonBlankString(fieldChange.field))) ||
    !isNullableString(value.interactionId) ||
    !isNullableString(value.sessionFingerprint) ||
    !isNullableString(value.authType) ||
    !isNullableString(value.ipAddress) ||
    !isNullableString(value.userAgent) ||
    search === undefined ||
    !isRecord(value.metadata)
  ) {
    return undefined
  }
  return {
    ...activity,
    canonicalEventId: value.canonicalEventId,
    canonicalParentId: value.canonicalParentId,
    fieldChange:
      fieldChange === null
        ? null
        : {
            field: fieldChange.field as string,
            before: fieldChange.before,
            after: fieldChange.after,
          },
    interactionId: value.interactionId,
    sessionFingerprint: value.sessionFingerprint,
    authType: value.authType,
    ipAddress: value.ipAddress,
    userAgent: value.userAgent,
    search,
    metadata: value.metadata,
  }
}

function decodeSearchQueryRevealResult(
  value: unknown,
): SearchQueryRevealResult | undefined {
  if (
    !isRecord(value) ||
    !isCanonicalUuid(value.activityId) ||
    !['AVAILABLE', 'RETENTION_EXPIRED', 'KEY_UNAVAILABLE'].includes(
      String(value.state),
    ) ||
    !isNullableString(value.rawQuery) ||
    !isNullableString(value.keyVersion) ||
    (value.revealedAt !== null && !isTimestamp(value.revealedAt))
  ) {
    return undefined
  }
  return {
    activityId: value.activityId,
    state: value.state as SearchQueryRevealResult['state'],
    rawQuery: value.rawQuery,
    keyVersion: value.keyVersion,
    revealedAt: value.revealedAt,
  }
}

function decodeAuditExportJob(value: unknown): AuditExportJob | undefined {
  if (
    !isRecord(value) ||
    !isCanonicalUuid(value.id) ||
    value.status !== 'REQUESTED' ||
    !isTimestamp(value.createdAt) ||
    !['CSV', 'JSONL'].includes(String(value.format)) ||
    !Array.isArray(value.fields) ||
    !value.fields.every(isNonBlankString) ||
    !isRecord(value.artifact) ||
    value.artifact.state !== 'NOT_CREATED' ||
    value.artifact.generationAvailable !== false
  ) {
    return undefined
  }
  return {
    id: value.id,
    status: 'REQUESTED',
    createdAt: value.createdAt,
    format: value.format as AuditExportJob['format'],
    fields: value.fields,
    artifact: { state: 'NOT_CREATED', generationAvailable: false },
  }
}

function appendAuditFilters(
  search: URLSearchParams,
  filters: AuditActivityFilters,
) {
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== '') search.set(key, String(value))
  })
}

export async function listAuditActivities(
  filters: AuditActivityFilters,
  cursor: string | null,
  interactionId: string,
): Promise<AuditActivityPage> {
  const search = new URLSearchParams()
  appendAuditFilters(search, filters)
  if (cursor) search.set('cursor', cursor)
  const response = await staffFetch(`/api/v1/audit/activities?${search}`, {
    headers: { 'X-Interaction-Id': interactionId },
  })
  const decoded = decodeAuditActivityPage(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function getAuditActivity(
  activityId: string,
  interactionId: string,
): Promise<AuditActivityDetail> {
  const response = await staffFetch(
    `/api/v1/audit/activities/${encodeURIComponent(activityId)}`,
    { headers: { 'X-Interaction-Id': interactionId } },
  )
  const decoded = decodeAuditActivityDetail(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function revealAuditSearchQuery(
  activityId: string,
  reason: string,
  interactionId: string,
): Promise<SearchQueryRevealResult> {
  const response = await unsafeStaffFetch(
    `/api/v1/audit/activities/${encodeURIComponent(activityId)}/search-query-reveal`,
    'POST',
    { reason },
    { 'X-Interaction-Id': interactionId },
  )
  const decoded = decodeSearchQueryRevealResult(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function createAuditExport(
  input: CreateAuditExportInput,
  interactionId: string,
): Promise<AuditExportJob> {
  const response = await unsafeStaffFetch(
    '/api/v1/audit/exports',
    'POST',
    input,
    { 'X-Interaction-Id': interactionId },
  )
  const decoded = decodeAuditExportJob(await checkedBody(response))
  if (!decoded) throw malformedSuccess(response)
  return decoded
}

export async function rebuildAuditProjection(
  interactionId: string,
): Promise<AuditProjectionRebuildResult> {
  const response = await unsafeStaffFetch(
    '/api/v1/audit/projection/rebuild',
    'POST',
    undefined,
    { 'X-Interaction-Id': interactionId },
  )
  const body = await checkedBody(response)
  if (!isRecord(body)) throw malformedSuccess(response)
  const projection = decodeAuditProjectionStatus(body.projection)
  if (
    !projection ||
    ![
      'ticketChangeCount',
      'accessSearchCount',
      'adminSecurityCount',
      'totalCount',
    ].every(
      (key) =>
        typeof body[key] === 'number' &&
        Number.isSafeInteger(body[key]) &&
        (body[key] as number) >= 0,
    ) ||
    !isTimestamp(body.completedAt)
  ) {
    throw malformedSuccess(response)
  }
  return {
    ticketChangeCount: body.ticketChangeCount as number,
    accessSearchCount: body.accessSearchCount as number,
    adminSecurityCount: body.adminSecurityCount as number,
    totalCount: body.totalCount as number,
    completedAt: body.completedAt,
    projection,
  }
}
