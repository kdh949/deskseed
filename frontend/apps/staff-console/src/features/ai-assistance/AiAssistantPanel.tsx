import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import { createOpaqueUuid } from '../../api/uuid'
import type {
  AiReplyAttributionSource,
  RichTextDocumentV1,
  TicketVisibility,
} from '../../api/types'
import { SeedButton, SeedIcon, SeedNotice } from '../../design-system/canonical'
import {
  cancelAiJob,
  createAiJob,
  getAiJob,
  listAiJobs,
  recordAiFeedback,
  type AiFeature,
  type AiFeedbackType,
  type AiGenerationMode,
  type AiJobReceipt,
  type AiResult,
} from './api'
import './ai-assistant.css'

const FEATURES: Array<{
  feature: AiFeature
  title: string
  description: string
  icon: 'text' | 'priority' | 'speech'
}> = [
  {
    feature: 'ticket.summary',
    title: '대화 요약',
    description: 'PUBLIC 대화의 핵심 내용과 확인할 일을 정리합니다.',
    icon: 'text',
  },
  {
    feature: 'ticket.triage',
    title: '분류 제안',
    description:
      '주제와 우선순위를 제안합니다. 티켓에는 자동 적용하지 않습니다.',
    icon: 'priority',
  },
  {
    feature: 'ticket.reply_draft',
    title: '답변 초안',
    description: 'PUBLIC 대화와 공개 지식 문서를 근거로 답변을 작성합니다.',
    icon: 'speech',
  },
]

const ACTIVE_STATUSES = new Set(['ACCEPTED', 'QUEUED', 'RUNNING', 'RETRY_WAIT'])

const TOPIC_LABELS = {
  ACCOUNT_ACCESS: '계정 접근',
  BILLING: '결제',
  USAGE: '사용 방법',
  TECHNICAL_ISSUE: '기술 문제',
  POLICY: '정책',
  FEEDBACK: '피드백',
  OTHER: '기타',
} as const

const PRIORITY_LABELS = {
  LOW: '낮음',
  NORMAL: '보통',
  HIGH: '높음',
  URGENT: '긴급',
} as const

export interface AiAssistantClient {
  list: typeof listAiJobs
  get: typeof getAiJob
  create: typeof createAiJob
  cancel: typeof cancelAiJob
  feedback: typeof recordAiFeedback
}

const defaultClient: AiAssistantClient = {
  list: listAiJobs,
  get: getAiJob,
  create: createAiJob,
  cancel: cancelAiJob,
  feedback: recordAiFeedback,
}

type JobMap = Partial<Record<AiFeature, AiJobReceipt>>
type ErrorMap = Partial<Record<AiFeature, string>>
type InsertStrategy = 'append' | 'replace'
type PendingCreateCommand = {
  expectedTicketVersion: number
  generationMode: AiGenerationMode
  idempotencyKey: string
}

export interface AiPublicDraftSnapshot {
  body: string
  document: RichTextDocumentV1
  attachmentIds: string[]
}

class AiResultUnavailableError extends Error {}

