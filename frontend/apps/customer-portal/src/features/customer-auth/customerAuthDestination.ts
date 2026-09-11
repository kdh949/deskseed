import type { CurrentCustomer } from './api/customerAuthClient'
export function customerAuthDestination(
  customer: CurrentCustomer,
  from?: unknown,
) {
  if (customer.registrationState === 'REGISTRATION_REQUIRED')
    return '/customer/register/complete'
  return typeof from === 'string' &&
    /^\/account\/requests(?:\/[1-9]\d*)?$/.test(from)
    ? from
    : '/account/requests'
}

const returnKey = 'deskseed-customer-login-return'
export function rememberCustomerAuthDestination(from: unknown) {
  try {
    sessionStorage.removeItem(returnKey)
    if (
      typeof from === 'string' &&
      /^\/account\/requests(?:\/[1-9]\d*)?$/.test(from)
    )
      sessionStorage.setItem(returnKey, from)
  } catch {
    /* Navigation memory is optional; authentication remains available. */
  }
}
export function takeCustomerAuthDestination(): string | null {
  try {
    const from = sessionStorage.getItem(returnKey)
    sessionStorage.removeItem(returnKey)
    return from
  } catch {
    return null
  }
}
