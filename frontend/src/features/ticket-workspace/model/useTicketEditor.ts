import { useEffect, useMemo, useRef, useState } from 'react'
import { useBeforeUnload, useBlocker } from 'react-router'
import { ApiError, updateAgentTicket } from '../../../api/client'
import type {
  AgentTicketDetail,
  TicketFieldName,
  TicketCommandWarning,
  TicketVisibility,
} from '../../../api/types'
import { createOpaqueUuid } from '../../../api/uuid'
import {
  buildUpdateTicketCommand,
  changedTicketFields,
  clearPendingTicketCommand,
  createEditableTicketFields,
  readTicketDraft,
  reconcileLatestFields,
  removeTicketDraft,
  resolveConflictField,
  ticketDraftStorageKey,
  writeTicketDraft,
  type EditableTicketFields,
  type TicketCommentDrafts,
} from './ticketEditorModel'

export interface TicketConflictState {
  fields: Set<TicketFieldName>
  currentVersion: number
  latestFields: EditableTicketFields | null
  requestId?: string
  loadingLatest: boolean
  latestError?: string
}

interface TicketEditorError {
  message: string
  requestId?: string
  saved?: boolean
}

export function useTicketEditor({
  detail,
  staffId,
  refreshLatest,
}: {
  detail: AgentTicketDetail
  staffId: string
  refreshLatest: () => Promise<AgentTicketDetail>
}) {
  const initial = useMemo(
    () => initialEditorState(detail, staffId),
    [detail.ticket.ticketNumber, staffId],
  )
  const [mode, setMode] = useState<TicketVisibility>(initial.mode)
  const [comments, setComments] = useState<TicketCommentDrafts>(
    initial.comments,
  )
  const [serverFields, setServerFields] = useState(initial.serverFields)
  const [localFields, setLocalFields] = useState(initial.fields)
  const [baseVersion, setBaseVersion] = useState(initial.baseVersion)
  const [submitting, setSubmitting] = useState(false)
  const [conflict, setConflict] = useState<TicketConflictState | null>(null)
  const [error, setError] = useState<TicketEditorError | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [warnings, setWarnings] = useState<TicketCommandWarning[]>([])
  const [pendingCommandId, setPendingCommandId] = useState<string | null>(
    initial.pendingCommandId ?? null,
  )
  const conflictRef = useRef<HTMLDivElement>(null)
  const storageKey = ticketDraftStorageKey(staffId, detail.ticket.ticketNumber)
  const dirtyFields = useMemo(
    () => new Set(changedTicketFields(serverFields, localFields)),
    [localFields, serverFields],
  )
  const hasComment =
    comments.PUBLIC.trim() !== '' || comments.INTERNAL.trim() !== ''
  const isUnsaved = dirtyFields.size > 0 || hasComment
  const blocker = useBlocker(isUnsaved || submitting)
  const unresolvedConflict = (conflict?.fields.size ?? 0) > 0
  const hasActiveSubmit =
    dirtyFields.size > 0 || comments[mode].trim().length > 0

  useBeforeUnload((event) => {
    if (!isUnsaved && !submitting) return
    event.preventDefault()
    event.returnValue = ''
  })

  useEffect(() => {
    if (!isUnsaved) {
      removeTicketDraft(localStorage, storageKey)
      return
    }
    writeTicketDraft(localStorage, storageKey, {
      mode,
      comments,
      fields: localFields,
      serverFields,
      baseVersion,
      ...(pendingCommandId ? { pendingCommandId } : {}),
    })
  }, [
    baseVersion,
    comments,
    isUnsaved,
    localFields,
    mode,
    pendingCommandId,
    serverFields,
    storageKey,
  ])

  useEffect(() => {
    if (!conflict) return
    conflictRef.current?.focus()
  }, [conflict?.currentVersion])

  const updateDraft = (visibility: TicketVisibility, value: string) => {
    if (submitting) return
    const nextComments = { ...comments, [visibility]: value }
    invalidatePendingCommand({ comments: nextComments })
    setComments(nextComments)
    setError(null)
    setSuccess(null)
  }

  const updateField = (
    field: TicketFieldName,
    value: EditableTicketFields[TicketFieldName],
  ) => {
    if (submitting) return
    const nextFields = { ...localFields }
    assignEditableField(nextFields, field, value)
    if (field === 'groupId') {
      const group = detail.assignmentOptions.groups.find(
        (option) => option.id === value,
      )
      const assigneeStillValid = group?.members.some(
        (member) => member.id === nextFields.assigneeId,
      )
      if (!assigneeStillValid) nextFields.assigneeId = null
    }
    invalidatePendingCommand({ fields: nextFields })
    setLocalFields(nextFields)
    setError(null)
    setSuccess(null)
  }

  const loadLatestForConflict = async (
    knownConflictFields?: Set<TicketFieldName>,
  ) => {
    setConflict((current) =>
      current
        ? { ...current, loadingLatest: true, latestError: undefined }
        : current,
    )
    try {
      const latest = await refreshLatest()
      const latestFields = createEditableTicketFields(latest.ticket)
      const reconciled = reconcileLatestFields({
        confirmedFields: serverFields,
        localFields,
        latestFields,
        knownConflictFields,
      })
      setLocalFields(reconciled.localFields)
      setServerFields(latestFields)
      setBaseVersion(latest.ticket.version)
      setConflict((current) =>
        current
          ? {
              ...current,
              fields: new Set([
                ...current.fields,
                ...reconciled.conflictingFields,
              ]),
              currentVersion: latest.ticket.version,
              latestFields,
              loadingLatest: false,
              latestError: undefined,
            }
          : current,
      )
      return latest
    } catch (cause) {
      const apiError = cause instanceof ApiError ? cause : null
      setConflict((current) =>
        current
          ? {
              ...current,
              loadingLatest: false,
              latestError: '최신 티켓을 읽지 못했습니다. 다시 확인해 주세요.',
              requestId: current.requestId ?? apiError?.requestId,
            }
          : current,
      )
      return null
    }
  }

  const resolveField = (field: TicketFieldName, choice: 'SERVER' | 'LOCAL') => {
    if (!conflict?.latestFields) return
    const resolved = resolveConflictField({
      field,
      choice,
      localFields,
      latestFields: conflict.latestFields,
      dirtyFields,
      unresolvedFields: conflict.fields,
    })
    invalidatePendingCommand({ fields: resolved.localFields })
    setLocalFields(resolved.localFields)
    setConflict(
      resolved.unresolvedFields.size === 0
        ? null
        : { ...conflict, fields: resolved.unresolvedFields },
    )
  }

  const refreshEditor = async () => {
    try {
      const latest = await refreshLatest()
      if (pendingCommandId) {
        setError({
          message:
            '이전 저장 결과가 아직 확정되지 않았습니다. 같은 변경사항을 다시 저장해 확인해 주세요.',
        })
        setSuccess(null)
        return latest
      }
      const latestFields = createEditableTicketFields(latest.ticket)
      const reconciled = reconcileLatestFields({
        confirmedFields: serverFields,
        localFields,
        latestFields,
        knownConflictFields: conflict?.fields,
      })
      setLocalFields(reconciled.localFields)
      setServerFields(latestFields)
      setBaseVersion(latest.ticket.version)
      setConflict((current) => {
        const fields = new Set([
          ...(current?.fields ?? []),
          ...reconciled.conflictingFields,
        ])
        if (fields.size === 0) return null
        return {
          fields,
          currentVersion: latest.ticket.version,
          latestFields,
          requestId: current?.requestId,
          loadingLatest: false,
        }
      })
      setError(null)
      setSuccess(
        reconciled.conflictingFields.size === 0
          ? '최신 티켓 정보를 확인했습니다.'
          : null,
      )
      return latest
    } catch (cause) {
      const apiError = cause instanceof ApiError ? cause : null
      setError({
        message: '최신 티켓 정보를 확인하지 못했습니다. 입력은 보존되었습니다.',
        requestId: apiError?.requestId,
      })
      return null
    }
  }

  const submit = async () => {
    if (submitting || unresolvedConflict || !hasActiveSubmit) return
    setSubmitting(true)
    setError(null)
    setSuccess(null)
    setWarnings([])
    const submittedMode = mode
    const submittedComment = comments[submittedMode].trim().length > 0
    const clientCommandId = pendingCommandId ?? createOpaqueUuid()
    if (pendingCommandId === null) {
      setPendingCommandId(clientCommandId)
      writeTicketDraft(localStorage, storageKey, {
        mode,
        comments,
        fields: localFields,
        serverFields,
        baseVersion,
        pendingCommandId: clientCommandId,
      })
    }
    const command = buildUpdateTicketCommand({
      expectedVersion: baseVersion,
      serverFields,
      localFields,
      comment: { visibility: submittedMode, body: comments[submittedMode] },
      clientCommandId,
    })
    try {
      const result = await updateAgentTicket(
        detail.ticket.ticketNumber,
        command,
      )
      const confirmedComments = { ...comments, [submittedMode]: '' }
      writeTicketDraft(localStorage, storageKey, {
        mode,
        comments: confirmedComments,
        fields: localFields,
        serverFields: localFields,
        baseVersion: result.version,
      })
      setPendingCommandId(null)
      setWarnings(result.warnings)
      setComments(confirmedComments)
      setServerFields(localFields)
      setLocalFields(localFields)
      setBaseVersion(result.version)
      try {
        const latest = await refreshLatest()
        const latestFields = createEditableTicketFields(latest.ticket)
        setServerFields(latestFields)
        setLocalFields(latestFields)
        setBaseVersion(latest.ticket.version)
        setConflict(null)
        setSuccess(
          !submittedComment
            ? '변경사항을 저장했습니다.'
            : submittedMode === 'PUBLIC'
              ? '공개 답변과 변경사항을 저장했습니다.'
              : '내부 메모와 변경사항을 저장했습니다.',
        )
      } catch (cause) {
        const apiError = cause instanceof ApiError ? cause : null
        setServerFields(localFields)
        setBaseVersion(result.version)
        setError({
          saved: true,
          message:
            '저장은 완료됐지만 최신 티켓을 확인하지 못했습니다. 새로고침해 주세요.',
          requestId: apiError?.requestId,
        })
      }
    } catch (cause) {
      const apiError = cause instanceof ApiError ? cause : null
      if (
        apiError?.status === 409 &&
        apiError.problem?.conflictingFields?.length &&
        typeof apiError.problem.currentVersion === 'number'
      ) {
        invalidatePendingCommand()
        setConflict({
          fields: new Set(apiError.problem.conflictingFields),
          currentVersion: apiError.problem.currentVersion,
          latestFields: null,
          requestId: apiError.requestId,
          loadingLatest: true,
        })
        await loadLatestForConflict(new Set(apiError.problem.conflictingFields))
      } else {
        const ambiguous = isAmbiguousCommandFailure(cause)
        if (!ambiguous) invalidatePendingCommand()
        setError({
          message: ambiguous
            ? '저장 결과를 확인할 수 없습니다. 같은 변경사항을 다시 저장해 중복 없이 확인해 주세요.'
            : (apiError?.message ??
              '변경사항을 저장하지 못했습니다. 입력은 그대로 보존되었습니다.'),
          requestId: apiError?.requestId,
        })
      }
    } finally {
      setSubmitting(false)
    }
  }

  return {
    mode,
    setMode: (nextMode: TicketVisibility) => {
      if (submitting) return
      if (nextMode !== mode) invalidatePendingCommand({ mode: nextMode })
      setMode(nextMode)
    },
    comments,
    updateDraft,
    serverFields,
    localFields,
    dirtyFields,
    updateField,
    submitting,
    conflict,
    conflictRef,
    resolveField,
    loadLatestForConflict,
    refreshEditor,
    submit,
    error,
    success,
    warnings,
    isUnsaved,
    blocker,
    canSubmit: hasActiveSubmit && !submitting && !unresolvedConflict,
  }

  function invalidatePendingCommand(
    next: {
      mode?: TicketVisibility
      comments?: TicketCommentDrafts
      fields?: EditableTicketFields
    } = {},
  ) {
    if (pendingCommandId) {
      writeTicketDraft(localStorage, storageKey, {
        mode: next.mode ?? mode,
        comments: next.comments ?? comments,
        fields: next.fields ?? localFields,
        serverFields,
        baseVersion,
      })
    } else {
      clearPendingTicketCommand(localStorage, storageKey)
    }
    setPendingCommandId(null)
  }
}

