import type {
  CreateStaffInput,
  CurrentStaff,
  GroupMembership,
  ProblemDetails,
  PublicComment,
  PublicRequest,
  StaffAccount,
  StaffRole,
  SubmitRequestInput,
  SubmittedRequest,
  SupportGroup,
  TicketStatus,
} from './types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
export const STAFF_SESSION_INVALID_EVENT = 'deskseed:staff-session-invalid'
const TICKET_STATUSES = new Set<TicketStatus>([
  'NEW',
  'OPEN',
  'PENDING',
  'SOLVED',
])
const ACCESS_TOKEN_MIN_LENGTH = 32
const ACCESS_TOKEN_MAX_LENGTH = 256

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

function isTicketNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
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

const STAFF_ROLES = new Set<StaffRole>(['ADMIN', 'AGENT'])

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
