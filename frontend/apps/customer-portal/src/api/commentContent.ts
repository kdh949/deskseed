import type {
  CommentBlockNode,
  CommentContent,
  CommentInlineNode,
  CommentTextMark,
} from './types'

export function decodeCommentContent(
  value: unknown,
  attachmentIds: ReadonlySet<string>,
): CommentContent | undefined {
  if (!isRecord(value) || typeof value.format !== 'string') return undefined
  if (value.format === 'PLAIN_TEXT') {
    return onlyKeys(value, ['format', 'text']) && typeof value.text === 'string'
      ? { format: 'PLAIN_TEXT', text: value.text }
      : undefined
  }
  if (
    value.format !== 'RICH_TEXT_V1' ||
    !onlyKeys(value, ['format', 'document'])
  )
    return undefined
  const document = value.document
  if (
    !isRecord(document) ||
    !onlyKeys(document, ['type', 'content']) ||
    document.type !== 'doc' ||
    !Array.isArray(document.content)
  )
    return undefined
  const blocks = document.content.map((node) =>
    decodeBlock(node, attachmentIds, 1),
  )
  if (blocks.some((node) => !node)) return undefined
  return {
    format: 'RICH_TEXT_V1',
    document: { type: 'doc', content: blocks as CommentBlockNode[] },
  }
}

function decodeBlock(
  value: unknown,
  attachmentIds: ReadonlySet<string>,
  depth: number,
): CommentBlockNode | undefined {
  if (depth > 12 || !isRecord(value) || typeof value.type !== 'string')
    return undefined
  if (value.type === 'paragraph' || value.type === 'heading') {
    const allowed =
      value.type === 'heading'
        ? ['type', 'attrs', 'content']
        : ['type', 'attrs', 'content']
    if (!onlyKeys(value, allowed)) return undefined
    const attrs = decodeTextAttrs(value.attrs, value.type === 'heading')
    if (attrs === undefined) return undefined
    const content = decodeInlineArray(value.content)
    if (content === undefined) return undefined
    if (value.type === 'heading') {
      if (!('level' in attrs)) return undefined
      return {
        type: 'heading',
        attrs: attrs as {
          level: 1 | 2 | 3
          textAlign?: 'left' | 'center' | 'right'
        },
        ...(content ? { content } : {}),
      }
    }
    return {
      type: 'paragraph',
      ...(Object.keys(attrs).length ? { attrs } : {}),
      ...(content ? { content } : {}),
    }
  }
  if (value.type === 'bulletList' || value.type === 'orderedList') {
    if (!onlyKeys(value, ['type', 'content']) || !Array.isArray(value.content))
      return undefined
    const items = value.content.map((item) => {
      if (
        !isRecord(item) ||
        item.type !== 'listItem' ||
        !onlyKeys(item, ['type', 'content']) ||
        !Array.isArray(item.content)
      )
        return undefined
      const children = item.content.map((child) =>
        decodeBlock(child, attachmentIds, depth + 1),
      )
      if (children.some((child) => !child)) return undefined
      return {
        type: 'listItem' as const,
        content: children as CommentBlockNode[],
      }
    })
    if (items.some((item) => !item)) return undefined
    return {
      type: value.type,
      content: items as Array<{
        type: 'listItem'
        content: CommentBlockNode[]
      }>,
    }
  }
  if (value.type === 'blockquote') {
    if (!onlyKeys(value, ['type', 'content']) || !Array.isArray(value.content))
      return undefined
    const content = value.content.map((child) =>
      decodeBlock(child, attachmentIds, depth + 1),
    )
    return content.some((child) => !child)
      ? undefined
      : { type: 'blockquote', content: content as CommentBlockNode[] }
  }
  if (value.type === 'codeBlock') {
    if (!onlyKeys(value, ['type', 'content'])) return undefined
    if (value.content === undefined) return { type: 'codeBlock' }
    if (!Array.isArray(value.content)) return undefined
    const content = value.content.map((node) => {
      if (
        !isRecord(node) ||
        !onlyKeys(node, ['type', 'text']) ||
        node.type !== 'text' ||
        typeof node.text !== 'string'
      )
        return undefined
      return { type: 'text' as const, text: node.text }
    })
    return content.some((node) => !node)
      ? undefined
      : {
          type: 'codeBlock',
          content: content as Array<{ type: 'text'; text: string }>,
        }
  }
  if (value.type === 'attachmentImage') {
    if (
      !onlyKeys(value, ['type', 'attrs']) ||
      !isRecord(value.attrs) ||
      !onlyKeys(value.attrs, ['attachmentId', 'alt'])
    )
      return undefined
    if (
      typeof value.attrs.attachmentId !== 'string' ||
      !attachmentIds.has(value.attrs.attachmentId) ||
      typeof value.attrs.alt !== 'string' ||
      !value.attrs.alt.trim()
    )
      return undefined
    return {
      type: 'attachmentImage',
      attrs: { attachmentId: value.attrs.attachmentId, alt: value.attrs.alt },
    }
  }
  return undefined
}

