import { describe, expect, it } from 'vitest'
import {
  decodeHistory,
  editableDraft,
  toMacroDraft,
  refreshMacroDraft,
} from './api'
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

it('merges only edited fields onto the latest actions in their latest order', () => {
  const before = {
    name: '기존',
    actions: [
      { type: 'GROUP', groupId: 'a' },
      { type: 'COMMENT', visibility: 'PUBLIC', template: '기존 답변' },
      { type: 'PRIORITY', priority: 'NORMAL' },
    ],
  } as AgentMacroDefinition
  const latest = {
    ...before,
    name: '최신 이름',
    actions: [
      { type: 'PRIORITY', priority: 'HIGH' },
      { type: 'GROUP', groupId: 'b' },
      { type: 'COMMENT', visibility: 'PUBLIC', template: '기존 답변' },
    ],
  }
  const local = { ...editableDraft(before), template: '편집한 답변' }
  const result = refreshMacroDraft(local, before, latest)
  expect(result.conflicts).toEqual([])
  expect(toMacroDraft(result.draft)).toEqual({
    name: '최신 이름',
    actions: [
      latest.actions[0],
      latest.actions[1],
      { type: 'COMMENT', visibility: 'PUBLIC', template: '편집한 답변' },
    ],
  })
})
it('identifies same-field conflicts while retaining the local draft for a deliberate choice', () => {
  const before = {
    name: '기존',
    actions: [{ type: 'COMMENT', visibility: 'PUBLIC', template: '기존 답변' }],
  } as AgentMacroDefinition
  const latest = {
    ...before,
    actions: [
      { type: 'COMMENT', visibility: 'INTERNAL', template: '원격 답변' },
    ],
  }
  const result = refreshMacroDraft(
    { ...editableDraft(before), template: '로컬 답변' },
    before,
    latest,
  )
  expect(result.conflicts).toEqual(['template'])
  expect(result.draft).toMatchObject({
    template: '로컬 답변',
    visibility: 'INTERNAL',
  })
})

it('requires the latest custom status when it conflicts with a locally added base status', () => {
  const before = {
    name: '상태',
    actions: [{ type: 'COMMENT', visibility: 'PUBLIC', template: '답변' }],
  } as AgentMacroDefinition
  const latest = {
    ...before,
    actions: [
      ...before.actions,
      { type: 'CUSTOM_STATUS', customStatusId: 'status' },
    ],
  }
  const result = refreshMacroDraft(
    { ...editableDraft(before), status: 'PENDING' },
    before,
    latest,
  )
  expect(result.conflicts).toEqual(['status'])
  expect(result.draft.status).toBe('PENDING')
  expect(
    toMacroDraft({ ...result.draft, status: editableDraft(latest).status })
      .actions,
  ).toEqual(latest.actions)
})
