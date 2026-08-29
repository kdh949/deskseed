import { useEffect, useId, useRef, useState, type ChangeEvent } from 'react'
import type { AttachmentUpload } from '../../api/types'
import { SeedButton, SeedIcon } from '../../design-system/canonical'
import { MAX_ATTACHMENTS } from './attachmentPolicy'

type UploadState =
  | { key: string; fileName: string; status: 'RESTORED'; attachmentId: string }
  | { key: string; fileName: string; status: 'UPLOADING' }
  | { key: string; fileName: string; status: 'CLEAN'; upload: AttachmentUpload }
  | {
      key: string
      fileName: string
      status: 'REJECTED' | 'FAILED'
      message: string
    }

export interface AttachmentDraftState {
  blocked: boolean
  ids: string[]
  needsNavigationWarning: boolean
}

export function AttachmentUploadField({
  disabled = false,
  label = '첨부 파일',
  initialAttachmentIds = [],
  onStateChange,
  resetVersion = 0,
  upload,
}: {
  disabled?: boolean
  initialAttachmentIds?: string[]
  label?: string
  onStateChange: (state: AttachmentDraftState) => void
  resetVersion?: number
  upload: (file: File) => Promise<AttachmentUpload>
}) {
  const inputId = useId()
  const inputRef = useRef<HTMLInputElement>(null)
  const onStateChangeRef = useRef(onStateChange)
  onStateChangeRef.current = onStateChange
  const [items, setItems] = useState<UploadState[]>(() =>
    restoredItems(initialAttachmentIds),
  )
  const [limitError, setLimitError] = useState(false)
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    setItems(restoredItems(initialAttachmentIds))
    setLimitError(false)
  }, [resetVersion])

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000)
    return () => window.clearInterval(timer)
  }, [])

  const pending = items.some((item) => item.status === 'UPLOADING')
  const expired = items.some(
    (item) =>
      item.status === 'CLEAN' && Date.parse(item.upload.expiresAt) <= now,
  )
  const blocked = pending || expired || items.some(isRejected)
  const ids = items.flatMap((item) =>
    item.status === 'RESTORED'
      ? [item.attachmentId]
      : item.status === 'CLEAN' && Date.parse(item.upload.expiresAt) > now
        ? [item.upload.id]
        : [],
  )
  const needsNavigationWarning = items.some(
    (item) => item.status === 'UPLOADING' || isRejected(item),
  )
  const activeCount = items.filter(
    (item) =>
      item.status === 'UPLOADING' ||
      item.status === 'RESTORED' ||
      (item.status === 'CLEAN' && Date.parse(item.upload.expiresAt) > now),
  ).length

  useEffect(() => {
    onStateChangeRef.current({ blocked, ids, needsNavigationWarning })
  }, [blocked, ids.join('|'), needsNavigationWarning])

  useEffect(() => {
    if (!pending) return
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [pending])

  const selectFiles = (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? [])
    if (!files.length) return
    if (activeCount + files.length > MAX_ATTACHMENTS) {
      setLimitError(true)
      event.target.value = ''
      return
    }
    setLimitError(false)
    for (const file of files) {
      const key = crypto.randomUUID()
      setItems((current) => [
        ...current,
        { key, fileName: file.name, status: 'UPLOADING' },
      ])
      void upload(file)
        .then((result) => {
          setItems((current) =>
            current.map((item) =>
              item.key === key
                ? { key, fileName: file.name, status: 'CLEAN', upload: result }
                : item,
            ),
          )
        })
        .catch((error) => {
          const rejected = statusOf(error) === 422
          setItems((current) =>
            current.map((item) =>
              item.key === key
                ? {
                    key,
                    fileName: file.name,
                    status: rejected ? 'REJECTED' : 'FAILED',
                    message: uploadFailureMessage(error),
                  }
                : item,
            ),
          )
        })
    }
    event.target.value = ''
  }

  return (
    <section
      aria-label={`${label} 업로드 영역`}
      className="seed-attachment-upload"
    >
      <div className="seed-attachment-upload__heading">
        <input
          aria-label={label}
          className="seed-visually-hidden"
          disabled={disabled || pending || activeCount >= MAX_ATTACHMENTS}
          id={inputId}
          multiple
          onChange={selectFiles}
          ref={inputRef}
          type="file"
        />
        <SeedButton
          disabled={disabled || pending || activeCount >= MAX_ATTACHMENTS}
          onClick={() => inputRef.current?.click()}
          variant="quiet"
        >
          <SeedIcon name="paperclip" /> 파일 첨부
        </SeedButton>
      </div>
      {items.length ? (
        <ul aria-live="polite" className="seed-attachment-upload__list">
          {items.map((item) => {
            const itemExpired =
              item.status === 'CLEAN' &&
              Date.parse(item.upload.expiresAt) <= now
            return (
              <li key={item.key}>
                <span>
                  <strong>{item.fileName}</strong>
                  <small>{uploadStatus(item, itemExpired)}</small>
                  {item.status === 'UPLOADING' ? (
                    <progress
                      aria-label={`${item.fileName} 업로드 및 검사 진행 중`}
                    />
                  ) : null}
                </span>
                {item.status !== 'UPLOADING' ? (
                  <SeedButton
                    disabled={disabled}
                    onClick={() =>
                      setItems((current) =>
                        current.filter(
                          (candidate) => candidate.key !== item.key,
                        ),
                      )
                    }
                    variant="quiet"
                  >
                    초안에서 제거
                  </SeedButton>
                ) : null}
              </li>
            )
          })}
        </ul>
      ) : (
        <p className="seed-visually-hidden">선택된 파일이 없습니다.</p>
      )}
      {blocked ? (
        <p className="seed-attachment-upload__blocker" role="status">
          모든 파일이 CLEAN 상태여야 제출할 수 있습니다.
        </p>
      ) : null}
      {limitError ? (
        <p className="seed-attachment-upload__blocker" role="alert">
          첨부 파일은 최대 {MAX_ATTACHMENTS}개까지 선택할 수 있습니다.
        </p>
      ) : null}
    </section>
  )
}

