import type {
  AiReplyAttribution,
  AiReplyAttributionSource,
} from '../../../api/types'

export type AiReplyLineage = AiReplyAttribution
export type AiReplyInsertStrategy = 'append' | 'replace'

const MAX_LINEAGE_SOURCES = 4

export function noAiReplyLineage(): AiReplyLineage {
  return {
    contractVersion: 'AI_SENT_V1',
    state: 'NO_AI_LINEAGE',
    sources: [],
  }
}

export function updateAiReplyLineageAfterInsert({
  current,
  source,
  strategy,
}: {
  current: AiReplyLineage
  source: AiReplyAttributionSource
  strategy: AiReplyInsertStrategy
}): AiReplyLineage {
  if (strategy === 'replace') return presentLineage([source])
  if (current.state === 'LINEAGE_LOST') return current

  const sources =
    current.state === 'LINEAGE_PRESENT' ? [...current.sources] : []
  if (
    sources.some((candidate) => candidate.candidateId === source.candidateId)
  ) {
    return presentLineage(sources)
  }
  if (sources.length >= MAX_LINEAGE_SOURCES) return lostLineage()
  return presentLineage([...sources, source])
}

export function updateAiReplyLineageAfterEdit({
  current,
  previousText,
  nextText,
}: {
  current: AiReplyLineage
  previousText: string
  nextText: string
}): AiReplyLineage {
  if (nextText.trim() === '') return noAiReplyLineage()
  if (current.state !== 'LINEAGE_PRESENT') return current
  if (
    previousText === nextText ||
    hasSharedTextAnchor(previousText, nextText)
  ) {
    return current
  }
  return lostLineage()
}

export function aiReplyAttributionForPublicComment(
  lineage: AiReplyLineage,
): AiReplyAttribution {
  return {
    contractVersion: 'AI_SENT_V1',
    state: lineage.state,
    sources: [...lineage.sources],
  }
}

function presentLineage(sources: AiReplyAttributionSource[]): AiReplyLineage {
  return {
    contractVersion: 'AI_SENT_V1',
    state: 'LINEAGE_PRESENT',
    sources,
  }
}

function lostLineage(): AiReplyLineage {
  return {
    contractVersion: 'AI_SENT_V1',
    state: 'LINEAGE_LOST',
    sources: [],
  }
}

function hasSharedTextAnchor(previousText: string, nextText: string) {
  const previous = normalizedCodePoints(previousText)
  const next = normalizedCodePoints(nextText)
  const shorter = previous.length <= next.length ? previous : next
  const longer = shorter === previous ? next : previous
  if (shorter.length === 0) return false

  const anchorLength = Math.min(8, Math.max(2, Math.ceil(shorter.length / 2)))
  const longerText = longer.join('')
  if (shorter.length < anchorLength)
    return longerText.includes(shorter.join(''))
  const longerAnchors = new Set<string>()
  for (let index = 0; index <= longer.length - anchorLength; index += 1) {
    longerAnchors.add(longer.slice(index, index + anchorLength).join(''))
  }
  for (let index = 0; index <= shorter.length - anchorLength; index += 1) {
    if (longerAnchors.has(shorter.slice(index, index + anchorLength).join('')))
      return true
  }
  return false
}

function normalizedCodePoints(value: string) {
  return Array.from(value.normalize('NFKC').replace(/\s+/g, ' ').trim())
}
