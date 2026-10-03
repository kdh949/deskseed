import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ApiError,
  clearAgentTicketDraft,
  getAgentTicketDraft,
  saveAgentTicketDraft,
} from '../../api/client'
import { createOpaqueUuid } from '../../api/uuid'
import type {
  CommentContent,
  TicketDraftChannel,
  TicketVisibility,
} from '../../api/types'
import {
  makeLocalTicketDraft,
  newestRecoverableDraft,
  readLocalTicketDraft,
  removeLocalTicketDraft,
  writeLocalTicketDraft,
} from './draftRecovery'

const DEBOUNCE_MS = 3_000

const CHANNELS: Record<TicketVisibility, TicketDraftChannel> = {
  PUBLIC: 'PUBLIC_REPLY',
  INTERNAL: 'INTERNAL_NOTE',
}

type ComposerDrafts = Record<
  TicketVisibility,
  { body: string; content: CommentContent; attachmentIds: string[] }
>

export type TicketDraftSyncState =
  'loading' | 'synced' | 'local-only' | 'conflict' | 'error'

export function useTicketDraftSync({
  staffId,
  ticketNumber,
  baseTicketVersion,
  drafts,
  migrateLegacy,
  onRecover,
  onFailure,
}: {
  staffId: string
  ticketNumber: number
  baseTicketVersion: number
  drafts: ComposerDrafts
  migrateLegacy: boolean
  onRecover: (recovered: Partial<ComposerDrafts>) => void
  onFailure: (message: string, requestId?: string) => void
}) {
  const [state, setState] = useState<TicketDraftSyncState>('loading')
  const hydrated = useRef(false)
  const hydration = useRef<Promise<void> | null>(null)
  const versions = useRef<Record<TicketVisibility, number | null>>({
    PUBLIC: null,
    INTERNAL: null,
  })
  const clientDeviceId = useRef(createOpaqueUuid())
  const recoveryCallback = useRef(onRecover)
  const failureCallback = useRef(onFailure)
  recoveryCallback.current = onRecover
  failureCallback.current = onFailure
  const fingerprint = useMemo(
    () =>
      JSON.stringify({
        baseTicketVersion,
        drafts,
      }),
    [baseTicketVersion, drafts],
  )
  const lastSynchronized = useRef<string | null>(null)
  const draftsRef = useRef(drafts)
  const baseVersionRef = useRef(baseTicketVersion)
  const synchronizeRef = useRef<{
    fingerprint: string
    run: () => Promise<void>
  }>({ fingerprint: '', run: async () => undefined })
  const timerRef = useRef<number | null>(null)
  const inFlightRef = useRef<Promise<void> | null>(null)
  const queuedRef = useRef(false)
  const submittedChannels = useRef(new Set<TicketVisibility>())
  draftsRef.current = drafts
  baseVersionRef.current = baseTicketVersion

  const requestSynchronization = useCallback(() => {
    if (!hydrated.current) return Promise.resolve()
    queuedRef.current = true
    if (inFlightRef.current) return inFlightRef.current

    const drain = async () => {
      while (queuedRef.current) {
        queuedRef.current = false
        const synchronizer = synchronizeRef.current
        if (lastSynchronized.current !== synchronizer.fingerprint) {
          await synchronizer.run()
        }
      }
    }
    const flight = drain().finally(() => {
      if (inFlightRef.current === flight) inFlightRef.current = null
    })
    inFlightRef.current = flight
    return flight
  }, [])

  useEffect(() => {
    let active = true
    const recover = async () => {
      const recovered: Partial<ComposerDrafts> = {}
      let hadLocalFailure = false
      let needsServerSync = migrateLegacy
      await Promise.all(
        (Object.keys(CHANNELS) as TicketVisibility[]).map(
          async (visibility) => {
            const channel = CHANNELS[visibility]
            let local = null
            try {
              local = await readLocalTicketDraft(staffId, ticketNumber, channel)
            } catch {
              hadLocalFailure = true
            }
            let remote = null
            try {
              remote = await getAgentTicketDraft(ticketNumber, channel)
              versions.current[visibility] = remote.draftVersion
            } catch (cause) {
              if (!(cause instanceof ApiError) || cause.status !== 404) {
                if (active) {
                  failureCallback.current(
                    '서버 복구 초안을 확인하지 못했습니다. 이 브라우저의 초안은 유지됩니다.',
                    cause instanceof ApiError ? cause.requestId : undefined,
                  )
                }
              }
            }
            if (submittedChannels.current.has(visibility)) return
            const newest = newestRecoverableDraft(local, remote)
            const current = draftsRef.current[visibility]
            const editorAlreadyContainsDraft =
              current.body.trim() !== '' || current.attachmentIds.length > 0
            if (newest && !editorAlreadyContainsDraft) {
              recovered[visibility] = {
                body: newest.body,
                content: newest.content ?? {
                  format: 'PLAIN_TEXT',
                  text: newest.body,
                },
                attachmentIds: [...newest.attachmentIds],
              }
              if (newest === remote) {
                versions.current[visibility] = newest.draftVersion
              } else {
                // A recovered browser draft is newer than (or absent from) the
                // server copy. Preserve the fetched optimistic version and let
                // the normal synchronization effect persist this recovered copy.
                needsServerSync = true
              }
            } else if (remote && editorAlreadyContainsDraft) {
              // A browser that received local input before hydration must not use the
              // fetched version to silently replace another browser's server draft.
              versions.current[visibility] = null
            }
          },
        ),
      )
      if (!active) return
      recoveryCallback.current(recovered)
      hydrated.current = true
      lastSynchronized.current = needsServerSync
        ? null
        : JSON.stringify({
            baseTicketVersion: baseVersionRef.current,
            drafts: {
              PUBLIC: recovered.PUBLIC ?? draftsRef.current.PUBLIC,
              INTERNAL: recovered.INTERNAL ?? draftsRef.current.INTERNAL,
            },
          })
      setState(
        hadLocalFailure ? 'local-only' : needsServerSync ? 'loading' : 'synced',
      )
    }
    hydration.current = recover()
    return () => {
      active = false
      hydrated.current = false
    }
  }, [migrateLegacy, staffId, ticketNumber])

  useEffect(() => {
    synchronizeRef.current = { fingerprint, run: synchronize }
    if (!hydrated.current || lastSynchronized.current === fingerprint) return
    timerRef.current = window.setTimeout(() => {
      timerRef.current = null
      void requestSynchronization()
    }, DEBOUNCE_MS)
    return () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current)
        timerRef.current = null
      }
    }

    async function synchronize() {
      let hasServerFailure = false
      let hasLocalFailure = false
      let hasConflict = false
      await Promise.all(
        (Object.keys(CHANNELS) as TicketVisibility[]).map(
          async (visibility) => {
            if (submittedChannels.current.has(visibility)) return
            const channel = CHANNELS[visibility]
            const draft = drafts[visibility]
            if (draft.body.trim() === '' && draft.attachmentIds.length === 0) {
              try {
                await removeLocalTicketDraft(staffId, ticketNumber, channel)
              } catch {
                hasLocalFailure = true
              }
              const version = versions.current[visibility]
              if (version !== null) {
                try {
                  await clearAgentTicketDraft(ticketNumber, channel, version)
                  versions.current[visibility] = null
                } catch (cause) {
                  if (cause instanceof ApiError && cause.status === 404) {
                    versions.current[visibility] = null
                  } else {
                    hasServerFailure = true
                  }
                }
              }
              return
            }

            const local = makeLocalTicketDraft({
              staffId,
              ticketNumber,
              channel,
              body: draft.body,
              content: draft.content,
              attachmentIds: draft.attachmentIds,
              clientDeviceId: clientDeviceId.current,
              baseTicketVersion,
              draftVersion: versions.current[visibility],
            })
            let localSaved = true
            try {
              await writeLocalTicketDraft(local)
            } catch {
              hasLocalFailure = true
              localSaved = false
            }
            try {
              const saved = await saveAgentTicketDraft(ticketNumber, channel, {
                content: draft.content,
                attachmentIds: draft.attachmentIds,
                clientDeviceId: clientDeviceId.current,
                baseTicketVersion,
                expectedDraftVersion: versions.current[visibility] ?? 0,
              })
              versions.current[visibility] = saved.draftVersion
            } catch (cause) {
              if (cause instanceof ApiError && cause.status === 409) {
                hasConflict = true
                failureCallback.current(
                  '다른 브라우저에서 더 최신 초안이 저장되었습니다. 자동 덮어쓰기를 중지했습니다.',
                  cause.requestId,
                )
                return
              }
              hasServerFailure = true
              failureCallback.current(
                localSaved
                  ? '서버에 복구 초안을 저장하지 못했습니다. 이 브라우저의 7일 초안은 유지됩니다.'
                  : '서버와 이 브라우저에 복구 초안을 보관하지 못했습니다. 작성 내용은 현재 화면에 유지됩니다.',
                cause instanceof ApiError ? cause.requestId : undefined,
              )
            }
          },
        ),
      )
      if (hasConflict) setState('conflict')
      else if (hasLocalFailure) setState('error')
      else if (hasServerFailure) setState('local-only')
      else setState('synced')
      lastSynchronized.current = fingerprint
    }
  }, [
    baseTicketVersion,
    drafts,
    fingerprint,
    requestSynchronization,
    staffId,
    ticketNumber,
  ])

  return {
    state,
    preserveLocalForNavigation: async () => {
      // Finish hydration/older writes before persisting the current two-channel snapshot.
      // This local checkpoint is distinct from remote autosave and ticket submission.
      await hydration.current
      await inFlightRef.current
      await Promise.all(
        (Object.keys(CHANNELS) as TicketVisibility[]).map(
          async (visibility) => {
            const channel = CHANNELS[visibility]
            const draft = draftsRef.current[visibility]
            if (draft.body.trim() === '' && draft.attachmentIds.length === 0) {
              await removeLocalTicketDraft(staffId, ticketNumber, channel)
              return
            }
            await writeLocalTicketDraft(
              makeLocalTicketDraft({
                staffId,
                ticketNumber,
                channel,
                body: draft.body,
                content: draft.content,
                attachmentIds: draft.attachmentIds,
                clientDeviceId: clientDeviceId.current,
                baseTicketVersion: baseVersionRef.current,
                draftVersion: versions.current[visibility],
              }),
            )
          },
        ),
      )
    },
    resumeChannel: (visibility: TicketVisibility) => {
      if (submittedChannels.current.delete(visibility))
        lastSynchronized.current = null
    },
    clearSubmitted: async (visibility: TicketVisibility) => {
      // A confirmed ticket write must finish channel cleanup before a detail
      // refresh can unmount the editor. Never wait for the autosave debounce.
      submittedChannels.current.add(visibility)
      const knownRecovery = hydrated.current
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current)
        timerRef.current = null
      }
      const previous = inFlightRef.current
      const cleanup = async () => {
        await hydration.current
        await previous
        const channel = CHANNELS[visibility]
        let localFailed = false
        try {
          await removeLocalTicketDraft(staffId, ticketNumber, channel)
        } catch {
          localFailed = true
        }
        const version = versions.current[visibility]
        if (!knownRecovery && version !== null) {
          setState('conflict')
          failureCallback.current(
            '티켓 저장은 완료됐습니다. 저장 전에 확인하지 못한 서버 복구 초안은 삭제하지 않았습니다. 복구 내용과 대화를 확인해 주세요.',
          )
          return
        }
        if (version !== null) {
          try {
            await clearAgentTicketDraft(ticketNumber, channel, version)
            versions.current[visibility] = null
          } catch (cause) {
            if (cause instanceof ApiError && cause.status === 404) {
              versions.current[visibility] = null
            } else {
              const conflict = cause instanceof ApiError && cause.status === 409
              setState(conflict ? 'conflict' : 'local-only')
              failureCallback.current(
                conflict
                  ? '티켓 저장은 완료됐습니다. 다른 브라우저의 새 복구 초안은 삭제하지 않았습니다. 이미 저장한 답변을 다시 제출하지 마세요.'
                  : '티켓 저장은 완료됐지만 서버 복구 초안을 정리하지 못했습니다. 다시 로그인하면 복구 내용과 대화를 확인하고 이미 저장한 답변을 다시 제출하지 마세요.',
                cause instanceof ApiError ? cause.requestId : undefined,
              )
              return
            }
          }
        }
        if (localFailed) {
          setState('error')
          failureCallback.current(
            '티켓 저장은 완료됐지만 이 브라우저의 복구 초안을 정리하지 못했습니다. 복구 시 이미 저장한 답변을 다시 제출하지 마세요.',
          )
        }
      }
      const flight = cleanup().finally(() => {
        if (inFlightRef.current === flight) {
          inFlightRef.current = null
          if (queuedRef.current) void requestSynchronization()
        }
      })
      inFlightRef.current = flight
      await flight
    },
    flush: () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current)
        timerRef.current = null
      }
      return requestSynchronization()
    },
  }
}
