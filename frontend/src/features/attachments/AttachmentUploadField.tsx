import { useEffect, useId, useRef, useState, type ChangeEvent } from 'react'
import { ApiError } from '../../api/client'
import type { AttachmentUpload } from '../../api/types'
import { DsButton } from '../../design-system'
import './attachments.css'

type UploadState =
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
}

export function AttachmentUploadField({
  disabled = false,
  label = '첨부 파일',
  onStateChange,
  resetVersion = 0,
  upload,
}: {
  disabled?: boolean
  label?: string
  onStateChange: (state: AttachmentDraftState) => void
  resetVersion?: number
  upload: (file: File) => Promise<AttachmentUpload>
}) {
  const inputId = useId()
  const inputRef = useRef<HTMLInputElement>(null)
  const [items, setItems] = useState<UploadState[]>([])
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    setItems([])
  }, [resetVersion])

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000)
    return () => window.clearInterval(timer)
  }, [])

  const pending = items.some((item) => item.status === 'UPLOADING')
  const cleanItems = items.filter(
    (item): item is Extract<UploadState, { status: 'CLEAN' }> =>
      item.status === 'CLEAN' && Date.parse(item.upload.expiresAt) > now,
  )
  const expired = items.some(
    (item) =>
      item.status === 'CLEAN' && Date.parse(item.upload.expiresAt) <= now,
  )
  const blocked = pending || expired || items.some(isRejected)

  useEffect(() => {
    onStateChange({ blocked, ids: cleanItems.map((item) => item.upload.id) })
  }, [
    blocked,
    cleanItems.map((item) => item.upload.id).join('|'),
    onStateChange,
  ])

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
          const rejected = error instanceof ApiError && error.status === 422
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
    <section className="attachment-upload-field">
      <div className="attachment-upload-heading">
        <label htmlFor={inputId}>{label}</label>
        <input
          disabled={disabled || pending}
          id={inputId}
          multiple
          onChange={selectFiles}
          ref={inputRef}
          type="file"
        />
      </div>
      {items.length ? (
        <ul aria-live="polite" className="attachment-upload-list">
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
                  <DsButton
                    disabled={disabled}
                    onClick={() =>
                      setItems((current) =>
                        current.filter(
                          (candidate) => candidate.key !== item.key,
                        ),
                      )
                    }
                    tone="secondary"
                  >
                    초안에서 제거
                  </DsButton>
                ) : null}
              </li>
            )
          })}
        </ul>
      ) : (
        <p className="attachment-upload-empty">선택된 파일이 없습니다.</p>
      )}
      {blocked ? (
        <p className="attachment-upload-blocker" role="status">
          모든 파일이 CLEAN 상태여야 제출할 수 있습니다.
        </p>
      ) : null}
    </section>
  )
}

function isRejected(item: UploadState) {
  return item.status === 'REJECTED' || item.status === 'FAILED'
}

function uploadStatus(item: UploadState, expired: boolean) {
  if (item.status === 'UPLOADING') return '업로드 및 악성 파일 검사 중'
  if (item.status !== 'CLEAN') {
    return item.status === 'REJECTED'
      ? `감염 또는 격리됨 · ${item.message}`
      : `업로드 실패 · ${item.message}`
  }
  if (expired) return '업로드 만료 · 파일을 다시 선택해 주세요.'
  return `CLEAN · ${formatBytes(item.upload.sizeBytes)}`
}

function uploadFailureMessage(error: unknown) {
  if (!(error instanceof ApiError)) return '네트워크 상태를 확인해 주세요.'
  if (error.status === 413) return '허용된 파일 크기를 초과했습니다.'
  if (error.status === 415) return '허용되지 않는 파일 형식입니다.'
  if (error.status === 422) return '안전 검사를 통과하지 못했습니다.'
  if (error.status === 403) return '업로드 권한이 없습니다.'
  return '파일을 다시 선택해 주세요.'
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