function decodeInlineArray(
  value: unknown,
): CommentInlineNode[] | null | undefined {
  if (value === undefined) return null
  if (!Array.isArray(value)) return undefined
  const nodes = value.map((node): CommentInlineNode | undefined => {
    if (!isRecord(node) || typeof node.type !== 'string') return undefined
    if (node.type === 'hardBreak')
      return onlyKeys(node, ['type']) ? { type: 'hardBreak' } : undefined
    if (
      node.type !== 'text' ||
      !onlyKeys(node, ['type', 'text', 'marks']) ||
      typeof node.text !== 'string'
    )
      return undefined
    if (node.marks === undefined) return { type: 'text', text: node.text }
    if (!Array.isArray(node.marks)) return undefined
    const marks = node.marks.map(decodeMark)
    return marks.some((mark) => !mark)
      ? undefined
      : { type: 'text', text: node.text, marks: marks as CommentTextMark[] }
  })
  return nodes.some((node) => !node)
    ? undefined
    : (nodes as CommentInlineNode[])
}

function decodeMark(value: unknown): CommentTextMark | undefined {
  if (!isRecord(value) || typeof value.type !== 'string') return undefined
  if (['bold', 'italic', 'underline', 'code'].includes(value.type)) {
    return onlyKeys(value, ['type'])
      ? ({ type: value.type } as CommentTextMark)
      : undefined
  }
  if (
    value.type !== 'link' ||
    !onlyKeys(value, ['type', 'attrs']) ||
    !isRecord(value.attrs) ||
    !onlyKeys(value.attrs, ['href']) ||
    typeof value.attrs.href !== 'string' ||
    !safeHref(value.attrs.href)
  )
    return undefined
  return { type: 'link', attrs: { href: value.attrs.href } }
}

function decodeTextAttrs(
  value: unknown,
  heading: boolean,
):
  | Record<string, never>
  | { textAlign?: 'left' | 'center' | 'right'; level?: 1 | 2 | 3 }
  | undefined {
  if (value === undefined) return heading ? undefined : {}
  if (
    !isRecord(value) ||
    !onlyKeys(value, heading ? ['level', 'textAlign'] : ['textAlign'])
  )
    return undefined
  if (heading && value.level !== 1 && value.level !== 2 && value.level !== 3)
    return undefined
  if (
    value.textAlign !== undefined &&
    value.textAlign !== 'left' &&
    value.textAlign !== 'center' &&
    value.textAlign !== 'right'
  )
    return undefined
  return {
    ...(heading ? { level: value.level as 1 | 2 | 3 } : {}),
    ...(value.textAlign ? { textAlign: value.textAlign } : {}),
  }
}

function safeHref(value: string) {
  try {
    return ['http:', 'https:', 'mailto:'].includes(new URL(value).protocol)
  } catch {
    return false
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function onlyKeys(value: Record<string, unknown>, allowed: string[]) {
  return Object.keys(value).every((key) => allowed.includes(key))
}