function isRejected(item: UploadState) {
  return item.status === 'REJECTED' || item.status === 'FAILED'
}

function uploadStatus(item: UploadState, expired: boolean) {
  if (item.status === 'RESTORED') return '이전 저장 시도에서 복원됨'
  if (item.status === 'UPLOADING') return '업로드 및 악성 파일 검사 중'
  if (item.status !== 'CLEAN') {
    return item.status === 'REJECTED'
      ? `감염 또는 격리됨 · ${item.message}`
      : `업로드 실패 · ${item.message}`
  }
  if (expired) return '업로드 만료 · 파일을 다시 선택해 주세요.'
  return `CLEAN · ${formatBytes(item.upload.sizeBytes)}`
}

function restoredItems(attachmentIds: string[]): UploadState[] {
  return attachmentIds.map((attachmentId, index) => ({
    key: `restored:${attachmentId}`,
    fileName: `복원된 첨부 파일 ${index + 1}`,
    status: 'RESTORED',
    attachmentId,
  }))
}

function uploadFailureMessage(error: unknown) {
  const status = statusOf(error)
  if (status === undefined) return '네트워크 상태를 확인해 주세요.'
  if (status === 413) return '허용된 파일 크기를 초과했습니다.'
  if (status === 415) return '허용되지 않는 파일 형식입니다.'
  if (status === 422) return '안전 검사를 통과하지 못했습니다.'
  if (status === 403) return '업로드 권한이 없습니다.'
  return '파일을 다시 선택해 주세요.'
}

function statusOf(error: unknown) {
  if (typeof error !== 'object' || error === null) return undefined
  const status = (error as { status?: unknown }).status
  return typeof status === 'number' ? status : undefined
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
