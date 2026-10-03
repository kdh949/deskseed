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

it.each([
  '9007199254740993',
  '123456789012345678.123456789012',
  '-0.000000000001',
])(
  'preserves the exact decimal string %s from the HTTP response',
  (numberValue) => {
    const decoded = decodeConfiguration(
      JSON.parse(
        JSON.stringify({
          ...response,
          fieldValues: { amount: { numberValue } },
        }),
      ),
    )
    expect(decoded?.fieldValues.amount).toEqual({ numberValue })
  },
)

it.each([9007199254740992, '', 'NaN', 'Infinity', '1e100', '1'.repeat(81)])(
  'rejects an inexact or malformed numeric response: %s',
  (numberValue) => {
    expect(
      decodeConfiguration({
        ...response,
        fieldValues: { amount: { numberValue } },
      }),
    ).toBeUndefined()
  },
)
