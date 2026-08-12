import type {
  AgentTicketStatus,
  AgentTicketSummary,
  TicketFieldName,
  TicketPriority,
  TicketVisibility,
  UpdateTicketCommand,
} from '../../api/types'

export interface EditableTicketFields {
  status: AgentTicketStatus
  priority: TicketPriority
  groupId: string | null
  assigneeId: string | null
}

export interface TicketCommentDrafts {
  PUBLIC: string
  INTERNAL: string
}

export interface TicketDraftSnapshot {
  mode: TicketVisibility
  comments: TicketCommentDrafts
  fields: EditableTicketFields
  serverFields: EditableTicketFields
  baseVersion: number
  pendingCommandId?: string
}

interface StoredTicketDraft extends TicketDraftSnapshot {
  formatVersion: 1
  savedAt: string
}

export interface StorageAdapter {
  getItem(key: string): string | null
  setItem(key: string, value: string): unknown
  removeItem(key: string): unknown
}

export interface EnumerableStorageAdapter extends StorageAdapter {
  readonly length: number
  key(index: number): string | null
}

export const TICKET_DRAFT_TTL_MS = 12 * 60 * 60 * 1000
export const STAFF_DRAFT_SESSION_OWNER_KEY =
  'deskseed:staff-session:last-authenticated-staff:v1'

const TICKET_DRAFT_KEY_PREFIX = 'deskseed:draft:ticket:'
const TICKET_DRAFT_KEY_SUFFIX = ':v1'

const FIELD_ORDER: TicketFieldName[] = [
  'status',
  'priority',
  'groupId',
  'assigneeId',
]

export function createEditableTicketFields(
  ticket: Pick<
    AgentTicketSummary,
    'status' | 'priority' | 'group' | 'assignee'
  >,
): EditableTicketFields {
  return {
    status: ticket.status,
    priority: ticket.priority,
    groupId: ticket.group?.id ?? null,
    assigneeId: ticket.assignee?.id ?? null,
  }
}

export function changedTicketFields(
  serverFields: EditableTicketFields,
  localFields: EditableTicketFields,
): TicketFieldName[] {
  return FIELD_ORDER.filter(
    (field) => serverFields[field] !== localFields[field],
  )
}

export function buildUpdateTicketCommand({
  expectedVersion,
  serverFields,
  localFields,
  comment,
  clientCommandId,
}: {
  expectedVersion: number
  serverFields: EditableTicketFields
  localFields: EditableTicketFields
  comment: { visibility: TicketVisibility; body: string }
  clientCommandId: string
}): UpdateTicketCommand {
  const changedFields = changedTicketFields(serverFields, localFields)
  const trimmedComment = comment.body.trim()
  const command: UpdateTicketCommand = {
    expectedVersion,
    changedFields,
    comment: trimmedComment
      ? { visibility: comment.visibility, body: trimmedComment }
      : null,
    clientCommandId,
  }
  if (changedFields.includes('status')) command.status = localFields.status
  if (changedFields.includes('priority'))
    command.priority = localFields.priority
  if (changedFields.includes('groupId')) command.groupId = localFields.groupId
  if (changedFields.includes('assigneeId')) {
    command.assigneeId = localFields.assigneeId
  }
  return command
}

export function mergeLatestFields({
  localFields,
  dirtyFields,
  latestFields,
}: {
  localFields: EditableTicketFields
  dirtyFields: Set<TicketFieldName>
  latestFields: EditableTicketFields
}): EditableTicketFields {
  return Object.fromEntries(
    FIELD_ORDER.map((field) => [
      field,
      dirtyFields.has(field) ? localFields[field] : latestFields[field],
    ]),
  ) as unknown as EditableTicketFields
}

