import { describe, expect, it } from 'vitest'
import { decodeField, decodeForm, listDecoder } from './api'

describe('configuration response boundary', () => {
  it('rejects unknown field types and missing visibility policies', () => {
    expect(
      decodeField({
        id: 'field',
        version: 1,
        type: 'SCRIPT',
        staffLabel: 'unsafe',
      }),
    ).toBeUndefined()
    expect(
      decodeField({
        id: 'field',
        version: 1,
        type: 'SHORT_TEXT',
        staffLabel: 'order',
      }),
    ).toBeUndefined()
  })
  it('does not silently discard malformed list items', () => {
    expect(listDecoder(decodeField)([null])).toBeUndefined()
    expect(listDecoder(decodeField)([])).toEqual([])
  })
  it('rejects forms without actor policy and unknown lifecycle', () => {
    const form = {
      id: 'form',
      version: 1,
      name: 'Refund',
      lifecycle: 'PUBLISHED',
      defaultForCustomer: true,
      defaultForAgent: true,
      placements: [{ fieldId: 'field', order: 0 }],
      conditionalRules: [],
      allowedCustomStatusIds: [],
    }
    expect(decodeForm(form)).toBeUndefined()
    expect(
      decodeForm({ ...form, lifecycle: 'UNKNOWN', placements: [] }),
    ).toBeUndefined()
  })
})
