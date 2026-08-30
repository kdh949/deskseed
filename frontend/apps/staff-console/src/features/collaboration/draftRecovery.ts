import type {
  CommentContent,
  TicketDraft,
  TicketDraftChannel,
} from '../../api/types'

export const LOCAL_DRAFT_RETENTION_MS = 7 * 24 * 60 * 60 * 1000

export type LocalTicketDraft = {
  staffId: string
  ticketNumber: number
  channel: TicketDraftChannel
  body: string
  content?: CommentContent
  attachmentIds: string[]
  clientDeviceId: string
  baseTicketVersion: number
  draftVersion: number | null
  updatedAt: string
  expiresAt: string
}

const DATABASE_NAME = 'deskseed-collaboration'
const DATABASE_VERSION = 1
const STORE_NAME = 'ticket-drafts'

export function localDraftKey(
  staffId: string,
  ticketNumber: number,
  channel: TicketDraftChannel,
) {
  return `${staffId}:${ticketNumber}:${channel}`
}

export function makeLocalTicketDraft({
  staffId,
  ticketNumber,
  channel,
  body,
  attachmentIds,
  clientDeviceId,
  baseTicketVersion,
  draftVersion = null,
  now = new Date(),
}: Omit<LocalTicketDraft, 'updatedAt' | 'expiresAt' | 'draftVersion'> & {
  draftVersion?: number | null
  now?: Date
}): LocalTicketDraft {
  return {
    staffId,
    ticketNumber,
    channel,
    body,
    attachmentIds: [...attachmentIds],
    clientDeviceId,
    baseTicketVersion,
    draftVersion,
    updatedAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + LOCAL_DRAFT_RETENTION_MS).toISOString(),
  }
}

export function newestRecoverableDraft(
  local: LocalTicketDraft | null,
  remote: TicketDraft | null,
  now = Date.now(),
): LocalTicketDraft | TicketDraft | null {
  const usableLocal = local && Date.parse(local.expiresAt) > now ? local : null
  const usableRemote =
    remote && Date.parse(remote.expiresAt) > now ? remote : null
  if (!usableLocal) return usableRemote
  if (!usableRemote) return usableLocal
  return Date.parse(usableLocal.updatedAt) >= Date.parse(usableRemote.updatedAt)
    ? usableLocal
    : usableRemote
}

export async function readLocalTicketDraft(
  staffId: string,
  ticketNumber: number,
  channel: TicketDraftChannel,
): Promise<LocalTicketDraft | null> {
  const database = await openDatabase()
  const key = localDraftKey(staffId, ticketNumber, channel)
  const draft = await request<LocalTicketDraft | undefined>(
    database
      .transaction(STORE_NAME, 'readonly')
      .objectStore(STORE_NAME)
      .get(key),
  )
  if (!draft) return null
  if (Date.parse(draft.expiresAt) <= Date.now()) {
    await removeLocalTicketDraft(staffId, ticketNumber, channel)
    return null
  }
  return draft
}

export async function writeLocalTicketDraft(draft: LocalTicketDraft) {
  const database = await openDatabase()
  await request(
    database
      .transaction(STORE_NAME, 'readwrite')
      .objectStore(STORE_NAME)
      .put({
        ...draft,
        id: localDraftKey(draft.staffId, draft.ticketNumber, draft.channel),
      }),
  )
}

export async function removeLocalTicketDraft(
  staffId: string,
  ticketNumber: number,
  channel: TicketDraftChannel,
) {
  const database = await openDatabase()
  await request(
    database
      .transaction(STORE_NAME, 'readwrite')
      .objectStore(STORE_NAME)
      .delete(localDraftKey(staffId, ticketNumber, channel)),
  )
}

async function openDatabase(): Promise<IDBDatabase> {
  if (typeof indexedDB === 'undefined') {
    throw new Error('IndexedDB is unavailable for local draft recovery')
  }
  return new Promise((resolve, reject) => {
    const opening = indexedDB.open(DATABASE_NAME, DATABASE_VERSION)
    opening.onupgradeneeded = () => {
      if (!opening.result.objectStoreNames.contains(STORE_NAME)) {
        opening.result.createObjectStore(STORE_NAME, { keyPath: 'id' })
      }
    }
    opening.onsuccess = () => resolve(opening.result)
    opening.onerror = () => reject(opening.error)
    opening.onblocked = () =>
      reject(new Error('IndexedDB upgrade is blocked by another Deskseed tab'))
  })
}

function request<T = undefined>(value: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    value.onsuccess = () => resolve(value.result)
    value.onerror = () => reject(value.error)
  })
}
