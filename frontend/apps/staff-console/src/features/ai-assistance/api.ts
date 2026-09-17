import { requestStaffResource } from '../../api/client'
import { createOpaqueUuid } from '../../api/uuid'
import type { TicketPriority } from '../../api/types'

export type AiFeature =
  'ticket.summary' | 'ticket.triage' | 'ticket.reply_draft'

export type AiJobStatus =
  | 'ACCEPTED'
  | 'QUEUED'
  | 'RUNNING'
  | 'RETRY_WAIT'
  | 'SUCCEEDED'
  | 'NEEDS_REVIEW'
  | 'FAILED'
  | 'CANCELLED'
  | 'SUPERSEDED'
  | 'EXPIRED'

export type AiJobPhase =
  'QUEUED' | 'AUTHORIZE' | 'RETRIEVE' | 'GENERATE' | 'VALIDATE' | 'COMPLETE'

export interface AiSummaryResult {
  type: 'ticket.summary'
  problem: string
  attemptedActions: string[]
  unresolvedItems: string[]
  nextChecks: string[]
}

export interface AiTriageResult {
  type: 'ticket.triage'
  topicCode:
    | 'ACCOUNT_ACCESS'
    | 'BILLING'
    | 'USAGE'
    | 'TECHNICAL_ISSUE'
    | 'POLICY'
    | 'FEEDBACK'
    | 'OTHER'
  suggestedTagIds: string[]
  suggestedPriority: TicketPriority | null
  reasons: string[]
}

export interface AiCitation {
  articleId: string
  revisionId: string
  chunkId: string
  title: string
  url: string
}

export interface AiReplyDraftResult {
  type: 'ticket.reply_draft'
  answer: string
  citations: AiCitation[]
}

export type AiResult = AiSummaryResult | AiTriageResult | AiReplyDraftResult

export interface AiJobReceipt {
  jobId: string
  feature: AiFeature
  status: AiJobStatus
  phase: AiJobPhase
  requestRevision: number
  createdAt: string
  deadlineAt: string
  completedAt: string | null
  resultExpiresAt: string | null
  pollAfterMs: number
  cancelRequested: boolean
  contextRevision: string
  contextPolicyVersion: 'public-comments-v1'
  inputScope: 'PUBLIC_ONLY'
  stale: boolean
  canInsert: boolean
  errorCode: string | null
  result: AiResult | null
}

export interface AiJobPage {
  items: AiJobReceipt[]
}

export type AiFeedbackType = 'helpful' | 'unhelpful' | 'inserted' | 'edited'

const FEATURES = new Set<AiFeature>([
  'ticket.summary',
  'ticket.triage',
  'ticket.reply_draft',
])
const STATUSES = new Set<AiJobStatus>([
  'ACCEPTED',
  'QUEUED',
  'RUNNING',
  'RETRY_WAIT',
  'SUCCEEDED',
  'NEEDS_REVIEW',
  'FAILED',
  'CANCELLED',
  'SUPERSEDED',
  'EXPIRED',
])
const PHASES = new Set<AiJobPhase>([
  'QUEUED',
  'AUTHORIZE',
  'RETRIEVE',
  'GENERATE',
  'VALIDATE',
  'COMPLETE',
])
const TOPICS = new Set<AiTriageResult['topicCode']>([
  'ACCOUNT_ACCESS',
  'BILLING',
  'USAGE',
  'TECHNICAL_ISSUE',
  'POLICY',
  'FEEDBACK',
  'OTHER',
])
const PRIORITIES = new Set<TicketPriority>(['LOW', 'NORMAL', 'HIGH', 'URGENT'])
const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const REVISION = /^[0-9a-f]{64}$/
const ARTICLE_URL = /^\/help\/articles\/[a-z0-9-]+$/

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const text = (value: unknown): value is string =>
  typeof value === 'string' && value.length > 0
const nullableText = (value: unknown): value is string | null =>
  value === null || text(value)
const integer = (value: unknown, minimum = 0): value is number =>
  Number.isSafeInteger(value) && Number(value) >= minimum
const textArray = (value: unknown, max: number): value is string[] =>
  Array.isArray(value) && value.length <= max && value.every(text)
const uuidArray = (value: unknown, max: number): value is string[] =>
  Array.isArray(value) &&
  value.length <= max &&
  value.every((item) => text(item) && UUID.test(item))

function decodeResult(value: unknown): AiResult | null | undefined {
  if (value === null) return null
  if (!record(value)) return undefined
  if (
    value.type === 'ticket.summary' &&
    text(value.problem) &&
    textArray(value.attemptedActions, 20) &&
    textArray(value.unresolvedItems, 20) &&
    textArray(value.nextChecks, 20)
  ) {
    return value as unknown as AiSummaryResult
  }
  if (
    value.type === 'ticket.triage' &&
    text(value.topicCode) &&
    TOPICS.has(value.topicCode as AiTriageResult['topicCode']) &&
    uuidArray(value.suggestedTagIds, 20) &&
    (value.suggestedPriority === null ||
      (text(value.suggestedPriority) &&
        PRIORITIES.has(value.suggestedPriority as TicketPriority))) &&
    textArray(value.reasons, 10)
  ) {
    return value as unknown as AiTriageResult
  }
  if (
    value.type === 'ticket.reply_draft' &&
    text(value.answer) &&
    Array.isArray(value.citations) &&
    value.citations.length <= 8 &&
    value.citations.every(
      (citation) =>
        record(citation) &&
        text(citation.articleId) &&
        UUID.test(citation.articleId) &&
        text(citation.revisionId) &&
        UUID.test(citation.revisionId) &&
        text(citation.chunkId) &&
        UUID.test(citation.chunkId) &&
        text(citation.title) &&
        text(citation.url) &&
        ARTICLE_URL.test(citation.url),
    )
  ) {
    return value as unknown as AiReplyDraftResult
  }
  return undefined
}

