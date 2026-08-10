import type {
  ProblemDetails,
  PublicRequest,
  SubmitRequestInput,
  SubmittedRequest,
} from './types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

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

async function readProblem(
  response: Response,
): Promise<ProblemDetails | undefined> {
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('json')) return undefined
  try {
    return (await response.json()) as ProblemDetails
  } catch {
    return undefined
  }
}

async function assertOk(response: Response): Promise<void> {
  if (response.ok) return
  const problem = await readProblem(response)
  const fieldErrors = Object.fromEntries(
    (problem?.fieldErrors ?? [])
      .filter(
        (error) =>
          typeof error.field === 'string' && typeof error.message === 'string',
      )
      .map((error) => [error.field, error.message]),
  )
  throw new ApiError(
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

export async function submitRequest(
  input: SubmitRequestInput,
): Promise<SubmittedRequest> {
  const response = await fetch(`${API_BASE_URL}/api/v1/requests`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    cache: 'no-store',
    body: JSON.stringify(input),
  })
  await assertOk(response)
  return (await response.json()) as SubmittedRequest
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
  await assertOk(response)
  return (await response.json()) as PublicRequest
}
