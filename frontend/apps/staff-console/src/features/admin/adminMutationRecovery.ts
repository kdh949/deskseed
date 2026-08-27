import { ApiError } from '../../api/client'

export function isAmbiguousAdminMutationOutcome(error: unknown) {
  if (!(error instanceof ApiError)) return true
  return error.status >= 500 || (error.status >= 200 && error.status < 300)
}

export async function recoverAmbiguousAdminMutationOutcome(
  error: unknown,
  refresh: () => Promise<void>,
) {
  if (!isAmbiguousAdminMutationOutcome(error)) return false
  await refresh().catch(() => undefined)
  return true
}
