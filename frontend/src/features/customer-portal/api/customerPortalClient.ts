export type CustomerRequestStatus = 'NEW' | 'OPEN' | 'PENDING' | 'SOLVED'

export interface CustomerRequestSummary {
  ticketNumber: number
  subject: string
  status: CustomerRequestStatus
  createdAt: string
  updatedAt: string
}

export interface CustomerPublicComment {
  id: string
  authorDisplayName: string
  body: string
  createdAt: string
}

export interface CustomerRequestDetail extends CustomerRequestSummary {
  comments: CustomerPublicComment[]
}

export interface CustomerRequestPage {
  items: CustomerRequestSummary[]
  nextCursor: string | null
}

export type CustomerClaimProof =
  { requestAccessToken: string } | { claimToken: string }

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
const STATUSES = new Set<CustomerRequestStatus>([
  'NEW',
  'OPEN',
  'PENDING',
  'SOLVED',
])

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly requestId?: string,
  ) {
    super(message)
    this.name = 'CustomerPortalApiError'
  }
}

export async function listCustomerRequests(
  status?: CustomerRequestStatus,
  cursor?: string,
  limit = 25,
): Promise<CustomerRequestPage> {
  const parameters = new URLSearchParams()
  if (status) parameters.set('status', status)
  if (cursor) parameters.set('cursor', cursor)
  parameters.set('limit', String(limit))
  const response = await customerFetch(
    `/api/v1/customer/requests?${parameters.toString()}`,
  )
  const body = await checkedJson(response)
  if (!isRecord(body) || !Array.isArray(body.items)) throw malformed(response)
  const items = body.items.map(decodeSummary)
  if (
    items.some((item) => !item) ||
    (body.nextCursor !== null && typeof body.nextCursor !== 'string')
  ) {
    throw malformed(response)
  }
  return {
    items: items as CustomerRequestSummary[],
    nextCursor: body.nextCursor as string | null,
  }
}

export async function getCustomerRequest(
  ticketNumber: number,
): Promise<CustomerRequestDetail> {
  const response = await customerFetch(
    `/api/v1/customer/requests/${ticketNumber}`,
  )
  const body = await checkedJson(response)
  const summary = decodeSummary(body)
  if (!summary || !isRecord(body) || !Array.isArray(body.comments))
    throw malformed(response)
  const comments = body.comments.map(decodeComment)
  if (comments.some((comment) => !comment)) throw malformed(response)
  return { ...summary, comments: comments as CustomerPublicComment[] }
}

export async function addCustomerFollowUp(
  ticketNumber: number,
  body: string,
  clientCommandId: string,
): Promise<CustomerPublicComment> {
  const response = await unsafeCustomerFetch(
    `/api/v1/customer/requests/${ticketNumber}/comments`,
    { body, clientCommandId },
  )
  const decoded = decodeComment(await checkedJson(response))
  if (!decoded) throw malformed(response)
  return decoded
}

export async function claimCustomerRequest(
  ticketNumber: number,
  proof: CustomerClaimProof,
): Promise<void> {
  const response = await unsafeCustomerFetch(
    `/api/v1/customer/requests/${ticketNumber}/claim`,
    proof,
  )
  if (!response.ok) throw await apiError(response)
}

async function unsafeCustomerFetch(
  path: string,
  body: unknown,
): Promise<Response> {
  const csrfResponse = await customerFetch('/api/v1/customer/csrf')
  const csrfBody = await checkedJson(csrfResponse)
  if (!isRecord(csrfBody) || typeof csrfBody.token !== 'string')
    throw malformed(csrfResponse)
  return fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    credentials: 'include',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrfBody.token,
    },
    body: JSON.stringify(body),
  })
}

async function customerFetch(path: string): Promise<Response> {
  return fetch(`${API_BASE_URL}${path}`, {
    credentials: 'include',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
  })
}

async function checkedJson(response: Response): Promise<unknown> {
  if (!response.ok) throw await apiError(response)
  return response.json()
}

async function apiError(response: Response): Promise<ApiError> {
  const body = await response.json().catch(() => undefined)
  const requestId =
    isRecord(body) && typeof body.requestId === 'string'
      ? body.requestId
      : undefined
  return new ApiError(
    `customer-portal-request-${response.status}`,
    response.status,
    requestId,
  )
}

function malformed(response: Response) {
  return new ApiError('customer-portal-response-invalid', response.status)
}

function decodeSummary(value: unknown): CustomerRequestSummary | undefined {
  if (!isRecord(value)) return undefined
  if (
    typeof value.ticketNumber !== 'number' ||
    !Number.isSafeInteger(value.ticketNumber) ||
    typeof value.subject !== 'string' ||
    typeof value.status !== 'string' ||
    !STATUSES.has(value.status as CustomerRequestStatus) ||
    !isTimestamp(value.createdAt) ||
    !isTimestamp(value.updatedAt)
  )
    return undefined
  return {
    ticketNumber: value.ticketNumber,
    subject: value.subject,
    status: value.status as CustomerRequestStatus,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  }
}

function decodeComment(value: unknown): CustomerPublicComment | undefined {
  if (!isRecord(value)) return undefined
  if (
    typeof value.id !== 'string' ||
    typeof value.authorDisplayName !== 'string' ||
    typeof value.body !== 'string' ||
    !isTimestamp(value.createdAt)
  )
    return undefined
  return {
    id: value.id,
    authorDisplayName: value.authorDisplayName,
    body: value.body,
    createdAt: value.createdAt,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isTimestamp(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value))
}