export function AiAssistantPanel({
  client = defaultClient,
  composerMode,
  publicDraft,
  ticketNumber,
  ticketVersion,
  onInsertReply,
}: {
  client?: AiAssistantClient
  composerMode: TicketVisibility
  publicDraft: AiPublicDraftSnapshot
  ticketNumber: number
  ticketVersion: number
  onInsertReply: (
    answer: string,
    strategy: InsertStrategy,
    expectedDraft: AiPublicDraftSnapshot,
    source: AiReplyAttributionSource,
  ) => boolean | void
}) {
  const [jobs, setJobs] = useState<JobMap>({})
  const [errors, setErrors] = useState<ErrorMap>({})
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [denied, setDenied] = useState(false)
  const [choiceJobId, setChoiceJobId] = useState<string | null>(null)
  const [insertMessage, setInsertMessage] = useState('')
  const [insertingJobId, setInsertingJobId] = useState<string | null>(null)
  const [generatingFeatures, setGeneratingFeatures] = useState<Set<AiFeature>>(
    () => new Set(),
  )
  const [ambiguousCreateFeatures, setAmbiguousCreateFeatures] = useState<
    Set<AiFeature>
  >(() => new Set())
  const [feedbackByJob, setFeedbackByJob] = useState<
    Record<string, AiFeedbackType | undefined>
  >({})
  const latestRef = useRef({
    composerMode,
    publicDraft,
    ticketNumber,
    ticketVersion,
  })
  const insertInFlightRef = useRef(false)
  const generateInFlightRef = useRef(new Set<AiFeature>())
  const pollFailuresRef = useRef(new Map<string, number>())
  const usedReplyJobsRef = useRef(new Set<string>())
  const pendingCreateCommandsRef = useRef(
    new Map<AiFeature, PendingCreateCommand>(),
  )

  latestRef.current = {
    composerMode,
    publicDraft,
    ticketNumber,
    ticketVersion,
  }

  const replaceJob = useCallback((job: AiJobReceipt) => {
    setJobs((current) => ({ ...current, [job.feature]: job }))
  }, [])

  const hydrateResult = useCallback(
    async (job: AiJobReceipt) => {
      if (job.status !== 'SUCCEEDED' || job.result !== null) return job
      const hydrated = await client.get(ticketNumber, job.jobId, true)
      if (hydrated.status === 'SUCCEEDED' && hydrated.result === null) {
        throw new AiResultUnavailableError()
      }
      return hydrated
    },
    [client, ticketNumber],
  )

  useEffect(() => {
    pendingCreateCommandsRef.current.clear()
    generateInFlightRef.current.clear()
    pollFailuresRef.current.clear()
    usedReplyJobsRef.current.clear()
    setAmbiguousCreateFeatures(new Set())
  }, [ticketNumber])

  const load = useCallback(async () => {
    setLoading(true)
    setLoadError(null)
    setDenied(false)
    try {
      const page = await client.list(ticketNumber)
      const latest: JobMap = {}
      for (const job of page.items) {
        if (!latest[job.feature]) latest[job.feature] = job
      }
      const hydrated = await Promise.all(
        Object.values(latest).map(async (job) => {
          try {
            return await hydrateResult(job)
          } catch (cause) {
            setErrors((current) => ({
              ...current,
              [job.feature]: messageForError(cause),
            }))
            return job
          }
        }),
      )
      const listed = Object.fromEntries(
        hydrated.map((job) => [job.feature, job]),
      ) as JobMap
      setJobs((current) => ({ ...listed, ...current }))
    } catch (cause) {
      if (
        cause instanceof ApiError &&
        (cause.status === 401 || cause.status === 403 || cause.status === 404)
      ) {
        setDenied(true)
      } else {
        setLoadError(messageForError(cause))
      }
    } finally {
      setLoading(false)
    }
  }, [client, hydrateResult, ticketNumber])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    const timers = Object.values(jobs).flatMap((job) => {
      if (!ACTIVE_STATUSES.has(job.status)) return []
      const failureCount = pollFailuresRef.current.get(job.jobId) ?? 0
      const timer = window.setTimeout(
        async () => {
          try {
            const next = await hydrateResult(
              await client.get(ticketNumber, job.jobId),
            )
            pollFailuresRef.current.delete(job.jobId)
            replaceJob(next)
            setErrors((current) => ({ ...current, [job.feature]: undefined }))
          } catch (cause) {
            const nextFailureCount = failureCount + 1
            pollFailuresRef.current.set(job.jobId, nextFailureCount)
            setErrors((current) => ({
              ...current,
              [job.feature]: messageForError(cause),
            }))
            setJobs((current) => ({ ...current }))
          }
        },
        Math.min(job.pollAfterMs * 2 ** failureCount, 10_000),
      )
      return [timer]
    })
    return () => timers.forEach(window.clearTimeout)
  }, [client, hydrateResult, jobs, replaceJob, ticketNumber])

  const generate = async (
    feature: AiFeature,
    generationMode: AiGenerationMode,
    retryAmbiguous = false,
  ) => {
    if (loading || generateInFlightRef.current.has(feature)) return
    const pending = pendingCreateCommandsRef.current.get(feature)
    const command = retryAmbiguous
      ? pending
      : {
          expectedTicketVersion: ticketVersion,
          generationMode,
          idempotencyKey: createOpaqueUuid(),
        }
    if (!command) return
    if (!retryAmbiguous) pendingCreateCommandsRef.current.set(feature, command)
    generateInFlightRef.current.add(feature)
    setGeneratingFeatures((current) => new Set(current).add(feature))
    setErrors((current) => ({ ...current, [feature]: undefined }))
    setChoiceJobId(null)
    setInsertMessage('')
    try {
      replaceJob(
        await client.create(
          ticketNumber,
          command.expectedTicketVersion,
          feature,
          command.generationMode,
          command.idempotencyKey,
        ),
      )
      pendingCreateCommandsRef.current.delete(feature)
      setAmbiguousCreateFeatures((current) => withoutFeature(current, feature))
    } catch (cause) {
      if (isAmbiguousCreateOutcome(cause)) {
        setAmbiguousCreateFeatures((current) => new Set(current).add(feature))
      } else {
        pendingCreateCommandsRef.current.delete(feature)
        setAmbiguousCreateFeatures((current) =>
          withoutFeature(current, feature),
        )
      }
      setErrors((current) => ({
        ...current,
        [feature]: messageForCreateError(cause, command.generationMode),
      }))
    } finally {
      generateInFlightRef.current.delete(feature)
      setGeneratingFeatures((current) => {
        const next = new Set(current)
        next.delete(feature)
        return next
      })
    }
  }

  const cancel = async (job: AiJobReceipt) => {
    setErrors((current) => ({ ...current, [job.feature]: undefined }))
    try {
      replaceJob(await client.cancel(ticketNumber, job.jobId))
    } catch (cause) {
      setErrors((current) => ({
        ...current,
        [job.feature]: messageForError(cause),
      }))
    }
  }

  const recordFeedback = async (job: AiJobReceipt, type: AiFeedbackType) => {
    setFeedbackByJob((current) => ({ ...current, [job.jobId]: type }))
    try {
      await client.feedback(ticketNumber, job.jobId, type)
    } catch {
      setFeedbackByJob((current) => ({ ...current, [job.jobId]: undefined }))
    }
  }

  const validateReply = async (job: AiJobReceipt) => {
    const snapshot = latestSnapshot(latestRef.current)
    const latest = await client.get(ticketNumber, job.jobId, true)
    replaceJob(latest)
    const current = latestRef.current
    if (
      snapshot.ticketNumber !== current.ticketNumber ||
      snapshot.ticketVersion !== current.ticketVersion ||
      snapshot.composerMode !== current.composerMode ||
      draftFingerprint(snapshot.publicDraft) !==
        draftFingerprint(current.publicDraft)
    ) {
      setInsertMessage(
        '검증 중 티켓이나 작성기가 변경되었습니다. 다시 시도해 주세요.',
      )
      return null
    }
    if (
      latest.status !== 'SUCCEEDED' ||
      latest.stale ||
      !latest.canInsert ||
      latest.result?.type !== 'ticket.reply_draft' ||
      !latest.candidateId
    ) {
      setInsertMessage(
        '현재 티켓과 일치하는 사용 가능한 초안이 아닙니다. 새로 생성해 주세요.',
      )
      return null
    }
    return {
      result: latest.result,
      publicDraft: snapshot.publicDraft,
      source: {
        jobId: latest.jobId,
        candidateId: latest.candidateId,
        originalAnswer: latest.result.answer,
      },
    }
  }

  const beginInsert = async (job: AiJobReceipt) => {
    if (insertInFlightRef.current) return
    if (usedReplyJobsRef.current.has(job.jobId)) {
      setInsertMessage('이 AI 초안은 이미 작성기에 넣었습니다.')
      return
    }
    insertInFlightRef.current = true
    setInsertingJobId(job.jobId)
    setInsertMessage('')
    try {
      const validated = await validateReply(job)
      if (!validated) return
      if (latestRef.current.composerMode !== 'PUBLIC') {
        setInsertMessage('PUBLIC 답변 작성기를 선택한 뒤 다시 시도해 주세요.')
        return
      }
      if (hasDraft(validated.publicDraft)) {
        setChoiceJobId(job.jobId)
        return
      }
      if (
        onInsertReply(
          validated.result.answer,
          'replace',
          validated.publicDraft,
          validated.source,
        ) === false
      ) {
        setInsertMessage(
          '검증 후 PUBLIC 작성기가 변경되었습니다. 다시 시도해 주세요.',
        )
        return
      }
      usedReplyJobsRef.current.add(job.jobId)
      setInsertMessage(
        '답변 초안을 PUBLIC 작성기에 넣었습니다. 검토 후 보내 주세요.',
      )
      await recordFeedback(job, 'inserted')
    } catch (cause) {
      setInsertMessage(messageForError(cause))
    } finally {
      insertInFlightRef.current = false
      setInsertingJobId(null)
    }
  }

  const completeInsert = async (
    job: AiJobReceipt,
    strategy: InsertStrategy,
  ) => {
    if (insertInFlightRef.current) return
    if (usedReplyJobsRef.current.has(job.jobId)) {
      setChoiceJobId(null)
      setInsertMessage('이 AI 초안은 이미 작성기에 넣었습니다.')
      return
    }
    insertInFlightRef.current = true
    setInsertingJobId(job.jobId)
    setInsertMessage('')
    try {
      const validated = await validateReply(job)
      if (!validated) return
      if (latestRef.current.composerMode !== 'PUBLIC') {
        setChoiceJobId(null)
        setInsertMessage('PUBLIC 답변 작성기를 선택한 뒤 다시 시도해 주세요.')
        return
      }
      if (
        onInsertReply(
          validated.result.answer,
          strategy,
          validated.publicDraft,
          validated.source,
        ) === false
      ) {
        setChoiceJobId(null)
        setInsertMessage(
          '검증 후 PUBLIC 작성기가 변경되었습니다. 다시 시도해 주세요.',
        )
        return
      }
      usedReplyJobsRef.current.add(job.jobId)
      setChoiceJobId(null)
      setInsertMessage(
        strategy === 'append'
          ? '기존 PUBLIC 초안 뒤에 AI 초안을 추가했습니다.'
          : '기존 PUBLIC 초안을 AI 초안으로 교체했습니다.',
      )
      await recordFeedback(job, 'inserted')
    } catch (cause) {
      setInsertMessage(messageForError(cause))
    } finally {
      insertInFlightRef.current = false
      setInsertingJobId(null)
    }
  }

  const availableJobs = useMemo(() => jobs, [jobs])

  return (
    <section aria-labelledby="ai-assistant-title" className="ai-assistant">
      <header className="ai-assistant__header">
        <span className="ai-assistant__brand-icon" aria-hidden="true">
          <SeedIcon name="leaf" />
        </span>
        <div>
          <div className="ai-assistant__title-row">
            <h2 id="ai-assistant-title">DeskSeed AI 어시스턴트</h2>
            <span className="ai-assistant__beta">Beta</span>
          </div>
          <p>상담 판단을 돕는 초안이며 자동으로 티켓을 변경하지 않습니다.</p>
        </div>
      </header>

      <div className="ai-assistant__scope">
        <SeedIcon name="lock" size="small" />
        <span>PUBLIC 대화만 사용 · INTERNAL 메모와 고객 프로필은 제외</span>
      </div>

      {loading && (
        <p className="ai-assistant__loading" role="status">
          최근 AI 작업을 불러오는 중…
        </p>
      )}
      {denied && (
        <SeedNotice title="AI 어시스턴트 권한 없음" tone="warning">
          현재 계정으로는 이 티켓의 AI 기능을 사용할 수 없습니다.
        </SeedNotice>
      )}
      {loadError && (
        <SeedNotice title="AI 작업을 불러오지 못했습니다" tone="danger">
          <p>{loadError}</p>
          <SeedButton onClick={() => void load()} size="compact">
            다시 시도
          </SeedButton>
        </SeedNotice>
      )}

      {!denied && !loadError && (
        <div className="ai-assistant__cards">
          {FEATURES.map((definition) => {
            const job = availableJobs[definition.feature]
            return (
              <AiFeatureCard
                choiceOpen={choiceJobId === job?.jobId}
                definition={definition}
                error={errors[definition.feature]}
                feedback={job ? feedbackByJob[job.jobId] : undefined}
                generationDisabled={
                  loading || generatingFeatures.has(definition.feature)
                }
                insertionBusy={insertingJobId === job?.jobId}
                job={job}
                key={definition.feature}
                onCancelChoice={() => setChoiceJobId(null)}
                onCancelJob={cancel}
                onFeedback={recordFeedback}
                onGenerate={generate}
                onInsert={beginInsert}
                onInsertChoice={completeInsert}
                onRetryGenerate={() =>
                  generate(definition.feature, 'REUSE_OR_CREATE', true)
                }
                retryAvailable={ambiguousCreateFeatures.has(definition.feature)}
              />
            )
          })}
        </div>
      )}

      <p aria-live="polite" className="ai-assistant__announcement">
        {insertMessage}
      </p>
      <p className="ai-assistant__footnote">
        AI 결과에는 오류가 있을 수 있습니다. 보내기 전에 내용과 공개 범위를
        확인하세요.
      </p>
    </section>
  )
}

