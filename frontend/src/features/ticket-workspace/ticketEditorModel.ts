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
  return `deskseed:draft:ticket:${ticketNumber}:${staffId}:v1`
}

export function writeTicketDraft(
  storage: StorageAdapter,
  key: string,
  snapshot: Omit<TicketDraftSnapshot, 'serverFields'> & {
    serverFields?: EditableTicketFields
  },
) {
  const stored: StoredTicketDraft = {
    ...snapshot,
    serverFields: snapshot.serverFields ?? snapshot.fields,
    formatVersion: 1,
    savedAt: new Date().toISOString(),
  }
  storage.setItem(key, JSON.stringify(stored))
}

export function readTicketDraft(
  storage: StorageAdapter,
  key: string,
): StoredTicketDraft | null {
  const raw = storage.getItem(key)
  if (!raw) return null
  try {
    const value = JSON.parse(raw) as Partial<StoredTicketDraft>
    if (
      value.formatVersion !== 1 ||
      (value.mode !== 'PUBLIC' && value.mode !== 'INTERNAL') ||
      !isCommentDrafts(value.comments) ||
      !isEditableFields(value.fields) ||
      !isEditableFields(value.serverFields) ||
      typeof value.baseVersion !== 'number' ||
      !Number.isSafeInteger(value.baseVersion) ||
      typeof value.savedAt !== 'string'
    ) {
      return null
    }
    return value as StoredTicketDraft
  } catch {
    return null
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

function isCommentDrafts(value: unknown): value is TicketCommentDrafts {
  if (!value || typeof value !== 'object') return false
  const comments = value as Record<string, unknown>
  return (
    typeof comments.PUBLIC === 'string' && typeof comments.INTERNAL === 'string'
  )
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
