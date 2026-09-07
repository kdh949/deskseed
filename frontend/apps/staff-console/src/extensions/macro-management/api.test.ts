import { describe, expect, it } from 'vitest'
import { decodeHistory, editableDraft, toMacroDraft } from './api'
import type { AgentMacroDefinition } from '../../api/types'
describe('macro management contracts', () => {
  it('preserves actions not editable in the basic editor', () => {
    const existing = {
      name: '분류',
      actions: [
        {
          type: 'CUSTOM_FIELD',
          fieldKey: 'order.number',
          value: { textValue: '123' },
        },
        { type: 'COMMENT', visibility: 'INTERNAL', template: '기존 메모' },
      ],
    } as AgentMacroDefinition
    const draft = editableDraft(existing)
    draft.template = '수정 메모'
    expect(toMacroDraft(draft)).toEqual({
      name: '분류',
      actions: [
        existing.actions[0],
        { type: 'COMMENT', visibility: 'INTERNAL', template: '수정 메모' },
      ],
    })
  })
  it('rejects malformed history rather than showing incomplete success', () => {
    expect(decodeHistory({ versions: [], activations: [] })).toEqual({
      versions: [],
      activations: [],
    })
    expect(
      decodeHistory({
        versions: [],
        activations: [{ version: 1, state: 'ACTIVE' }],
      }),
    ).toBeUndefined()
  })
})
