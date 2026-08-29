import { createElement, useEffect, useState, type ReactNode } from 'react'
import type {
  AttachmentDownload,
  CommentBlockNode,
  CommentContent,
  CommentInlineNode,
  CommentTextMark,
  TicketAttachment,
} from '../api/types'

export function CustomerCommentContent({
  attachments,
  body,
  content,
  downloadAttachment,
}: {
  attachments: TicketAttachment[]
  body: string
  content?: CommentContent
  downloadAttachment?: (attachmentId: string) => Promise<AttachmentDownload>
}) {
  if (!content || content.format === 'PLAIN_TEXT') {
    return <p className="customer-comment-content">{content?.text ?? body}</p>
  }
  return (
    <div className="customer-comment-content customer-comment-content--rich">
      {content.document.content.map((node, index) => (
        <RichBlock
          attachments={attachments}
          downloadAttachment={downloadAttachment}
          key={`${node.type}-${index}`}
          node={node}
        />
      ))}
    </div>
  )
}

function RichBlock({
  attachments,
  downloadAttachment,
  node,
}: {
  attachments: TicketAttachment[]
  downloadAttachment?: (attachmentId: string) => Promise<AttachmentDownload>
  node: CommentBlockNode
}): ReactNode {
  if (node.type === 'paragraph') {
    return (
      <p style={{ textAlign: node.attrs?.textAlign }}>
        {renderInline(node.content)}
      </p>
    )
  }
  if (node.type === 'heading') {
    return createElement(
      `h${node.attrs.level}`,
      { style: { textAlign: node.attrs.textAlign } },
      renderInline(node.content),
    )
  }
  if (node.type === 'bulletList' || node.type === 'orderedList') {
    const List = node.type === 'bulletList' ? 'ul' : 'ol'
    return (
      <List>
        {node.content.map((item, index) => (
          <li key={index}>
            {item.content.map((child, childIndex) => (
              <RichBlock
                attachments={attachments}
                downloadAttachment={downloadAttachment}
                key={`${child.type}-${childIndex}`}
                node={child}
              />
            ))}
          </li>
        ))}
      </List>
    )
  }
  if (node.type === 'blockquote') {
    return (
      <blockquote>
        {node.content.map((child, index) => (
          <RichBlock
            attachments={attachments}
            downloadAttachment={downloadAttachment}
            key={`${child.type}-${index}`}
            node={child}
          />
        ))}
      </blockquote>
    )
  }
  if (node.type === 'codeBlock') {
    return (
      <pre>
        <code>{node.content?.map((item) => item.text).join('') ?? ''}</code>
      </pre>
    )
  }
  if (node.type !== 'attachmentImage') return null
  const attachment = attachments.find(
    (item) => item.id === node.attrs.attachmentId,
  )
  if (!attachment) return null
  return (
    <RichAttachmentImage
      alt={node.attrs.alt}
      attachment={attachment}
      downloadAttachment={downloadAttachment}
    />
  )
}

function renderInline(nodes?: CommentInlineNode[]) {
  return nodes?.map((node, index) => {
    if (node.type === 'hardBreak') return <br key={index} />
    return applyMarks(node.text, node.marks ?? [], index)
  })
}

function applyMarks(
  text: string,
  marks: CommentTextMark[],
  key: number,
): ReactNode {
  return marks.reduce<ReactNode>((child, mark, index) => {
    const markKey = `${key}-${index}`
    if (mark.type === 'bold') return <strong key={markKey}>{child}</strong>
    if (mark.type === 'italic') return <em key={markKey}>{child}</em>
    if (mark.type === 'underline') return <u key={markKey}>{child}</u>
    if (mark.type === 'code') return <code key={markKey}>{child}</code>
    if (mark.type === 'link') {
      return (
        <a href={mark.attrs.href} key={markKey} rel="noreferrer">
          {child}
        </a>
      )
    }
    return child
  }, text)
}

function RichAttachmentImage({
  alt,
  attachment,
  downloadAttachment,
}: {
  alt: string
  attachment: TicketAttachment
  downloadAttachment?: (attachmentId: string) => Promise<AttachmentDownload>
}) {
  const [source, setSource] = useState<string>()
  const [unavailable, setUnavailable] = useState(false)

  useEffect(() => {
    if (!downloadAttachment) return
    let active = true
    let objectUrl: string | undefined
    void downloadAttachment(attachment.id)
      .then((download) => {
        if (!active || !download.contentType.startsWith('image/')) {
          if (active) setUnavailable(true)
          return
        }
        objectUrl = URL.createObjectURL(download.content)
        setSource(objectUrl)
      })
      .catch(() => {
        if (active) setUnavailable(true)
      })
    return () => {
      active = false
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [attachment.id, downloadAttachment])

  if (!downloadAttachment || unavailable) {
    return (
      <p className="customer-comment-content__attachment">
        {alt} · {attachment.fileName}
      </p>
    )
  }
  if (!source) {
    return (
      <p aria-live="polite" className="customer-comment-content__attachment">
        이미지 불러오는 중
      </p>
    )
  }
  return (
    <figure className="customer-comment-content__image">
      <img alt={alt} src={source} />
      <figcaption>{attachment.fileName}</figcaption>
    </figure>
  )
}
