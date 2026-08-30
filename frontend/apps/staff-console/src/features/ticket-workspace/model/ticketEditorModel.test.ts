import { describe, expect, it } from 'vitest'
import type { RichTextDocumentV1 } from '../../../api/types'
import {
  STAFF_DRAFT_SESSION_OWNER_KEY,
  buildUpdateTicketCommand,
  clearSubmittedDraft,
  createEditableTicketFields,
  mergeLatestFields,
  purgeStaffTicketDrafts,
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

  it('links only the CLEAN attachment handles supplied by the composer', () => {
    const command = buildUpdateTicketCommand({
      expectedVersion: 7,
      serverFields,
      localFields: serverFields,
      comment: { visibility: 'PUBLIC', body: '첨부 파일을 확인해 주세요.' },
      attachmentIds: ['55555555-5555-4555-8555-555555555555'],
      clientCommandId: '44444444-4444-4444-8444-444444444444',
    })

    expect(command.comment).toEqual({
      visibility: 'PUBLIC',
      body: '첨부 파일을 확인해 주세요.',
      attachmentIds: ['55555555-5555-4555-8555-555555555555'],
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
    storage.set(STAFF_DRAFT_SESSION_OWNER_KEY, 'staff-1')
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

  it('restores a server-valid rich draft with more than 500 total nodes', () => {
    const storage = new Map<string, string>()
    const adapter = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    }
    storage.set(STAFF_DRAFT_SESSION_OWNER_KEY, 'staff-1')
    const key = ticketDraftStorageKey('staff-1', 1042)
    const document: RichTextDocumentV1 = {
      type: 'doc',
      content: Array.from({ length: 251 }, (_, index) => ({
        type: 'paragraph',
        content: [{ type: 'text', text: `line ${index}` }],
      })),
    }

    writeTicketDraft(adapter, key, {
      mode: 'PUBLIC',
      comments: { PUBLIC: 'large valid draft', INTERNAL: '' },
      documents: {
        PUBLIC: document,
        INTERNAL: { type: 'doc', content: [{ type: 'paragraph' }] },
      },
      fields: serverFields,
      baseVersion: 7,
    })

    expect(readTicketDraft(adapter, key)?.documents?.PUBLIC).toEqual(document)
  })

  it('persists the complete ambiguous command including attachment IDs', () => {
    const storage = new Map<string, string>()
    const adapter = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    }
    storage.set(STAFF_DRAFT_SESSION_OWNER_KEY, 'staff-1')
    const key = ticketDraftStorageKey('staff-1', 1042)
    const pendingCommand = buildUpdateTicketCommand({
      expectedVersion: 7,
      serverFields,
      localFields: serverFields,
      comment: { visibility: 'PUBLIC', body: '첨부 확인' },
      attachmentIds: ['11111111-1111-4111-8111-111111111111'],
      clientCommandId: '44444444-4444-4444-8444-444444444444',
    })

    writeTicketDraft(adapter, key, {
      mode: 'PUBLIC',
      comments: { PUBLIC: '첨부 확인', INTERNAL: '' },
      fields: serverFields,
      baseVersion: 7,
      attachmentIds: {
        PUBLIC: ['11111111-1111-4111-8111-111111111111'],
      },
      pendingCommandId: pendingCommand.clientCommandId,
      pendingCommand,
    })

    expect(readTicketDraft(adapter, key)?.pendingCommand).toEqual(
      pendingCommand,
    )
    expect(readTicketDraft(adapter, key)?.attachmentIds?.PUBLIC).toEqual([
      '11111111-1111-4111-8111-111111111111',
    ])
  })

  it('restores a draft only within the 12-hour active-session recovery window', () => {
    const savedAt = Date.parse('2026-08-12T00:00:00.000Z')
    const storage = new Map<string, string>()
    const adapter = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    }
    storage.set(STAFF_DRAFT_SESSION_OWNER_KEY, 'staff-1')
    const key = ticketDraftStorageKey('staff-1', 1042)

    writeTicketDraft(
      adapter,
      key,
      {
        mode: 'INTERNAL',
        comments: { PUBLIC: '고객 답변', INTERNAL: '팀 메모' },
        fields: serverFields,
        baseVersion: 7,
      },
      savedAt,
    )

    expect(JSON.parse(storage.get(key) ?? '{}').savedAt).toBe(
      '2026-08-12T00:00:00.000Z',
    )
    expect(
      readTicketDraft(adapter, key, Date.parse('2026-08-12T11:59:59.999Z'))
        ?.comments.INTERNAL,
    ).toBe('팀 메모')

    expect(
      readTicketDraft(adapter, key, Date.parse('2026-08-12T12:00:00.001Z')),
    ).toBeNull()
    expect(storage.has(key)).toBe(false)
  })

  it('cannot recreate a departing staff draft after the session owner marker is cleared or changed', () => {
    const storage = new Map<string, string>()
    const adapter = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    }
    const key = ticketDraftStorageKey('staff-1', 1042)
    const snapshot = {
      mode: 'INTERNAL' as const,
      comments: { PUBLIC: '', INTERNAL: '세션 종료 직전 메모' },
      fields: serverFields,
      baseVersion: 7,
    }

    storage.set(STAFF_DRAFT_SESSION_OWNER_KEY, 'staff-1')
    writeTicketDraft(adapter, key, snapshot)
    expect(storage.has(key)).toBe(true)

    storage.delete(STAFF_DRAFT_SESSION_OWNER_KEY)
    writeTicketDraft(adapter, key, snapshot)
    expect(storage.has(key)).toBe(false)

    storage.set(STAFF_DRAFT_SESSION_OWNER_KEY, 'staff-2')
    writeTicketDraft(adapter, key, snapshot)
    expect(storage.has(key)).toBe(false)
  })

  it.each([
    ['invalid JSON', '{'],
    [
      'invalid timestamp',
      JSON.stringify({
        formatVersion: 1,
        savedAt: 'not-a-timestamp',
        mode: 'PUBLIC',
        comments: { PUBLIC: '고객 답변', INTERNAL: '' },
        fields: serverFields,
        serverFields,
        baseVersion: 7,
      }),
    ],
    [
      'future timestamp',
      JSON.stringify({
        formatVersion: 1,
        savedAt: '2026-08-12T00:00:00.001Z',
        mode: 'PUBLIC',
        comments: { PUBLIC: '고객 답변', INTERNAL: '' },
        fields: serverFields,
        serverFields,
        baseVersion: 7,
      }),
    ],
  ])('deletes a malformed stored draft with %s', (_label, raw) => {
    const storage = new Map([['draft-key', raw]])
    const adapter = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    }

    expect(
      readTicketDraft(
        adapter,
        'draft-key',
        Date.parse('2026-08-12T00:00:00.000Z'),
      ),
    ).toBeNull()
    expect(storage.has('draft-key')).toBe(false)
  })

  it('purges only ticket draft keys that belong to the exact staff namespace', () => {
    const firstStaffDraft = ticketDraftStorageKey('staff-1', 1042)
    const secondStaffDraft = ticketDraftStorageKey('staff-1', 1043)
    const otherStaffDraft = ticketDraftStorageKey('staff-10', 1042)
    const preferenceKey = 'deskseed:agent:staff-1:workspace-panels:v1'
    const malformedDraftKey = 'deskseed:draft:ticket:not-a-number:staff-1:v1'
    const unrelatedKey = 'unrelated-application-key'
    const storage = new Map([
      [firstStaffDraft, 'first'],
      [secondStaffDraft, 'second'],
      [otherStaffDraft, 'other staff'],
      [preferenceKey, 'preference'],
      [malformedDraftKey, 'malformed'],
      [unrelatedKey, 'unrelated'],
    ])
    const adapter = {
      get length() {
        return storage.size
      },
      key: (index: number) => [...storage.keys()][index] ?? null,
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    }

    purgeStaffTicketDrafts(adapter, 'staff-1')

    expect([...storage.keys()]).toEqual([
      otherStaffDraft,
      preferenceKey,
      malformedDraftKey,
      unrelatedKey,
    ])
  })

  it('treats unavailable draft storage as disabled recovery', () => {
    const key = ticketDraftStorageKey('staff-1', 1042)
    const unavailableRead = {
      getItem: () => {
        throw new DOMException('Storage access denied', 'SecurityError')
      },
      setItem: () => undefined,
      removeItem: () => undefined,
    }
    expect(() => readTicketDraft(unavailableRead, key)).not.toThrow()
    expect(readTicketDraft(unavailableRead, key)).toBeNull()

    const unavailableWrite = {
      getItem: (storageKey: string) =>
        storageKey === STAFF_DRAFT_SESSION_OWNER_KEY ? 'staff-1' : null,
      setItem: () => {
        throw new DOMException('Storage quota exceeded', 'QuotaExceededError')
      },
      removeItem: () => undefined,
    }
    expect(() =>
      writeTicketDraft(unavailableWrite, key, {
        mode: 'INTERNAL',
        comments: { PUBLIC: '', INTERNAL: '브라우저 메모' },
        fields: serverFields,
        baseVersion: 7,
      }),
    ).not.toThrow()
  })

  it('does not fail the caller when an invalid draft cannot be deleted', () => {
    const unavailableDelete = {
      getItem: () => '{',
      setItem: () => undefined,
      removeItem: () => {
        throw new DOMException('Storage access denied', 'SecurityError')
      },
    }

    expect(() => readTicketDraft(unavailableDelete, 'draft-key')).not.toThrow()
    expect(readTicketDraft(unavailableDelete, 'draft-key')).toBeNull()
  })
})
