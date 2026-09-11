import type { ConsentBlock } from '../../../design-system'
import { decodeConsentBlocks } from './consentDocument'
import type { CustomerAccessMode } from '../../../api/types'

export interface CurrentCustomer {
  id: string
  email: string
  displayName: string | null
  companyName: string | null
  verifiedAt: string
  credentialState: 'PASSWORDLESS' | 'PASSWORD'
  registrationState: 'REGISTRATION_REQUIRED' | 'COMPLETE'
  availableAuthenticationMethods: Array<'MAGIC_LINK' | 'PASSWORD'>
}

export interface CustomerRegistrationInput {
  email: string
  password: string
  displayName: string
  companyName: string
  acceptedPolicies: Array<{ policyKey: string; version: number }>
}

export interface CustomerConsentPolicy {
  policyKey: string
  version: number
  title: string
  required: boolean
  blocks: ConsentBlock[]
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

export class CustomerAuthApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly requestId?: string,
  ) {
    super(message)
    this.name = 'CustomerAuthApiError'
  }
}

export async function requestCustomerMagicLink(email: string): Promise<void> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/customer/auth/magic-link-requests`,
    {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email }),
    },
  )
  if (response.status !== 202)
    throw await responseFailure(response, 'magic-link-request-failed')
}

export async function requestCustomerRegistration(
  input: CustomerRegistrationInput,
): Promise<void> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/customer/registrations`,
    {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    },
  )
  if (response.status !== 202)
    throw await responseFailure(
      response,
      'customer-registration-request-failed',
    )
}

export async function verifyCustomerRegistration(token: string): Promise<void> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/customer/registration-verifications`,
    {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token }),
    },
  )
  if (response.status !== 204)
    throw await responseFailure(
      response,
      'customer-registration-verification-failed',
    )
}

export async function completePasswordlessCustomerRegistration(
  input: Omit<CustomerRegistrationInput, 'email'>,
  expectedCustomerId: string,
): Promise<CurrentCustomer> {
  const csrfResponse = await fetch(`${API_BASE_URL}/api/v1/customer/csrf`, {
    credentials: 'include',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
  })
  if (!csrfResponse.ok)
    throw await responseFailure(csrfResponse, 'customer-csrf-read-failed')
  const csrf: unknown = await csrfResponse.json()
  if (!isCsrfToken(csrf)) throw new Error('customer-csrf-response-invalid')
  const response = await fetch(
    `${API_BASE_URL}/api/v1/customer/me/registration`,
    {
      method: 'PUT',
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-TOKEN': csrf.token,
        'X-Deskseed-Expected-Customer-Id': expectedCustomerId,
      },
      body: JSON.stringify(input),
    },
  )
  if (!response.ok)
    throw await responseFailure(
      response,
      'customer-registration-completion-failed',
    )
  const body: unknown = await response.json()
  if (!isCurrentCustomer(body))
    throw new Error('customer-session-response-invalid')
  return body
}

export async function listRegistrationConsentPolicies(): Promise<
  CustomerConsentPolicy[]
> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/customer/consent-policies?context=REGISTRATION`,
    {
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
    },
  )
  if (!response.ok)
    throw await responseFailure(response, 'customer-consent-policy-read-failed')
  const body: unknown = await response.json()
  if (
    typeof body !== 'object' ||
    body === null ||
    !Array.isArray((body as { policies?: unknown }).policies)
  )
    throw new Error('customer-consent-policy-response-invalid')
  const policies = (body as { policies: unknown[] }).policies
  if (policies.length > 20)
    throw new Error('customer-consent-policy-response-invalid')
  const decoded = policies.map((item): CustomerConsentPolicy => {
    if (typeof item !== 'object' || item === null)
      throw new Error('customer-consent-policy-response-invalid')
    const policy = item as Record<string, unknown>
    const document = policy.document as {
      schemaVersion?: unknown
      blocks?: unknown
    } | null
    if (
      typeof policy.policyKey !== 'string' ||
      !policy.policyKey ||
      !Number.isSafeInteger(policy.version) ||
      Number(policy.version) < 1 ||
      typeof policy.title !== 'string' ||
      !policy.title ||
      typeof policy.required !== 'boolean' ||
      !document ||
      document.schemaVersion !== 1 ||
      !Array.isArray(document.blocks)
    )
      throw new Error('customer-consent-policy-response-invalid')
    const blocks = decodeConsentBlocks(document.blocks)
    return {
      policyKey: policy.policyKey,
      version: Number(policy.version),
      title: policy.title,
      required: policy.required,
      blocks,
    }
  })
  if (
    new Set(decoded.map((policy) => policy.policyKey)).size !== decoded.length
  )
    throw new Error('customer-consent-policy-response-invalid')
  return decoded
}

