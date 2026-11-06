import { describe, expect, it, vi } from 'vitest'
import { requestStaffResource } from '../../api/client'
vi.mock('../../api/client', () => ({ requestStaffResource: vi.fn() }))
import {
  decodeField,
  decodeFormPreview,
  previewForm,
  decodeForm,
  listDecoder,
  saveForm,
  type TicketForm,
} from './api'

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

it('preserves an existing form description when saving another property', async () => {
  const existing: TicketForm = {
    id: 'form',
    version: 1,
    name: '환불',
    lifecycle: 'DRAFT',
    defaultForCustomer: false,
    defaultForAgent: false,
    placements: [],
    conditionalRules: [],
    allowedCustomStatusIds: [],
  }
  const decoded = decodeForm({ ...existing, description: '기존 API 안내문' })!
  await saveForm({ ...decoded, name: '환불 문의' }, decoded)
  expect(requestStaffResource).toHaveBeenCalledWith(
    '/api/v1/admin/ticket-forms/form',
    expect.any(Function),
    expect.objectContaining({
      method: 'PUT',
      version: 1,
      body: expect.objectContaining({
        name: '환불 문의',
        description: '기존 API 안내문',
      }),
    }),
  )
})

it('rejects incomplete preview policies and mismatched resource identity', async () => {
  expect(
    decodeFormPreview({ formId: 'form', formVersion: 0, fields: [] }),
  ).toBeUndefined()
  expect(
    decodeFormPreview({
      formId: 'form',
      formVersion: 1,
      fields: [{ visible: true, editable: true, field: {} }],
    }),
  ).toBeUndefined()
  expect(
    decodeFormPreview({ formId: 'form', formVersion: 2, fields: [] }),
  ).toEqual({ formId: 'form', formVersion: 2, fields: [] })
  await previewForm('form', {
    actorKind: 'AGENT',
    ticketKind: 'AGENT_CREATED',
    statusCategory: 'OPEN',
    fieldValues: { amount: { numberValue: '12345678901234567890.12' } },
  })
  const call = vi.mocked(requestStaffResource).mock.lastCall!
  expect(call[0]).toBe('/api/v1/admin/ticket-forms/form/preview')
  expect(
    call[1]({ formId: 'other', formVersion: 1, fields: [] }),
  ).toBeUndefined()
  expect(call[2]).toEqual({
    method: 'POST',
    body: {
      actorKind: 'AGENT',
      ticketKind: 'AGENT_CREATED',
      statusCategory: 'OPEN',
      fieldValues: { amount: { numberValue: '12345678901234567890.12' } },
    },
  })
})
