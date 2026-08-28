import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import {
  isAmbiguousAdminMutationOutcome,
  recoverAmbiguousAdminMutationOutcome,
} from './adminMutationRecovery'

describe('adminMutationRecovery', () => {
  it('treats network, 5xx, and malformed successful responses as ambiguous', () => {
    expect(isAmbiguousAdminMutationOutcome(new Error('network lost'))).toBe(
      true,
    )
    expect(isAmbiguousAdminMutationOutcome(new ApiError('server', 503))).toBe(
      true,
    )
    expect(
      isAmbiguousAdminMutationOutcome(new ApiError('malformed', 201)),
    ).toBe(true)
  })

  it('does not retry-reconcile definite validation or stale-version rejection', async () => {
    const refresh = vi.fn().mockResolvedValue(undefined)

    await expect(
      recoverAmbiguousAdminMutationOutcome(
        new ApiError('validation', 400),
        refresh,
      ),
    ).resolves.toBe(false)
    await expect(
      recoverAmbiguousAdminMutationOutcome(
        new ApiError('conflict', 409),
        refresh,
      ),
    ).resolves.toBe(false)
    await expect(
      recoverAmbiguousAdminMutationOutcome(new ApiError('stale', 412), refresh),
    ).resolves.toBe(false)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('forces a best-effort reconciliation read for an ambiguous outcome', async () => {
    const refresh = vi.fn().mockRejectedValue(new Error('read unavailable'))

    await expect(
      recoverAmbiguousAdminMutationOutcome(
        new Error('connection reset'),
        refresh,
      ),
    ).resolves.toBe(true)
    expect(refresh).toHaveBeenCalledOnce()
  })
})
