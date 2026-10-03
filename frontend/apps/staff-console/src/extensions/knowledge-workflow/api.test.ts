import { describe, expect, it } from 'vitest'
import { articleLink, decodeDocument } from './api'
describe('knowledge boundaries', () => {
  it('never inserts staff-only article links in public replies', () => {
    for (const type of ['STAFF', 'SELECTED_STAFF_GROUPS'] as const) {
      const article = {
        slug: 'internal-runbook',
        audience: { type, groupIds: [] },
      }
      expect(
        articleLink(article, 'PUBLIC', 'https://deskseed.example'),
      ).toBeUndefined()
      expect(articleLink(article, 'INTERNAL', 'https://deskseed.example')).toBe(
        'https://deskseed.example/agent/knowledge/articles/internal-runbook',
      )
    }
  })
  it('links public help to the customer route', () =>
    expect(
      articleLink(
        { slug: 'refund-guide', audience: { type: 'PUBLIC', groupIds: [] } },
        'PUBLIC',
        'https://deskseed.example',
      ),
    ).toBe('https://deskseed.example/articles/refund-guide'))
  it('rejects raw HTML, unsafe URLs and unsupported blocks', () => {
    for (const block of [
      { type: 'html', value: '<img onerror=alert(1)>' },
      { type: 'link', text: '위험', url: 'javascript:alert(1)' },
      { type: 'link', text: '위험', url: 'https://user:password@example.com' },
      { type: 'unknown', text: 'untrusted' },
    ])
      expect(
        decodeDocument({ schemaVersion: 1, blocks: [block] }),
      ).toBeUndefined()
    expect(
      decodeDocument({
        schemaVersion: 1,
        blocks: [{ type: 'paragraph', text: '<script>는 텍스트입니다.' }],
      }),
    ).toBeDefined()
  })
})
