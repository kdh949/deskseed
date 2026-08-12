import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAuditInteractionId } from './auditInteraction'

afterEach(() => vi.unstubAllGlobals())

describe('audit interaction model', () => {
  it('uses one opaque UUID suitable for list, detail, reveal, and export linkage', () => {
    const randomUUID = vi.fn(() => '11111111-1111-4111-8111-111111111111')
    vi.stubGlobal('crypto', { randomUUID })

    expect(createAuditInteractionId()).toBe(
      '11111111-1111-4111-8111-111111111111',
    )
    expect(randomUUID).toHaveBeenCalledTimes(1)
  })

  it('keeps RFC 4122 version and variant bits in the fallback path', () => {
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes: Uint8Array) => bytes.fill(0xab),
    })

    expect(createAuditInteractionId()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
  })
})
