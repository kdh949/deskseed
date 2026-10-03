import { describe, expect, it } from 'vitest'
import { decodeAutomation } from './automation-api'
describe('time automation contract', () => {
  it('rejects unsupported actions and invalid duration before presenting an editable policy', () => {
    const policy = {
      id: 'policy',
      name: '해결 후 종료',
      position: 1,
      currentVersion: 2,
      aggregateVersion: 3,
      solvedAgeMinutes: 60,
      actionType: 'CLOSE_TICKET',
      createdAt: '2026-09-08T00:00:00Z',
      updatedAt: '2026-09-08T00:00:00Z',
    }
    expect(decodeAutomation(policy)?.activeVersion).toBeNull()
    expect(
      decodeAutomation({ ...policy, actionType: 'SEND_EMAIL' }),
    ).toBeUndefined()
    expect(decodeAutomation({ ...policy, solvedAgeMinutes: 0 })).toBeUndefined()
    expect(
      decodeAutomation({ ...policy, solvedAgeMinutes: 1.5 }),
    ).toBeUndefined()
    expect(
      decodeAutomation({ ...policy, solvedAgeMinutes: 525601 }),
    ).toBeUndefined()
  })
})
