import { useState } from 'react'
import type { AttachmentDownload, TicketAttachment } from '../../api/types'
import { DsButton } from '../../design-system'
import './attachments.css'

export function AttachmentList({
  attachments,
  download,
}: {
  attachments: TicketAttachment[]
  download: (attachmentId: string) => Promise<AttachmentDownload>
}) {
  const [downloadingId, setDownloadingId] = useState<string | null>(null)
  const [errorId, setErrorId] = useState<string | null>(null)
  if (!attachments.length) return null

  const handleDownload = async (attachment: TicketAttachment) => {
    setDownloadingId(attachment.id)
    setErrorId(null)
    try {
      const result = await download(attachment.id)
      const url = URL.createObjectURL(result.content)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = result.fileName ?? attachment.fileName
      anchor.click()
      URL.revokeObjectURL(url)
    } catch {
      setErrorId(attachment.id)
    } finally {
      setDownloadingId(null)
    }
  }

  return (
    <ul aria-label="첨부 파일" className="linked-attachment-list">
      {attachments.map((attachment) => (
        <li key={attachment.id}>
          <span>
            <strong>{attachment.fileName}</strong>
            <small>{formatBytes(attachment.sizeBytes)}</small>
            {errorId === attachment.id ? (
              <small role="alert">
                다운로드가 거부되었거나 파일이 삭제·만료되었습니다.
              </small>
            ) : null}
          </span>
          <DsButton
            disabled={downloadingId !== null}
            onClick={() => void handleDownload(attachment)}
            tone="secondary"
          >
            {downloadingId === attachment.id ? '다운로드 중…' : '다운로드'}
          </DsButton>
        </li>
      ))}
    </ul>
  )
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
