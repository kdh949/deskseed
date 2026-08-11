import { describe, expect, it } from 'vitest'
import {
  buildUpdateTicketCommand,
  clearSubmittedDraft,
  createEditableTicketFields,
  mergeLatestFields,
  readTicketDraft,
  reconcileLatestFields,
  resolveConflictField,
  ticketDraftStorageKey,
  writeTicketDraft,
} from './ticketEditorModel'

const serverFields = {
  status: 'OPEN' as const,
  priority: 'NORMAL' as const,
  groupId: '11111111-1111-4111-8111-111111111111',
  assigneeId: '22222222-2222-4222-8222-222222222222',
}

describe('ticket editor model', () => {
  it('builds an exact canonical changed field set with the active comment', () => {
    const command = buildUpdateTicketCommand({
      expectedVersion: 7,
      serverFields,
      localFields: {
        ...serverFields,
        status: 'PENDING',
        groupId: '33333333-3333-4333-8333-333333333333',
        assigneeId: null,
      },
      comment: { visibility: 'INTERNAL', body: ' 결제팀 확인 요청 ' },
      clientCommandId: '44444444-4444-4444-8444-444444444444',
    })

    expect(command).toEqual({
      expectedVersion: 7,
      changedFields: ['status', 'groupId', 'assigneeId'],
      status: 'PENDING',
      groupId: '33333333-3333-4333-8333-333333333333',
      assigneeId: null,
      comment: { visibility: 'INTERNAL', body: '결제팀 확인 요청' },
      clientCommandId: '44444444-4444-4444-8444-444444444444',
    })
  })

  it('merges refreshed server values only into clean fields', () => {
    expect(
      mergeLatestFields({
        localFields: { ...serverFields, priority: 'HIGH' },
        dirtyFields: new Set(['priority']),
        latestFields: {
          ...serverFields,
          status: 'PENDING',
          priority: 'URGENT',
        },
      }),
    ).toEqual({ ...serverFields, status: 'PENDING', priority: 'HIGH' })
  })

  it('adds every dirty field changed by the server to the explicit conflict set', () => {
    const reconciled = reconcileLatestFields({
      confirmedFields: serverFields,
      localFields: {
        ...serverFields,
        status: 'PENDING',
        priority: 'HIGH',
      },
      latestFields: {
        ...serverFields,
        status: 'SOLVED',
        priority: 'URGENT',
      },
      knownConflictFields: new Set(['status']),
    })

    expect(reconciled.localFields).toEqual({
      ...serverFields,
      status: 'PENDING',
      priority: 'HIGH',
    })
    expect(reconciled.conflictingFields).toEqual(
      new Set(['status', 'priority']),
    )
  })

  it('detects a dirty same-field server change during a manual refresh', () => {
    const reconciled = reconcileLatestFields({
      confirmedFields: serverFields,
      localFields: { ...serverFields, status: 'PENDING' },
      latestFields: { ...serverFields, status: 'SOLVED' },
    })

    expect(reconciled.localFields.status).toBe('PENDING')
    expect(reconciled.conflictingFields).toEqual(new Set(['status']))
  })

  it('requires an explicit server or local choice for a conflicting field', () => {
    const keepLocal = resolveConflictField({
      field: 'priority',
      choice: 'LOCAL',
      localFields: { ...serverFields, priority: 'HIGH' },
      latestFields: { ...serverFields, priority: 'URGENT' },
      dirtyFields: new Set(['priority']),
      unresolvedFields: new Set(['priority']),
    })
    expect(keepLocal.localFields.priority).toBe('HIGH')
    expect(keepLocal.dirtyFields).toEqual(new Set(['priority']))
    expect(keepLocal.unresolvedFields).toEqual(new Set())

    const useServer = resolveConflictField({
      field: 'priority',
      choice: 'SERVER',
      localFields: { ...serverFields, priority: 'HIGH' },
      latestFields: { ...serverFields, priority: 'URGENT' },
      dirtyFields: new Set(['priority']),
      unresolvedFields: new Set(['priority']),
    })
    expect(useServer.localFields.priority).toBe('URGENT')
    expect(useServer.dirtyFields).toEqual(new Set())
    expect(useServer.unresolvedFields).toEqual(new Set())
  })

  it('keeps PUBLIC and INTERNAL drafts separate per staff and ticket and clears only the submitted mode', () => {
    const storage = new Map<string, string>()
    const adapter = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    }
    const firstKey = ticketDraftStorageKey('staff-1', 1042)
    const secondKey = ticketDraftStorageKey('staff-1', 1043)
    expect(firstKey).not.toBe(secondKey)

    writeTicketDraft(adapter, firstKey, {
      mode: 'INTERNAL',
      comments: { PUBLIC: '고객 답변', INTERNAL: '팀 메모' },
      fields: createEditableTicketFields({
        status: 'OPEN',
        priority: 'NORMAL',
        group: null,
        assignee: null,
      }),
      baseVersion: 7,
    })
    writeTicketDraft(adapter, secondKey, {
      mode: 'PUBLIC',
      comments: { PUBLIC: '다른 티켓', INTERNAL: '' },
      fields: serverFields,
      baseVersion: 3,
    })

    const restored = readTicketDraft(adapter, firstKey)
    expect(restored?.mode).toBe('INTERNAL')
    expect(restored?.comments).toEqual({
      PUBLIC: '고객 답변',
      INTERNAL: '팀 메모',
    })

    clearSubmittedDraft(adapter, firstKey, 'PUBLIC')
    expect(readTicketDraft(adapter, firstKey)?.comments).toEqual({
      PUBLIC: '',
      INTERNAL: '팀 메모',
    })
    expect(readTicketDraft(adapter, secondKey)?.comments.PUBLIC).toBe(
      '다른 티켓',
    )
  })
})
