import { useEffect, useMemo, useRef, useState } from 'react'
import { useBeforeUnload, useBlocker } from 'react-router'
import { ApiError, updateAgentTicket } from '../../../api/client'
import type {
  AgentTicketDetail,
  TicketFieldName,
  TicketCommandWarning,
  TicketVisibility,
  UpdateTicketCommand,
} from '../../../api/types'
import type { AttachmentDraftState } from '../../attachments/AttachmentUploadField'
import { createOpaqueUuid } from '../../../api/uuid'
import { useTicketDraftSync } from '../../collaboration/useTicketDraftSync'
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
  const [pendingCommand, setPendingCommand] =
    useState<UpdateTicketCommand | null>(initial.pendingCommand ?? null)
  const [attachmentStates, setAttachmentStates] = useState<
    Record<TicketVisibility, AttachmentDraftState>
  >(() =>
    initialAttachmentStates(initial.pendingCommand, initial.attachmentIds),
  )
  const composerDrafts = useMemo(
    () => ({
      PUBLIC: {
        body: comments.PUBLIC,
        attachmentIds: attachmentStates.PUBLIC.ids,
      },
      INTERNAL: {
        body: comments.INTERNAL,
        attachmentIds: attachmentStates.INTERNAL.ids,
      },
    }),
    [attachmentStates, comments],
  )
  const draftSync = useTicketDraftSync({
    staffId,
    ticketNumber: detail.ticket.ticketNumber,
    baseTicketVersion: baseVersion,
    drafts: composerDrafts,
    migrateLegacy: initial.hasLegacyComposerDraft,
    onRecover: (recovered) => {
      setComments((current) => ({
        PUBLIC:
          current.PUBLIC.trim() === ''
            ? (recovered.PUBLIC?.body ?? current.PUBLIC)
            : current.PUBLIC,
        INTERNAL:
          current.INTERNAL.trim() === ''
            ? (recovered.INTERNAL?.body ?? current.INTERNAL)
            : current.INTERNAL,
      }))
      setAttachmentStates((current) => ({
        PUBLIC:
          current.PUBLIC.ids.length === 0 && recovered.PUBLIC
            ? { ...current.PUBLIC, ids: recovered.PUBLIC.attachmentIds }
            : current.PUBLIC,
        INTERNAL:
          current.INTERNAL.ids.length === 0 && recovered.INTERNAL
            ? { ...current.INTERNAL, ids: recovered.INTERNAL.attachmentIds }
            : current.INTERNAL,
      }))
    },
    onFailure: (message, requestId) => setError({ message, requestId }),
  })
  const conflictRef = useRef<HTMLDivElement>(null)
  const storageKey = ticketDraftStorageKey(staffId, detail.ticket.ticketNumber)
  const dirtyFields = useMemo(
    () => new Set(changedTicketFields(serverFields, localFields)),
    [localFields, serverFields],
  )
  const hasComment =
    comments.PUBLIC.trim() !== '' || comments.INTERNAL.trim() !== ''
  const hasAttachments =
    attachmentStates.PUBLIC.ids.length > 0 ||
    attachmentStates.INTERNAL.ids.length > 0
  const needsAttachmentWarning =
    attachmentStates.PUBLIC.needsNavigationWarning ||
    attachmentStates.INTERNAL.needsNavigationWarning
  const isUnsaved =
    dirtyFields.size > 0 ||
    hasComment ||
    hasAttachments ||
    needsAttachmentWarning
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
    writeEditorState({
      mode,
      comments,
      fields: localFields,
      serverFields,
      baseVersion,
      attachmentIds: persistedAttachmentIds(attachmentStates),
      ...(pendingCommandId ? { pendingCommandId } : {}),
      ...(pendingCommand ? { pendingCommand } : {}),
    })
  }, [
    baseVersion,
    attachmentStates,
    comments,
    isUnsaved,
    localFields,
    mode,
    pendingCommandId,
    pendingCommand,
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

  const submit = async (attachmentIds: string[] = []) => {
    if (submitting || unresolvedConflict || !hasActiveSubmit) return false
    setSubmitting(true)
    setError(null)
    setSuccess(null)
    setWarnings([])
    const submittedMode = mode
    const submittedComment = comments[submittedMode].trim().length > 0
    const clientCommandId = pendingCommandId ?? createOpaqueUuid()
    const command =
      pendingCommand ??
      buildUpdateTicketCommand({
        expectedVersion: baseVersion,
        serverFields,
        localFields,
        comment: { visibility: submittedMode, body: comments[submittedMode] },
        attachmentIds,
        clientCommandId,
      })
    if (pendingCommandId === null) {
      setPendingCommandId(clientCommandId)
      setPendingCommand(command)
      writeEditorState({
        mode,
        comments,
        fields: localFields,
        serverFields,
        baseVersion,
        attachmentIds: persistedAttachmentIds(attachmentStates),
        pendingCommandId: clientCommandId,
        pendingCommand: command,
      })
    }
    try {
      const result = await updateAgentTicket(
        detail.ticket.ticketNumber,
        command,
      )
      const confirmedComments = { ...comments, [submittedMode]: '' }
      writeEditorState({
        mode,
        comments: confirmedComments,
        fields: localFields,
        serverFields: localFields,
        baseVersion: result.version,
      })
      setPendingCommandId(null)
      setPendingCommand(null)
      setAttachmentStates((current) => ({
        ...current,
        [submittedMode]: emptyAttachmentState(),
      }))
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
      return true
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
      return false
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
    attachmentStates,
    updateAttachmentState: (
      visibility: TicketVisibility,
      state: AttachmentDraftState,
    ) => {
      const nextAttachmentStates = { ...attachmentStates, [visibility]: state }
      const pendingIds = pendingCommand?.comment?.attachmentIds ?? []
      if (pendingCommand && !sameAttachmentIds(pendingIds, state.ids)) {
        invalidatePendingCommand({ attachmentStates: nextAttachmentStates })
      }
      setAttachmentStates(nextAttachmentStates)
    },
    error,
    success,
    warnings,
    draftSyncState: draftSync.state,
    isUnsaved,
    blocker,
    canSubmit: hasActiveSubmit && !submitting && !unresolvedConflict,
  }

  function invalidatePendingCommand(
    next: {
      mode?: TicketVisibility
      comments?: TicketCommentDrafts
      fields?: EditableTicketFields
      attachmentStates?: Record<TicketVisibility, AttachmentDraftState>
    } = {},
  ) {
    if (pendingCommandId) {
      writeEditorState({
        mode: next.mode ?? mode,
        comments: next.comments ?? comments,
        fields: next.fields ?? localFields,
        serverFields,
        baseVersion,
        attachmentIds: persistedAttachmentIds(
          next.attachmentStates ?? attachmentStates,
        ),
      })
    } else {
      clearPendingTicketCommand(localStorage, storageKey)
    }
    setPendingCommandId(null)
    setPendingCommand(null)
  }

  function writeEditorState(
    snapshot: Parameters<typeof writeTicketDraft>[2],
  ) {
    const preserveAmbiguousCommand =
      snapshot.pendingCommandId !== undefined || snapshot.pendingCommand !== undefined
    writeTicketDraft(localStorage, storageKey, {
      ...snapshot,
      comments: preserveAmbiguousCommand
        ? snapshot.comments
        : { PUBLIC: '', INTERNAL: '' },
      attachmentIds: preserveAmbiguousCommand
        ? snapshot.attachmentIds
        : undefined,
    })
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
      attachmentIds: undefined,
      pendingCommandId: undefined,
      pendingCommand: undefined,
      hasLegacyComposerDraft: false,
    }
  }
  if (stored.pendingCommandId) {
    return detail.ticket.isChild
      ? {
          ...stored,
          mode: 'INTERNAL' as const,
          comments: { ...stored.comments, PUBLIC: '' },
          hasLegacyComposerDraft: hasComposerDraft(stored),
        }
      : { ...stored, hasLegacyComposerDraft: hasComposerDraft(stored) }
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
      hasLegacyComposerDraft: hasComposerDraft(stored),
    }
  }
  return detail.ticket.isChild
    ? {
        ...stored,
        mode: 'INTERNAL' as const,
        comments: { ...stored.comments, PUBLIC: '' },
        hasLegacyComposerDraft: hasComposerDraft(stored),
      }
    : { ...stored, hasLegacyComposerDraft: hasComposerDraft(stored) }
}

