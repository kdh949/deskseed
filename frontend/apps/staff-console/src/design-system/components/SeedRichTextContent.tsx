import { Fragment, type ReactNode } from 'react'
import type {
  RichTextDocumentV1,
  RichTextMarkV1,
  RichTextNodeV1,
} from '../../api/types'
import { isAllowedEditorLink } from './richTextLink'
import './seed-rich-text.css'

export function SeedRichTextContent({
  document,
  resolveAttachment,
}: {
  document: RichTextDocumentV1
  resolveAttachment?: (attachmentId: string) => string | null
}) {
  return (
    <div className="seed-rich-content">
      {document.content.map((node, index) => (
        <RichNode
          key={`${node.type}-${index}`}
          node={node}
          path={`${index}`}
          resolveAttachment={resolveAttachment}
        />
      ))}
    </div>
  )
}

function RichNode({
  node,
  path,
  resolveAttachment,
}: {
  node: RichTextNodeV1
  path: string
  resolveAttachment?: (attachmentId: string) => string | null
}): ReactNode {
  const children = node.content?.map((child, index) => (
    <RichNode
      key={`${path}-${index}`}
      node={child}
      path={`${path}-${index}`}
      resolveAttachment={resolveAttachment}
    />
  ))
  const style = node.attrs?.textAlign
    ? { textAlign: node.attrs.textAlign }
    : undefined
  if (node.type === 'text') {
    return applyMarks(node.text ?? '', node.marks ?? [], path)
  }
  if (node.type === 'hardBreak') return <br />
  if (node.type === 'paragraph') return <p style={style}>{children}</p>
  if (node.type === 'heading') {
    const level = node.attrs?.level ?? 1
    if (level === 2) return <h2 style={style}>{children}</h2>
    if (level === 3) return <h3 style={style}>{children}</h3>
    return <h1 style={style}>{children}</h1>
  }
  if (node.type === 'bulletList') return <ul>{children}</ul>
  if (node.type === 'orderedList') return <ol>{children}</ol>
  if (node.type === 'listItem') return <li>{children}</li>
  if (node.type === 'blockquote') return <blockquote>{children}</blockquote>
  if (node.type === 'codeBlock') {
    return (
      <pre>
        <code>{children}</code>
      </pre>
    )
  }
  if (node.type === 'attachmentImage') {
    const attachmentId = node.attrs?.attachmentId ?? ''
    const alt = node.attrs?.alt ?? ''
    const source = resolveAttachment?.(attachmentId)
    return source ? (
      <figure>
        <img alt={alt} src={source} />
        <figcaption>{alt}</figcaption>
      </figure>
    ) : (
      <p className="seed-rich-content__attachment">첨부 이미지: {alt}</p>
    )
  }
  return <Fragment>{children}</Fragment>
}

function applyMarks(
  text: string,
  marks: RichTextMarkV1[],
  key: string,
): ReactNode {
  return marks.reduce<ReactNode>((child, mark, index) => {
    const markKey = `${key}-${mark.type}-${index}`
    if (mark.type === 'bold') return <strong key={markKey}>{child}</strong>
    if (mark.type === 'italic') return <em key={markKey}>{child}</em>
    if (mark.type === 'underline') return <u key={markKey}>{child}</u>
    if (mark.type === 'code') return <code key={markKey}>{child}</code>
    return 'attrs' in mark && isAllowedEditorLink(mark.attrs.href) ? (
      <a
        href={mark.attrs.href}
        key={markKey}
        rel="noopener noreferrer"
        target="_blank"
      >
        {child}
      </a>
    ) : (
      child
    )
  }, text)
}
