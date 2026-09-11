/* eslint-disable no-control-regex -- The frozen consent schema rejects control characters. */
import type { ConsentBlock } from '../../../design-system'

export function decodeConsentBlocks(values: unknown[]): ConsentBlock[] {
  const invalid = () => new Error('customer-consent-policy-response-invalid')
  const text = (value: unknown): value is string =>
    typeof value === 'string' &&
    value.trim().length > 0 &&
    [...value].length <= 10000 &&
    !/[<>\u0000-\u001f\u007f]/u.test(value)
  if (values.length < 1 || values.length > 100) throw invalid()
  return values.map((value): ConsentBlock => {
    if (!value || typeof value !== 'object') throw invalid()
    const block = value as Record<string, unknown>
    switch (block.type) {
      case 'divider':
        return { type: 'divider' }
      case 'list':
        if (
          typeof block.ordered !== 'boolean' ||
          !Array.isArray(block.items) ||
          block.items.length < 1 ||
          block.items.length > 100 ||
          !block.items.every(text)
        )
          throw invalid()
        return { type: 'list', ordered: block.ordered, items: block.items }
      case 'heading':
        if ((block.level !== 2 && block.level !== 3) || !text(block.text))
          throw invalid()
        return { type: 'heading', level: block.level, text: block.text }
      case 'link': {
        if (
          !text(block.text) ||
          typeof block.url !== 'string' ||
          block.url.length > 2048 ||
          /[\s<>\u0000-\u001f\u007f]/u.test(block.url)
        )
          throw invalid()
        let url: URL
        try {
          url = new URL(block.url)
        } catch {
          throw invalid()
        }
        if (
          url.protocol !== 'https:' ||
          !url.hostname ||
          url.username ||
          url.password
        )
          throw invalid()
        return { type: 'link', text: block.text, url: block.url }
      }
      case 'paragraph':
      case 'quote':
      case 'callout':
        if (!text(block.text)) throw invalid()
        return { type: block.type, text: block.text }
      default:
        throw invalid()
    }
  })
}
