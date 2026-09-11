import type { CurrentCustomer } from './api/customerAuthClient'
const safeDestination = (from: unknown): from is string =>
  typeof from === 'string' && /^\/account\/requests(?:\/[1-9]\d*)?$/.test(from)

export function customerAuthDestination(
  customer: CurrentCustomer,
  from?: unknown,
) {
  if (customer.registrationState === 'REGISTRATION_REQUIRED')
    return '/customer/register/complete'
  return safeDestination(from) ? from : '/account/requests'
}

const returnKey = 'deskseed-customer-login-return'
const lifetime = 15 * 60 * 1000
interface AuthContinuation {
  version: 1
  id: string
  from: string
  expiresAt: number
}

// One short-lived, latest-request continuation per browser. It carries navigation,
// never authentication proof, and cannot grant access to the destination request.
export function rememberCustomerAuthDestination(from: unknown) {
  try {
    sessionStorage.removeItem(returnKey)
    localStorage.removeItem(returnKey)
    if (safeDestination(from))
      localStorage.setItem(
        returnKey,
        JSON.stringify({
          version: 1,
          id: crypto.randomUUID(),
          from,
          expiresAt: Date.now() + lifetime,
        }),
      )
  } catch {
    /* Authentication works even if optional navigation storage is blocked. */
  }
}

export function readCustomerAuthContinuation(): AuthContinuation | null {
  try {
    const raw = localStorage.getItem(returnKey)
    if (!raw) return null
    const value: unknown = raw.length <= 512 ? JSON.parse(raw) : null
    if (value && typeof value === 'object') {
      const row = value as Record<string, unknown>
      if (
        row.version === 1 &&
        typeof row.id === 'string' &&
        /^[0-9a-f-]{36}$/.test(row.id) &&
        safeDestination(row.from) &&
        typeof row.expiresAt === 'number' &&
        row.expiresAt > Date.now() &&
        row.expiresAt <= Date.now() + lifetime
      )
        return {
          version: 1,
          id: row.id,
          from: row.from,
          expiresAt: row.expiresAt,
        }
    }
    localStorage.removeItem(returnKey)
  } catch {
    /* Invalid or unavailable navigation memory does not affect authentication. */
  }
  return null
}

export function takeCustomerAuthDestination(
  expectedId?: string,
): string | null {
  const continuation = readCustomerAuthContinuation()
  if (
    !continuation ||
    (expectedId !== undefined && continuation.id !== expectedId)
  )
    return null
  try {
    localStorage.removeItem(returnKey)
  } catch {
    /* Optional navigation memory. */
  }
  return continuation.from
}
