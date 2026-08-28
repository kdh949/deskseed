import type {
  AttachmentDownload,
  AttachmentUpload,
  ProblemDetails,
  PublicComment,
  PublicRequest,
  SubmittedRequest,
  SubmitRequestInput,
  TicketAttachment,
  TicketStatus,
} from './types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
const TICKET_STATUSES = new Set<TicketStatus>([
  'NEW',
  'OPEN',
  'PENDING',
  'SOLVED',
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
    this.name = 'CustomerApiError'
  }
}

export async function submitRequest(
  input: SubmitRequestInput,
  authenticatedCustomer = false,
): Promise<SubmittedRequest> {
  const headers = await mutationHeaders(authenticatedCustomer, true)
  const response = await fetch(`${API_BASE_URL}/api/v1/requests`, {
    method: 'POST',
    credentials: 'include',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
    headers,
    body: JSON.stringify(input),
  })
  const submitted = decodeSubmittedRequest(await checkedJson(response))
  if (!submitted) throw malformed(response)
  return submitted
}

export async function submitRequestWithAttachments(
  input: SubmitRequestInput,
  files: File[],
  authenticatedCustomer = false,
): Promise<SubmittedRequest> {
  const form = new FormData()
  form.set('name', input.name)
  form.set('email', input.email)
  form.set('subject', input.subject)
  form.set('message', input.message)
  if (input.privacyConsent !== undefined)
    form.set('privacyConsent', String(input.privacyConsent))
  files.forEach((file) => form.append('attachments', file, file.name))
  const response = await fetch(`${API_BASE_URL}/api/v1/requests`, {
    method: 'POST',
    credentials: 'include',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
    headers: await mutationHeaders(authenticatedCustomer, false),
    body: form,
  })
  const submitted = decodeSubmittedRequest(await checkedJson(response))
  if (!submitted) throw malformed(response)
  return submitted
}

export async function getPublicRequest(
  ticketNumber: number,
  accessToken: string,
): Promise<PublicRequest> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/requests/${ticketNumber}`,
    customerOptions({ 'X-Request-Access-Token': accessToken }),
  )
  const request = decodePublicRequest(await checkedJson(response))
  if (!request) throw malformed(response)
  return request
}

export async function addAnonymousRequestComment(
  ticketNumber: number,
  accessToken: string,
  body: string,
  clientCommandId: string,
  attachmentIds: string[] = [],
): Promise<PublicComment> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/requests/${ticketNumber}/comments`,
    {
      ...customerOptions({
        'Content-Type': 'application/json',
        'X-Request-Access-Token': accessToken,
      }),
      method: 'POST',
      body: JSON.stringify({ body, clientCommandId, attachmentIds }),
    },
  )
  const comment = decodePublicComment(await checkedJson(response))
  if (!comment) throw malformed(response)
  return comment
}

export async function uploadAnonymousRequestAttachment(
  ticketNumber: number,
  accessToken: string,
  file: File,
): Promise<AttachmentUpload> {
  const form = new FormData()
  form.append('file', file, file.name)
  const response = await fetch(
    `${API_BASE_URL}/api/v1/requests/${ticketNumber}/attachments/uploads`,
    {
      ...customerOptions({ 'X-Request-Access-Token': accessToken }),
      method: 'POST',
      body: form,
    },
  )
  const upload = decodeAttachmentUpload(await checkedJson(response))
  if (!upload) throw malformed(response)
  return upload
}