function AiFeatureCard({
  choiceOpen,
  definition,
  error,
  feedback,
  generationDisabled,
  insertionBusy,
  job,
  onCancelChoice,
  onCancelJob,
  onFeedback,
  onGenerate,
  onInsert,
  onInsertChoice,
  onRetryGenerate,
  retryAvailable,
}: {
  choiceOpen: boolean
  definition: (typeof FEATURES)[number]
  error?: string
  feedback?: AiFeedbackType
  generationDisabled: boolean
  insertionBusy: boolean
  job?: AiJobReceipt
  onCancelChoice: () => void
  onCancelJob: (job: AiJobReceipt) => Promise<void>
  onFeedback: (job: AiJobReceipt, type: AiFeedbackType) => Promise<void>
  onGenerate: (
    feature: AiFeature,
    generationMode: AiGenerationMode,
  ) => Promise<void>
  onInsert: (job: AiJobReceipt) => Promise<void>
  onInsertChoice: (job: AiJobReceipt, strategy: InsertStrategy) => Promise<void>
  onRetryGenerate: () => Promise<void>
  retryAvailable: boolean
}) {
  const active = job ? ACTIVE_STATUSES.has(job.status) : false
  const ready = job?.status === 'SUCCEEDED' && job.result !== null
  const routeDescription = job ? generationRouteDescription(job) : null
  return (
    <article className="ai-assistant-card">
      <div className="ai-assistant-card__heading">
        <span className="ai-assistant-card__icon">
          <SeedIcon name={definition.icon} />
        </span>
        <div>
          <h3>{definition.title}</h3>
          <p>{definition.description}</p>
        </div>
      </div>

      {active && job && (
        <div className="ai-assistant-card__progress" role="status">
          <span className="ai-assistant-card__spinner" aria-hidden="true" />
          <span>{phaseLabel(job.phase)}</span>
          <SeedButton
            onClick={() => void onCancelJob(job)}
            size="compact"
            variant="quiet"
          >
            취소
          </SeedButton>
        </div>
      )}

      {routeDescription && (
        <p className="ai-assistant-card__reuse-status">{routeDescription}</p>
      )}

      {job?.status === 'NEEDS_REVIEW' && (
        <SeedNotice title="검토가 필요한 결과" tone="warning">
          근거 또는 형식 검증을 통과하지 못했습니다. 새로 생성해 주세요.
        </SeedNotice>
      )}
      {job &&
        ['FAILED', 'CANCELLED', 'SUPERSEDED', 'EXPIRED'].includes(
          job.status,
        ) && (
          <SeedNotice
            title={terminalTitle(job.status)}
            tone={job.status === 'FAILED' ? 'danger' : 'info'}
          >
            {terminalDescription(job)}
          </SeedNotice>
        )}
      {ready && job && (
        <AiResultContent result={job.result!} stale={job.stale} />
      )}
      {error && (
        <SeedNotice title="요청을 완료하지 못했습니다" tone="danger">
          <p>{error}</p>
          {retryAvailable && (
            <SeedButton
              disabled={generationDisabled}
              onClick={() => void onRetryGenerate()}
              size="compact"
            >
              같은 요청 다시 시도
            </SeedButton>
          )}
        </SeedNotice>
      )}

      {choiceOpen && job && (
        <div
          className="ai-assistant-card__choice"
          role="group"
          aria-label="기존 PUBLIC 초안 처리"
        >
          <p>PUBLIC 작성기에 기존 초안이 있습니다.</p>
          <div>
            <SeedButton
              disabled={insertionBusy}
              onClick={() => void onInsertChoice(job, 'append')}
              size="compact"
            >
              뒤에 추가
            </SeedButton>
            <SeedButton
              disabled={insertionBusy}
              onClick={() => void onInsertChoice(job, 'replace')}
              size="compact"
              variant="danger"
            >
              교체
            </SeedButton>
            <SeedButton
              disabled={insertionBusy}
              onClick={onCancelChoice}
              size="compact"
              variant="quiet"
            >
              취소
            </SeedButton>
          </div>
        </div>
      )}

      <div className="ai-assistant-card__actions">
        <SeedButton
          disabled={active || generationDisabled || retryAvailable}
          onClick={() => void onGenerate(definition.feature, 'REUSE_OR_CREATE')}
          size="compact"
          variant={job ? 'quiet' : 'primary'}
        >
          {job ? '최근 결과 확인' : '결과 확인/생성'}
        </SeedButton>
        {ready && !job.stale && (
          <SeedButton
            disabled={active || generationDisabled || retryAvailable}
            onClick={() => void onGenerate(definition.feature, 'NEW_CANDIDATE')}
            size="compact"
            variant="quiet"
          >
            다른 초안 생성
          </SeedButton>
        )}
        {ready &&
          job.result?.type === 'ticket.reply_draft' &&
          !job.stale &&
          job.canInsert && (
            <SeedButton
              disabled={insertionBusy}
              onClick={() => void onInsert(job)}
              size="compact"
              variant="primary"
            >
              PUBLIC 작성기에 사용
            </SeedButton>
          )}
      </div>

      {ready && !job.stale && (
        <p className="ai-assistant-card__candidate-limit">
          다른 초안은 같은 입력에서 24시간 동안 최대 2회 요청할 수 있습니다.
        </p>
      )}

      {ready && job && (
        <div
          className="ai-assistant-card__feedback"
          aria-label={`${definition.title} 평가`}
          role="group"
        >
          <span>도움이 되었나요?</span>
          <button
            aria-pressed={feedback === 'helpful'}
            disabled={feedback !== undefined}
            onClick={() => void onFeedback(job, 'helpful')}
            type="button"
          >
            도움됨
          </button>
          <button
            aria-pressed={feedback === 'unhelpful'}
            disabled={feedback !== undefined}
            onClick={() => void onFeedback(job, 'unhelpful')}
            type="button"
          >
            개선 필요
          </button>
        </div>
      )}
    </article>
  )
}

