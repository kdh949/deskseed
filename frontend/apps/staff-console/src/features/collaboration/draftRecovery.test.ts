import { describe, expect, it } from 'vitest'
import {
  LOCAL_DRAFT_RETENTION_MS,
  makeLocalTicketDraft,
  newestRecoverableDraft,
} from './draftRecovery'

describe('ticket draft recovery selection', () => {
  const local = makeLocalTicketDraft({
    staffId: '11111111-1111-4111-8111-111111111111',
    ticketNumber: 7101,
    channel: 'PUBLIC_REPLY',
    body: '이 브라우저 초안',
    attachmentIds: [],
    clientDeviceId: '22222222-2222-4222-8222-222222222222',
    baseTicketVersion: 7,
    now: new Date('2026-08-18T00:00:00Z'),
  })

  it('uses a seven-day local expiry and never returns an expired local draft', () => {
    expect(Date.parse(local.expiresAt) - Date.parse(local.updatedAt)).toBe(
      LOCAL_DRAFT_RETENTION_MS,
    )
    expect(
      newestRecoverableDraft(local, null, Date.parse(local.expiresAt)),
    ).toBeNull()
  })

  it('chooses the newer recoverable copy without merging separate devices silently', () => {
    const remote = {
      ticketNumber: 7101,
      channel: 'PUBLIC_REPLY' as const,
      body: '서버 초안',
      attachmentIds: [],
      clientDeviceId: '33333333-3333-4333-8333-333333333333',
      baseTicketVersion: 7,
      draftVersion: 3,
      updatedAt: '2026-08-18T00:00:01Z',
      expiresAt: '2026-09-17T00:00:01Z',
    }

    expect(newestRecoverableDraft(local, remote)).toBe(remote)
  })
})