export function reconcileLatestFields({
  confirmedFields,
  localFields,
  latestFields,
  knownConflictFields = new Set(),
}: {
  confirmedFields: EditableTicketFields
  localFields: EditableTicketFields
  latestFields: EditableTicketFields
  knownConflictFields?: Set<TicketFieldName>
}) {
  const dirtyFields = new Set(changedTicketFields(confirmedFields, localFields))
  const serverChangedFields = new Set(
    changedTicketFields(confirmedFields, latestFields),
  )
  const conflictingFields = new Set(knownConflictFields)
  for (const field of dirtyFields) {
    if (
      serverChangedFields.has(field) &&
      localFields[field] !== latestFields[field]
    ) {
      conflictingFields.add(field)
    }
  }
  return {
    localFields: mergeLatestFields({
      localFields,
      dirtyFields,
      latestFields,
    }),
    conflictingFields,
  }
}

export function resolveConflictField({
  field,
  choice,
  localFields,
  latestFields,
  dirtyFields,
  unresolvedFields,
}: {
  field: TicketFieldName
  choice: 'SERVER' | 'LOCAL'
  localFields: EditableTicketFields
  latestFields: EditableTicketFields
  dirtyFields: Set<TicketFieldName>
  unresolvedFields: Set<TicketFieldName>
}) {
  const nextLocal = { ...localFields }
  const nextDirty = new Set(dirtyFields)
  const nextUnresolved = new Set(unresolvedFields)
  if (choice === 'SERVER') {
    assignField(nextLocal, field, latestFields[field])
    nextDirty.delete(field)
  } else {
    nextDirty.add(field)
  }
  nextUnresolved.delete(field)
  return {
    localFields: nextLocal,
    dirtyFields: nextDirty,
    unresolvedFields: nextUnresolved,
  }
}

function assignField(
  fields: EditableTicketFields,
  field: TicketFieldName,
  value: EditableTicketFields[TicketFieldName],
) {
  if (field === 'status') fields.status = value as AgentTicketStatus
  else if (field === 'priority') fields.priority = value as TicketPriority
  else if (field === 'groupId') fields.groupId = value as string | null
  else fields.assigneeId = value as string | null
}

export function ticketDraftStorageKey(staffId: string, ticketNumber: number) {
  return `${TICKET_DRAFT_KEY_PREFIX}${ticketNumber}:${staffId}${TICKET_DRAFT_KEY_SUFFIX}`
}

export function writeTicketDraft(
  storage: StorageAdapter,
  key: string,
  snapshot: Omit<TicketDraftSnapshot, 'serverFields'> & {
    serverFields?: EditableTicketFields
  },
  now = Date.now(),
) {
  try {
    if (!isTicketDraftWriteAuthorized(storage, key)) {
      return removeTicketDraft(storage, key)
    }
    const stored: StoredTicketDraft = {
      ...snapshot,
      serverFields: snapshot.serverFields ?? snapshot.fields,
      formatVersion: 1,
      savedAt: new Date(now).toISOString(),
    }
    storage.setItem(key, JSON.stringify(stored))
    return true
  } catch {
    return false
  }
}

export function readTicketDraft(
  storage: StorageAdapter,
  key: string,
  now = Date.now(),
): StoredTicketDraft | null {
  let raw: string | null
  try {
    raw = storage.getItem(key)
  } catch {
    return null
  }
  if (raw === null) return null
  try {
    if (!isTicketDraftWriteAuthorized(storage, key)) {
      return removeInvalidTicketDraft(storage, key)
    }
  } catch {
    return null
  }
  try {
    const value = JSON.parse(raw) as Partial<StoredTicketDraft>
    const savedAt = Date.parse(value.savedAt ?? '')
    if (
      value.formatVersion !== 1 ||
      (value.mode !== 'PUBLIC' && value.mode !== 'INTERNAL') ||
      !isCommentDrafts(value.comments) ||
      !isEditableFields(value.fields) ||
      !isEditableFields(value.serverFields) ||
      typeof value.baseVersion !== 'number' ||
      !Number.isSafeInteger(value.baseVersion) ||
      typeof value.savedAt !== 'string' ||
      !Number.isFinite(savedAt) ||
      savedAt > now ||
      now - savedAt > TICKET_DRAFT_TTL_MS ||
      (value.pendingCommandId !== undefined &&
        !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
          value.pendingCommandId,
        ))
    ) {
      return removeInvalidTicketDraft(storage, key)
    }
    return value as StoredTicketDraft
  } catch {
    return removeInvalidTicketDraft(storage, key)
  }
}