function AiResultContent({
  result,
  stale,
}: {
  result: AiResult
  stale: boolean
}) {
  return (
    <div className="ai-assistant-card__result">
      {stale && (
        <SeedNotice title="티켓 변경 후 생성된 이전 결과" tone="warning">
          참고만 하고, 사용하려면 새로 생성해 주세요.
        </SeedNotice>
      )}
      {result.type === 'ticket.summary' && (
        <>
          <p>{result.problem}</p>
          <ResultList label="시도한 내용" items={result.attemptedActions} />
          <ResultList label="남은 확인" items={result.unresolvedItems} />
          <ResultList label="다음 확인" items={result.nextChecks} />
        </>
      )}
      {result.type === 'ticket.triage' && (
        <>
          <dl className="ai-assistant-card__facts">
            <div>
              <dt>주제</dt>
              <dd>{TOPIC_LABELS[result.topicCode]}</dd>
            </div>
            <div>
              <dt>우선순위</dt>
              <dd>
                {result.suggestedPriority
                  ? PRIORITY_LABELS[result.suggestedPriority]
                  : '제안 없음'}
              </dd>
            </div>
          </dl>
          <ResultList label="제안 근거" items={result.reasons} />
        </>
      )}
      {result.type === 'ticket.reply_draft' && (
        <>
          <div className="ai-assistant-card__answer">
            {result.answer.split(/\n{2,}/).map((paragraph) => (
              <p key={paragraph}>{paragraph}</p>
            ))}
          </div>
          {result.citations.length > 0 && (
            <div className="ai-assistant-card__citations">
              <strong>관련 공개 지식</strong>
              <ul>
                {result.citations.map((citation) => (
                  <li key={citation.chunkId}>
                    <a href={citation.url}>{citation.title}</a>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
    </div>
  )
}

function ResultList({ label, items }: { label: string; items: string[] }) {
  if (items.length === 0) return null
  return (
    <div className="ai-assistant-card__list">
      <strong>{label}</strong>
      <ul>
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
  )
}

function phaseLabel(phase: AiJobReceipt['phase']) {
  return {
    QUEUED: '작업 대기 중…',
    AUTHORIZE: '접근 권한 확인 중…',
    RETRIEVE: '공개 지식 확인 중…',
    GENERATE: 'AI 결과 생성 중…',
    VALIDATE: '근거와 형식 검증 중…',
    COMPLETE: '결과 정리 중…',
  }[phase]
}

function terminalTitle(status: AiJobReceipt['status']) {
  const titles: Partial<Record<AiJobReceipt['status'], string>> = {
    FAILED: '생성 실패',
    CANCELLED: '작업 취소됨',
    SUPERSEDED: '더 최신 작업이 있음',
    EXPIRED: '결과 만료됨',
  }
  return titles[status] ?? '작업 종료'
}

function terminalDescription(job: AiJobReceipt) {
  if (job.status === 'FAILED' && job.errorCode === 'BUDGET_EXCEEDED')
    return '현재 AI 사용 한도에 도달했습니다. 한도가 갱신된 뒤 다시 시도해 주세요.'
  if (job.status === 'FAILED')
    return '결과를 생성하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  if (job.status === 'CANCELLED') return '요청한 AI 작업을 취소했습니다.'
  if (job.status === 'SUPERSEDED')
    return '같은 기능의 더 최신 작업을 확인해 주세요.'
  return '보존 기간이 지나 결과를 사용할 수 없습니다. 새로 생성해 주세요.'
}

function messageForError(cause: unknown) {
  if (cause instanceof AiResultUnavailableError) {
    return '완료된 AI 결과를 불러오지 못했습니다. 새로 생성해 주세요.'
  }
  if (cause instanceof ApiError) {
    if (cause.status === 409)
      return '티켓이 변경되었습니다. 최신 정보를 확인한 뒤 다시 시도해 주세요.'
    if (cause.status === 429)
      return '요청이 많습니다. 잠시 후 다시 시도해 주세요.'
    if (cause.status === 403)
      return '현재 계정에는 이 AI 기능을 사용할 권한이 없습니다.'
    return cause.requestId
      ? `${cause.message} (요청 ID: ${cause.requestId})`
      : cause.message
  }
  return 'AI 요청을 처리하지 못했습니다. 다시 시도해 주세요.'
}

function messageForCreateError(
  cause: unknown,
  generationMode: AiGenerationMode,
) {
  if (isAmbiguousCreateOutcome(cause)) {
    return '요청 결과를 확인할 수 없습니다. 같은 요청 다시 시도로 중복 없이 확인해 주세요.'
  }
  if (cause instanceof ApiError && cause.status === 429) {
    const subject =
      generationMode === 'NEW_CANDIDATE'
        ? '다른 초안 요청 한도 또는 AI 사용 한도'
        : 'AI 요청 한도'
    return `${subject}에 도달했습니다.${retryAfterMessage(cause.retryAfter)}`
  }
  return messageForError(cause)
}

function isAmbiguousCreateOutcome(cause: unknown) {
  if (!(cause instanceof ApiError)) return true
  return cause.status >= 500 || (cause.status >= 200 && cause.status < 300)
}

function retryAfterMessage(value: string | undefined) {
  if (!value || !/^\d+$/.test(value)) return ' 잠시 후 다시 시도해 주세요.'
  const seconds = Number(value)
  if (!Number.isSafeInteger(seconds) || seconds < 1 || seconds > 86_400) {
    return ' 잠시 후 다시 시도해 주세요.'
  }
  if (seconds >= 3_600)
    return ` 약 ${Math.ceil(seconds / 3_600)}시간 후 다시 시도해 주세요.`
  if (seconds >= 60)
    return ` 약 ${Math.ceil(seconds / 60)}분 후 다시 시도해 주세요.`
  return ` ${seconds}초 후 다시 시도해 주세요.`
}

function generationRouteDescription(job: AiJobReceipt) {
  if (
    job.generationMode === 'REUSE_OR_CREATE' &&
    ACTIVE_STATUSES.has(job.status)
  ) {
    return '최근 결과를 확인하거나 같은 입력의 진행 중인 작업을 기다리고 있습니다.'
  }
  if (job.reuseKind === 'CACHE_HIT')
    return '현재 입력과 일치하는 최근 결과를 사용했습니다.'
  if (job.reuseKind === 'COALESCED') {
    return ACTIVE_STATUSES.has(job.status)
      ? '같은 입력의 진행 중인 작업을 기다리고 있습니다.'
      : '같은 입력의 진행 중인 작업 결과를 사용했습니다.'
  }
  if (job.reuseKind === 'GENERATED') {
    return job.generationMode === 'NEW_CANDIDATE'
      ? '다른 초안을 새 후보로 생성했습니다.'
      : '현재 입력에 맞는 새 결과를 생성했습니다.'
  }
  return null
}

function withoutFeature(current: Set<AiFeature>, feature: AiFeature) {
  const next = new Set(current)
  next.delete(feature)
  return next
}

function latestSnapshot(current: {
  composerMode: TicketVisibility
  publicDraft: AiPublicDraftSnapshot
  ticketNumber: number
  ticketVersion: number
}) {
  return {
    ...current,
    publicDraft: {
      body: current.publicDraft.body,
      document: structuredClone(current.publicDraft.document),
      attachmentIds: [...current.publicDraft.attachmentIds],
    },
  }
}

export function draftFingerprint(draft: AiPublicDraftSnapshot) {
  return JSON.stringify({
    body: draft.body,
    document: draft.document,
    attachmentIds: draft.attachmentIds,
  })
}

function hasDraft(draft: AiPublicDraftSnapshot) {
  return (
    draft.body.trim().length > 0 ||
    draft.attachmentIds.length > 0 ||
    draft.document.content.some((node) => node.type === 'attachmentImage')
  )
}
