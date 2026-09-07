import { expect, it } from 'vitest'
import { decodeConfiguration } from './runtime-api'
const response = {
  ticketNumber: 1042,
  version: 3,
  writable: true,
  form: null,
  fieldValues: {},
  tags: [],
  statusCategory: 'OPEN',
  customStatus: null,
  availableTags: [],
  availableStatuses: [],
}
it('normalizes legacy nullable typed values before a write command', () => {
  expect(
    decodeConfiguration({
      ...response,
      fieldValues: {
        urgency: {
          booleanValue: false,
          numberValue: null,
          optionId: null,
          shortTextValue: null,
          longTextValue: null,
        },
      },
    })?.fieldValues,
  ).toEqual({ urgency: { booleanValue: false } })
})
it('rejects ambiguous values and projections without explicit authorization', () => {
  expect(
    decodeConfiguration({
      ...response,
      fieldValues: { urgency: { shortTextValue: 'high', numberValue: 1 } },
    }),
  ).toBeUndefined()
  expect(
    decodeConfiguration({ ...response, writable: undefined }),
  ).toBeUndefined()
})
