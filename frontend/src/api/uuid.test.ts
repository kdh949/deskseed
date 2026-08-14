import { afterEach, describe, expect, it, vi } from 'vitest'
import { createOpaqueUuid } from './uuid'

afterEach(() => vi.unstubAllGlobals())

describe('createOpaqueUuid', () => {
  it('uses crypto.randomUUID when available', () => {
    const randomUUID = vi.fn(() => '11111111-1111-4111-8111-111111111111')
    vi.stubGlobal('crypto', { randomUUID })

    expect(createOpaqueUuid()).toBe('11111111-1111-4111-8111-111111111111')
    expect(randomUUID).toHaveBeenCalledTimes(1)
  })

  it('keeps RFC 4122 version and variant bits in the getRandomValues fallback', () => {
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes: Uint8Array) => bytes.fill(0xab),
    })

    expect(createOpaqueUuid()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
  })

  it('keeps RFC 4122 version and variant bits in the Math.random fallback', () => {
    vi.stubGlobal('crypto', undefined)

    expect(createOpaqueUuid()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
  })
})