function hasComposerDraft(stored: {
  comments: TicketCommentDrafts
  attachmentIds?: Partial<Record<TicketVisibility, string[]>>
}) {
  return (
    stored.comments.PUBLIC.trim() !== '' ||
    stored.comments.INTERNAL.trim() !== '' ||
    (stored.attachmentIds?.PUBLIC?.length ?? 0) > 0 ||
    (stored.attachmentIds?.INTERNAL?.length ?? 0) > 0
  )
}

function initialAttachmentStates(
  pendingCommand?: UpdateTicketCommand,
  storedAttachmentIds: Partial<Record<TicketVisibility, string[]>> = {},
) {
  const empty = emptyAttachmentState()
  const states: Record<TicketVisibility, AttachmentDraftState> = {
    PUBLIC: { ...empty, ids: [...(storedAttachmentIds.PUBLIC ?? [])] },
    INTERNAL: { ...empty, ids: [...(storedAttachmentIds.INTERNAL ?? [])] },
  }
  if (pendingCommand?.comment?.attachmentIds?.length) {
    states[pendingCommand.comment.visibility] = {
      ...empty,
      ids: [...pendingCommand.comment.attachmentIds],
    }
  }
  return states
}

function emptyAttachmentState(): AttachmentDraftState {
  return { blocked: false, ids: [], needsNavigationWarning: false }
}

function persistedAttachmentIds(
  states: Record<TicketVisibility, AttachmentDraftState>,
) {
  return {
    ...(states.PUBLIC.ids.length ? { PUBLIC: states.PUBLIC.ids } : {}),
    ...(states.INTERNAL.ids.length ? { INTERNAL: states.INTERNAL.ids } : {}),
  }
}

function sameAttachmentIds(left: string[], right: string[]) {
  return (
    left.length === right.length &&
    left.every((id, index) => id === right[index])
  )
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