function initialEditorState(detail: AgentTicketDetail, staffId: string) {
  const freshFields = createEditableTicketFields(detail.ticket)
  const stored = readTicketDraft(
    localStorage,
    ticketDraftStorageKey(staffId, detail.ticket.ticketNumber),
  )
  if (!stored) {
    return {
      mode: detail.ticket.isChild ? ('INTERNAL' as const) : ('PUBLIC' as const),
      comments: { PUBLIC: '', INTERNAL: '' },
      fields: freshFields,
      serverFields: freshFields,
      baseVersion: detail.ticket.version,
      pendingCommandId: undefined,
    }
  }
  if (stored.pendingCommandId) {
    return detail.ticket.isChild
      ? {
          ...stored,
          mode: 'INTERNAL' as const,
          comments: { ...stored.comments, PUBLIC: '' },
        }
      : stored
  }
  const storedDirty = changedTicketFields(stored.serverFields, stored.fields)
  if (storedDirty.length === 0) {
    return {
      ...stored,
      mode: detail.ticket.isChild ? ('INTERNAL' as const) : stored.mode,
      comments: detail.ticket.isChild
        ? { ...stored.comments, PUBLIC: '' }
        : stored.comments,
      fields: freshFields,
      serverFields: freshFields,
      baseVersion: detail.ticket.version,
    }
  }
  return detail.ticket.isChild
    ? {
        ...stored,
        mode: 'INTERNAL' as const,
        comments: { ...stored.comments, PUBLIC: '' },
      }
    : stored
}

function isAmbiguousCommandFailure(cause: unknown) {
  if (!(cause instanceof ApiError)) return true
  return cause.status >= 500 || (cause.status >= 200 && cause.status < 300)
}

function assignEditableField(
  fields: EditableTicketFields,
  field: TicketFieldName,
  value: EditableTicketFields[TicketFieldName],
) {
  if (field === 'status')
    fields.status = value as EditableTicketFields['status']
  else if (field === 'priority') {
    fields.priority = value as EditableTicketFields['priority']
  } else if (field === 'groupId') fields.groupId = value as string | null
  else fields.assigneeId = value as string | null
}