export function purgeStaffTicketDrafts(
  storage: EnumerableStorageAdapter,
  staffId: string,
) {
  const matchingKeys: string[] = []
  try {
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index)
      if (key && isTicketDraftKeyForStaff(key, staffId)) matchingKeys.push(key)
    }
  } catch {
    return
  }
  for (const key of matchingKeys) removeTicketDraft(storage, key)
}

export function sweepStaffTicketDrafts(
  storage: EnumerableStorageAdapter,
  staffId: string,
  now = Date.now(),
) {
  const matchingKeys: string[] = []
  try {
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index)
      if (key && isTicketDraftKeyForStaff(key, staffId)) matchingKeys.push(key)
    }
  } catch {
    return
  }
  for (const key of matchingKeys) readTicketDraft(storage, key, now)
}

export function removeTicketDraft(storage: StorageAdapter, key: string) {
  try {
    storage.removeItem(key)
    return true
  } catch {
    return false
  }
}

export function clearSubmittedDraft(
  storage: StorageAdapter,
  key: string,
  visibility: TicketVisibility,
) {
  const current = readTicketDraft(storage, key)
  if (!current) return
  writeTicketDraft(storage, key, {
    ...current,
    comments: { ...current.comments, [visibility]: '' },
  })
}

export function clearPendingTicketCommand(
  storage: StorageAdapter,
  key: string,
) {
  const current = readTicketDraft(storage, key)
  if (!current?.pendingCommandId) return
  const withoutPendingCommand = { ...current }
  delete withoutPendingCommand.pendingCommandId
  writeTicketDraft(storage, key, withoutPendingCommand)
}

function isCommentDrafts(value: unknown): value is TicketCommentDrafts {
  if (!value || typeof value !== 'object') return false
  const comments = value as Record<string, unknown>
  return (
    typeof comments.PUBLIC === 'string' && typeof comments.INTERNAL === 'string'
  )
}

function removeInvalidTicketDraft(storage: StorageAdapter, key: string) {
  removeTicketDraft(storage, key)
  return null
}

function isTicketDraftKeyForStaff(key: string, staffId: string) {
  return ticketDraftStaffId(key) === staffId
}

function isTicketDraftWriteAuthorized(storage: StorageAdapter, key: string) {
  const staffId = ticketDraftStaffId(key)
  return (
    staffId === null ||
    storage.getItem(STAFF_DRAFT_SESSION_OWNER_KEY) === staffId
  )
}

function ticketDraftStaffId(key: string) {
  if (
    !key.startsWith(TICKET_DRAFT_KEY_PREFIX) ||
    !key.endsWith(TICKET_DRAFT_KEY_SUFFIX)
  ) {
    return null
  }
  const identity = key.slice(
    TICKET_DRAFT_KEY_PREFIX.length,
    -TICKET_DRAFT_KEY_SUFFIX.length,
  )
  const separator = identity.indexOf(':')
  if (separator <= 0) return null
  const ticketNumber = identity.slice(0, separator)
  const keyStaffId = identity.slice(separator + 1)
  if (!/^\d+$/.test(ticketNumber) || keyStaffId.length === 0) return null
  return keyStaffId
}

function isEditableFields(value: unknown): value is EditableTicketFields {
  if (!value || typeof value !== 'object') return false
  const fields = value as Record<string, unknown>
  return (
    ['NEW', 'OPEN', 'PENDING', 'ON_HOLD', 'SOLVED', 'CLOSED'].includes(
      String(fields.status),
    ) &&
    ['LOW', 'NORMAL', 'HIGH', 'URGENT'].includes(String(fields.priority)) &&
    (fields.groupId === null || typeof fields.groupId === 'string') &&
    (fields.assigneeId === null || typeof fields.assigneeId === 'string')
  )
}