export async function createCustomerPasswordSession(
  email: string,
  password: string,
): Promise<CurrentCustomer> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/customer/auth/password-sessions`,
    {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    },
  )
  if (!response.ok)
    throw await responseFailure(response, 'customer-password-login-failed')
  const body: unknown = await response.json()
  if (!isCurrentCustomer(body))
    throw new Error('customer-session-response-invalid')
  return body
}

export async function consumeCustomerMagicLink(
  token: string,
): Promise<CurrentCustomer> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/customer/auth/magic-link-sessions`,
    {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token }),
    },
  )
  if (!response.ok)
    throw await responseFailure(response, 'magic-link-consume-failed')
  const body: unknown = await response.json()
  if (!isCurrentCustomer(body)) throw new Error('magic-link-response-invalid')
  return body
}

export async function getCurrentCustomer(): Promise<CurrentCustomer | null> {
  const response = await fetch(`${API_BASE_URL}/api/v1/customer/me`, {
    credentials: 'include',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
  })
  if (response.status === 401) return null
  if (!response.ok)
    throw await responseFailure(response, 'customer-session-read-failed')
  const body: unknown = await response.json()
  if (!isCurrentCustomer(body))
    throw new Error('customer-session-response-invalid')
  return body
}

export async function getCustomerAccessMode(): Promise<CustomerAccessMode> {
  const response = await fetch(`${API_BASE_URL}/api/v1/customer/access-mode`, {
    credentials: 'include',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
  })
  if (!response.ok)
    throw await responseFailure(response, 'customer-access-mode-read-failed')
  const body: unknown = await response.json()
  if (
    typeof body !== 'object' ||
    body === null ||
    Array.isArray(body) ||
    ![
      'ANONYMOUS_ALLOWED',
      'REGISTRATION_OPTIONAL',
      'REGISTRATION_REQUIRED',
    ].includes(String((body as Record<string, unknown>).mode))
  ) {
    throw new Error('customer-access-mode-response-invalid')
  }
  return (body as { mode: CustomerAccessMode }).mode
}

export async function deleteCustomerSession(): Promise<void> {
  const csrfResponse = await fetch(`${API_BASE_URL}/api/v1/customer/csrf`, {
    credentials: 'include',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
  })
  if (!csrfResponse.ok)
    throw await responseFailure(csrfResponse, 'customer-csrf-read-failed')
  const csrfBody: unknown = await csrfResponse.json().catch(() => undefined)
  if (!isCsrfToken(csrfBody)) {
    throw new CustomerAuthApiError(
      'customer-csrf-response-invalid',
      csrfResponse.status,
      responseRequestId(csrfResponse, csrfBody),
    )
  }

  const response = await fetch(`${API_BASE_URL}/api/v1/customer/session`, {
    method: 'DELETE',
    credentials: 'include',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
    headers: { 'X-CSRF-TOKEN': csrfBody.token },
  })
  if (!response.ok)
    throw await responseFailure(response, 'customer-session-delete-failed')
}

async function responseFailure(response: Response, message: string) {
  const body: unknown = await response.json().catch(() => undefined)
  return new CustomerAuthApiError(
    message,
    response.status,
    responseRequestId(response, body),
  )
}

function responseRequestId(response: Response, body: unknown) {
  if (
    typeof body === 'object' &&
    body !== null &&
    !Array.isArray(body) &&
    typeof (body as Record<string, unknown>).requestId === 'string'
  ) {
    return (body as Record<string, string>).requestId
  }
  return response.headers.get('X-Request-Id') ?? undefined
}

function isCsrfToken(
  value: unknown,
): value is { token: string; headerName: 'X-CSRF-TOKEN' } {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    return false
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.token === 'string' &&
    candidate.token.length >= 32 &&
    candidate.token.length <= 256 &&
    candidate.headerName === 'X-CSRF-TOKEN'
  )
}

function isCurrentCustomer(value: unknown): value is CurrentCustomer {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    return false
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.id === 'string' &&
    typeof candidate.email === 'string' &&
    (typeof candidate.displayName === 'string' ||
      candidate.displayName === null) &&
    (typeof candidate.companyName === 'string' ||
      candidate.companyName === null) &&
    typeof candidate.verifiedAt === 'string' &&
    ['PASSWORDLESS', 'PASSWORD'].includes(String(candidate.credentialState)) &&
    ['REGISTRATION_REQUIRED', 'COMPLETE'].includes(
      String(candidate.registrationState),
    ) &&
    Array.isArray(candidate.availableAuthenticationMethods) &&
    candidate.availableAuthenticationMethods.every((method) =>
      ['MAGIC_LINK', 'PASSWORD'].includes(String(method)),
    )
  )
}
