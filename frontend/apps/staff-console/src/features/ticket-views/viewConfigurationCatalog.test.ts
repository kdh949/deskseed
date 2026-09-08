import { describe, expect, it } from 'vitest'
import { decodeViewConfigurationCatalog } from './viewConfigurationCatalog'
describe('view filter catalog boundary', () => {
  const field = {
    id: '11111111-1111-4111-8111-111111111111',
    machineKey: 'refund.amount',
    label: '환불 금액',
    type: 'NUMBER',
    options: [],
  }
  const catalog = { fields: [field], tags: [], forms: [], statuses: [] }
  it('retains the server machine key used in saved condition fieldKey', () => {
    expect(decodeViewConfigurationCatalog(catalog)?.fields[0]?.machineKey).toBe(
      'refund.amount',
    )
    expect(
      decodeViewConfigurationCatalog({
        ...catalog,
        fields: [{ ...field, machineKey: undefined, key: field.machineKey }],
      }),
    ).toBeUndefined()
  })
  it('rejects non-queryable field types and malformed choice collections', () => {
    expect(
      decodeViewConfigurationCatalog({
        ...catalog,
        fields: [{ ...field, type: 'LONG_TEXT' }],
      }),
    ).toBeUndefined()
    expect(
      decodeViewConfigurationCatalog({ ...catalog, tags: [{ id: 'tag' }] }),
    ).toBeUndefined()
  })
})