export async function downloadAnonymousRequestAttachment(
  ticketNumber: number,
  attachmentId: string,
  accessToken: string,
): Promise<AttachmentDownload> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/requests/${ticketNumber}/attachments/${encodeURIComponent(attachmentId)}/download`,
    customerOptions({ 'X-Request-Access-Token': accessToken }),
  )
  if (!response.ok) throw await apiError(response)
  const contentType = response.headers
    .get('Content-Type')
    ?.split(';')[0]
    ?.trim()
  if (!contentType) throw malformed(response)
  return {
    content: await response.blob(),
    contentType,
    fileName: responseFileName(response),
  }
}

function customerOptions(headers?: Record<string, string>): RequestInit {
  return {
    credentials: 'include',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
    headers,
  }
}

async function mutationHeaders(
  authenticated: boolean,
  json: boolean,
): Promise<Record<string, string>> {
  const headers: Record<string, string> = json
    ? { 'Content-Type': 'application/json' }
    : {}
  if (!authenticated) return headers
  const response = await fetch(
    `${API_BASE_URL}/api/v1/customer/csrf`,
    customerOptions(),
  )
  const body = await checkedJson(response)
  if (!isRecord(body) || !isNonBlankString(body.token))
    throw malformed(response)
  headers['X-CSRF-TOKEN'] = body.token
  return headers
}

async function checkedJson(response: Response): Promise<unknown> {
  const body = await response.json().catch(() => undefined)
  if (!response.ok) throw failure(response, body)
  return body
}

function failure(response: Response, value: unknown) {
  const problem = decodeProblem(value)
  const fieldErrors = Object.fromEntries(
    (problem?.fieldErrors ?? []).map((item) => [item.field, item.message]),
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

async function apiError(response: Response) {
  return failure(response, await response.json().catch(() => undefined))
}

function malformed(response: Response) {
  return new ApiError(
    '서버 응답을 안전하게 처리할 수 없습니다.',
    response.status,
    undefined,
    response.headers.get('X-Request-Id') ?? undefined,
  )
}

function decodeProblem(value: unknown): ProblemDetails | undefined {
  if (!isRecord(value)) return undefined
  const fieldErrors = Array.isArray(value.fieldErrors)
    ? value.fieldErrors.flatMap((item) => {
        if (
          !isRecord(item) ||
          !isNonBlankString(item.field) ||
          !isNonBlankString(item.message)
        )
          return []
        return [{ field: item.field, message: item.message }]
      })
    : undefined
  return {
    ...(typeof value.type === 'string' ? { type: value.type } : {}),
    ...(typeof value.title === 'string' ? { title: value.title } : {}),
    ...(typeof value.detail === 'string' ? { detail: value.detail } : {}),
    ...(typeof value.status === 'number' ? { status: value.status } : {}),
    ...(typeof value.requestId === 'string'
      ? { requestId: value.requestId }
      : {}),
    ...(fieldErrors ? { fieldErrors } : {}),
  }
}

function decodeSubmittedRequest(value: unknown): SubmittedRequest | undefined {
  if (
    !isRecord(value) ||
    !isTicketNumber(value.ticketNumber) ||
    !isTicketStatus(value.status) ||
    !isNonBlankString(value.accessToken) ||
    !isTimestamp(value.createdAt)
  )
    return undefined
  return value as unknown as SubmittedRequest
}

function decodeTicketAttachment(value: unknown): TicketAttachment | undefined {
  if (
    !isRecord(value) ||
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.fileName) ||
    !Number.isSafeInteger(value.sizeBytes) ||
    !isNonBlankString(value.contentType)
  )
    return undefined
  return value as unknown as TicketAttachment
}

function decodeAttachmentUpload(value: unknown): AttachmentUpload | undefined {
  const attachment = decodeTicketAttachment(value)
  if (
    !attachment ||
    !isRecord(value) ||
    value.scanStatus !== 'CLEAN' ||
    !isTimestamp(value.expiresAt)
  )
    return undefined
  return value as unknown as AttachmentUpload
}

function decodePublicComment(value: unknown): PublicComment | undefined {
  if (!isRecord(value) || !Array.isArray(value.attachments)) return undefined
  const attachments = value.attachments.map(decodeTicketAttachment)
  if (
    !isNonBlankString(value.id) ||
    !isNonBlankString(value.authorDisplayName) ||
    !isNonBlankString(value.body) ||
    !isTimestamp(value.createdAt) ||
    attachments.some((item) => !item)
  )
    return undefined
  return {
    ...(value as unknown as PublicComment),
    attachments: attachments as TicketAttachment[],
  }
}

function decodePublicRequest(value: unknown): PublicRequest | undefined {
  if (!isRecord(value) || !Array.isArray(value.comments)) return undefined
  const comments = value.comments.map(decodePublicComment)
  if (
    !isTicketNumber(value.ticketNumber) ||
    !isNonBlankString(value.subject) ||
    !isTicketStatus(value.status) ||
    !isTimestamp(value.createdAt) ||
    !isTimestamp(value.updatedAt) ||
    comments.some((item) => !item)
  )
    return undefined
  return {
    ...(value as unknown as PublicRequest),
    comments: comments as PublicComment[],
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

function isTicketNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isTicketStatus(value: unknown): value is TicketStatus {
  return typeof value === 'string' && TICKET_STATUSES.has(value as TicketStatus)
}

function responseFileName(response: Response): string | null {
  const disposition = response.headers.get('Content-Disposition')
  if (!disposition) return null
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (encoded) {
    try {
      return decodeURIComponent(encoded)
    } catch {
      return null
    }
  }
  return disposition.match(/filename="?([^";]+)"?/i)?.[1] ?? null
}
