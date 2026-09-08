import { describe, expect, it } from 'vitest'
import { listDecoder } from './api'
import { decodeStatus } from './labels-api'

const status = {
  id: '33333333-3333-4333-8333-333333333333',
  machineKey: 'waiting-team',
  agentLabel: '협업 회신 대기',
  customerLabel: null,
  statusCategory: 'ON_HOLD',
  active: true,
  order: 0,
  defaultForCategory: false,
  allowedFormIds: [],
  description: null,
  version: 1,
}

describe('custom status response boundary', () => {
  it('accepts every contracted status category in a list including ON_HOLD', () => {
    const statuses = ['NEW', 'OPEN', 'PENDING', 'ON_HOLD', 'SOLVED'].map(
      (statusCategory) => ({ ...status, statusCategory }),
    )
    expect(listDecoder(decodeStatus)(statuses)).toEqual(statuses)
  })

  it('rejects the uncontracted HOLD alias', () => {
    expect(decodeStatus({ ...status, statusCategory: 'HOLD' })).toBeUndefined()
  })
})
