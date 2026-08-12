import type { CustomerAccessMode } from '../../api/types'

export interface CurrentCustomer {
  id: string
  email: string
  displayName: string
  verifiedAt: string
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

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
  if (response.status !== 202) throw new Error('magic-link-request-failed')
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
  if (!response.ok) throw new Error('magic-link-consume-failed')
  const body: unknown = await response.json()
  if (!isCurrentCustomer(body)) throw new Error('magic-link-response-invalid')
  return body
}

export async function getCurrentCustomer(): Promise<CurrentCustomer | null> {
  const response = await fetch(`${API_BASE_URL}/api/v1/customer/me`, {
    credentials: 'include',
    cache: 'no-store',
  })
  if (response.status === 401) return null
  if (!response.ok) throw new Error('customer-session-read-failed')
  const body: unknown = await response.json()
  if (!isCurrentCustomer(body))
    throw new Error('customer-session-response-invalid')
  return body
}

export async function getCustomerAccessMode(): Promise<CustomerAccessMode> {
  const response = await fetch(`${API_BASE_URL}/api/v1/customer/access-mode`, {
    credentials: 'include',
    cache: 'no-store',
  })
  if (!response.ok) throw new Error('customer-access-mode-read-failed')
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

function isCurrentCustomer(value: unknown): value is CurrentCustomer {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    return false
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.id === 'string' &&
    typeof candidate.email === 'string' &&
    typeof candidate.displayName === 'string' &&
    typeof candidate.verifiedAt === 'string'
  )
}
