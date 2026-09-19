import { describe, expect, it } from 'vitest'
import type { AiReplyAttributionSource } from '../../../api/types'
import {
  aiReplyAttributionForPublicComment,
  noAiReplyLineage,
  updateAiReplyLineageAfterEdit,
  updateAiReplyLineageAfterInsert,
} from './aiReplyLineage'

const source = (
  sequence: number,
  candidateId = `${sequence}2222222-2222-4222-8222-222222222222`,
): AiReplyAttributionSource => ({
  jobId: `${sequence}1111111-1111-4111-8111-111111111111`,
  candidateId,
  originalAnswer: `AI 답변 ${sequence}`,
})

describe('AI reply lineage', () => {
  it('tracks ordered append sources and replaces them with a new candidate', () => {
    const first = updateAiReplyLineageAfterInsert({
      current: noAiReplyLineage(),
      source: source(1),
      strategy: 'append',
    })
    const appended = updateAiReplyLineageAfterInsert({
      current: first,
      source: source(2),
      strategy: 'append',
    })
    const replaced = updateAiReplyLineageAfterInsert({
      current: appended,
      source: source(3),
      strategy: 'replace',
    })

    expect(appended.sources).toEqual([source(1), source(2)])
    expect(replaced).toEqual({
      contractVersion: 'AI_SENT_V1',
      state: 'LINEAGE_PRESENT',
      sources: [source(3)],
    })
  })

  it('deduplicates a shared origin candidate and loses lineage above four sources', () => {
    const duplicate = updateAiReplyLineageAfterInsert({
      current: updateAiReplyLineageAfterInsert({
        current: noAiReplyLineage(),
        source: source(1),
        strategy: 'append',
      }),
      source: source(2, source(1).candidateId),
      strategy: 'append',
    })
    expect(duplicate.sources).toEqual([source(1)])

    let lineage = noAiReplyLineage()
    for (let index = 1; index <= 5; index += 1) {
      lineage = updateAiReplyLineageAfterInsert({
        current: lineage,
        source: source(index),
        strategy: 'append',
      })
    }
    expect(lineage).toEqual({
      contractVersion: 'AI_SENT_V1',
      state: 'LINEAGE_LOST',
      sources: [],
    })
  })

  it('preserves normal edits, marks an untrackable replacement lost, and clears on deletion', () => {
    const lineage = updateAiReplyLineageAfterInsert({
      current: noAiReplyLineage(),
      source: source(1),
      strategy: 'replace',
    })
    const edited = updateAiReplyLineageAfterEdit({
      current: lineage,
      previousText: '결제 승인 기록을 확인하고 있습니다.',
      nextText: '안녕하세요. 결제 승인 기록을 확인하고 있습니다.',
    })
    const lost = updateAiReplyLineageAfterEdit({
      current: edited,
      previousText: '안녕하세요. 결제 승인 기록을 확인하고 있습니다.',
      nextText: '배송 주소 변경 절차를 새로 안내드립니다.',
    })
    const cleared = updateAiReplyLineageAfterEdit({
      current: lost,
      previousText: '배송 주소 변경 절차를 새로 안내드립니다.',
      nextText: '',
    })

    expect(edited).toBe(lineage)
    expect(lost.state).toBe('LINEAGE_LOST')
    expect(cleared).toEqual(noAiReplyLineage())
  })

  it('returns a defensive command payload', () => {
    const lineage = updateAiReplyLineageAfterInsert({
      current: noAiReplyLineage(),
      source: source(1),
      strategy: 'replace',
    })
    const attribution = aiReplyAttributionForPublicComment(lineage)

    expect(attribution).toEqual(lineage)
    expect(attribution.sources).not.toBe(lineage.sources)
  })
})
