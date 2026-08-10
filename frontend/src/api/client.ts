import type {
  ProblemDetails,
  PublicComment,
  PublicRequest,
  SubmitRequestInput,
  SubmittedRequest,
  TicketStatus,
} from './types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
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