export function decodeAiJobReceipt(value: unknown): AiJobReceipt | undefined {
  if (!record(value)) return undefined
  const result = decodeResult(value.result)
  if (
    !text(value.jobId) ||
    !UUID.test(value.jobId) ||
    !text(value.feature) ||
    !FEATURES.has(value.feature as AiFeature) ||
    !text(value.status) ||
    !STATUSES.has(value.status as AiJobStatus) ||
    !text(value.phase) ||
    !PHASES.has(value.phase as AiJobPhase) ||
    !integer(value.requestRevision, 1) ||
    !text(value.createdAt) ||
    !text(value.deadlineAt) ||
    !nullableText(value.completedAt) ||
    !nullableText(value.resultExpiresAt) ||
    !integer(value.pollAfterMs, 100) ||
    value.pollAfterMs > 10_000 ||
    typeof value.cancelRequested !== 'boolean' ||
    !text(value.contextRevision) ||
    !REVISION.test(value.contextRevision) ||
    value.contextPolicyVersion !== 'public-comments-v1' ||
    value.inputScope !== 'PUBLIC_ONLY' ||
    typeof value.stale !== 'boolean' ||
    typeof value.canInsert !== 'boolean' ||
    !nullableText(value.errorCode) ||
    result === undefined
  ) {
    return undefined
  }
  return {
    jobId: value.jobId,
    feature: value.feature as AiFeature,
    status: value.status as AiJobStatus,
    phase: value.phase as AiJobPhase,
    requestRevision: value.requestRevision,
    createdAt: value.createdAt,
    deadlineAt: value.deadlineAt,
    completedAt: value.completedAt,
    resultExpiresAt: value.resultExpiresAt,
    pollAfterMs: value.pollAfterMs,
    cancelRequested: value.cancelRequested,
    contextRevision: value.contextRevision,
    contextPolicyVersion: 'public-comments-v1',
    inputScope: 'PUBLIC_ONLY',
    stale: value.stale,
    canInsert: value.canInsert,
    errorCode: value.errorCode,
    result,
  }
}

export function decodeAiJobPage(value: unknown): AiJobPage | undefined {
  if (!record(value) || !Array.isArray(value.items) || value.items.length > 50)
    return undefined
  const items = value.items.map(decodeAiJobReceipt)
  return items.some((item) => item === undefined)
    ? undefined
    : { items: items as AiJobReceipt[] }
}

const jobPath = (ticketNumber: number, jobId: string) =>
  `/api/v1/agent/tickets/${ticketNumber}/ai/jobs/${jobId}` as const

export const listAiJobs = (ticketNumber: number) =>
  requestStaffResource(
    `/api/v1/agent/tickets/${ticketNumber}/ai/jobs?limit=20`,
    decodeAiJobPage,
  )

export const getAiJob = (
  ticketNumber: number,
  jobId: string,
  includeResult = false,
) =>
  requestStaffResource(
    `${jobPath(ticketNumber, jobId)}${includeResult ? '?includeResult=true' : ''}`,
    decodeAiJobReceipt,
  )

export const createAiJob = (
  ticketNumber: number,
  expectedTicketVersion: number,
  feature: AiFeature,
) =>
  requestStaffResource(
    `/api/v1/agent/tickets/${ticketNumber}/ai/jobs`,
    decodeAiJobReceipt,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': createOpaqueUuid() },
      body: {
        feature,
        expectedTicketVersion,
        options:
          feature === 'ticket.reply_draft'
            ? { language: 'ko', tone: 'calm' }
            : {},
      },
    },
  )

export const cancelAiJob = (ticketNumber: number, jobId: string) =>
  requestStaffResource(
    `${jobPath(ticketNumber, jobId)}/cancel`,
    decodeAiJobReceipt,
    {
      method: 'POST',
    },
  )

const decodeFeedbackReceipt = (value: unknown) =>
  record(value) &&
  text(value.jobId) &&
  UUID.test(value.jobId) &&
  text(value.type) &&
  typeof value.replayed === 'boolean' &&
  text(value.recordedAt)
    ? value
    : undefined

export const recordAiFeedback = (
  ticketNumber: number,
  jobId: string,
  type: AiFeedbackType,
) =>
  requestStaffResource(
    `${jobPath(ticketNumber, jobId)}/feedback`,
    decodeFeedbackReceipt,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': createOpaqueUuid() },
      body: { type },
    },
  )
