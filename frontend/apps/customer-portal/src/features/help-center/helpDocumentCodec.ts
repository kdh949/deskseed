export type HelpBlock =
  | { type: 'paragraph' | 'code' | 'quote' | 'callout'; text: string }
  | { type: 'heading'; text: string; level: 2 | 3 }
  | { type: 'list'; ordered: boolean; items: string[] }
  | { type: 'divider' }
  | { type: 'link'; text: string; url: string }
  | { type: 'attachment'; attachmentId: string }
export function parseHelpBlocks(document: unknown): HelpBlock[] {
  if (!document || typeof document !== 'object')
    throw new Error('help-document-invalid')
  const value = document as Record<string, unknown>
  if (
    value.schemaVersion !== 1 ||
    !Array.isArray(value.blocks) ||
    value.blocks.length > 200
  )
    throw new Error('help-document-invalid')
  return value.blocks.map((raw): HelpBlock => {
    if (!raw || typeof raw !== 'object') throw new Error('help-block-invalid')
    const block = raw as Record<string, unknown>
    const text =
      typeof block.text === 'string' && block.text.length <= 10000
        ? block.text
        : null
    if (
      ['paragraph', 'code', 'quote', 'callout'].includes(String(block.type)) &&
      text !== null
    )
      return {
        type: block.type as 'paragraph' | 'code' | 'quote' | 'callout',
        text,
      }
    if (
      block.type === 'heading' &&
      text !== null &&
      (block.level === 2 || block.level === 3)
    )
      return { type: 'heading', text, level: block.level }
    if (
      block.type === 'list' &&
      typeof block.ordered === 'boolean' &&
      Array.isArray(block.items) &&
      block.items.length <= 100 &&
      block.items.every(
        (item) => typeof item === 'string' && item.length <= 10000,
      )
    )
      return {
        type: 'list',
        ordered: block.ordered,
        items: block.items as string[],
      }
    if (block.type === 'divider') return { type: 'divider' }
    if (block.type === 'attachment' && typeof block.attachmentId === 'string')
      return { type: 'attachment', attachmentId: block.attachmentId }
    if (
      block.type === 'link' &&
      text !== null &&
      typeof block.url === 'string'
    ) {
      const url = new URL(block.url)
      if (
        url.protocol === 'https:' &&
        !url.username &&
        !url.password &&
        block.url.length <= 2048
      )
        return { type: 'link', text, url: url.href }
    }
    throw new Error('help-block-invalid')
  })
}
