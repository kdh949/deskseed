import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { useMemo, useState, type FormEvent, type ReactNode } from 'react'
import {
  ApiError,
  getOutboundMailIntent,
  getOutboundMailSummary,
  listOutboundMailIntents,
  retryOutboundMailIntent,
} from '../../api/client'
import type {
  OutboundMailIntent,
  OutboundMailIntentStatus,
} from '../../api/types'
import {
  DsButton,
  Notification,
  RetryButton,
  ScreenState,
} from '../../design-system'

const STATUS_OPTIONS: Array<{
  label: string
  value: OutboundMailIntentStatus | ''
}> = [
  { label: '모든 상태', value: '' },
  { label: '대기', value: 'QUEUED' },
  { label: '전송 중', value: 'SENDING' },
  { label: '재시도 대기', value: 'RETRY_WAIT' },
  { label: '전송 완료', value: 'SENT' },
  { label: '실패', value: 'FAILED' },
]

const STATUS_LABEL: Record<OutboundMailIntentStatus, string> = {
  QUEUED: '대기',
  SENDING: '전송 중',
  RETRY_WAIT: '재시도 대기',
  SENT: '전송 완료',
  FAILED: '실패',
}

const TEMPLATE_LABEL: Record<OutboundMailIntent['template'], string> = {
  CUSTOMER_MAGIC_LINK: '고객 로그인 링크',
  REQUEST_RECEIVED: '문의 접수 확인',
  PUBLIC_AGENT_REPLY: '상담사 공개 답변',
}

export function AdminMailPage() {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<OutboundMailIntentStatus | ''>('')
  const [selectedIntentId, setSelectedIntentId] = useState<string | null>(null)
  const [retryReason, setRetryReason] = useState('')
  const [retryOpen, setRetryOpen] = useState(false)
  const [validationError, setValidationError] = useState<string | null>(null)

  const summaryQuery = useQuery({
    queryKey: ['admin-mail-summary'],
    queryFn: getOutboundMailSummary,
    retry: false,
  })
  const intentsQuery = useInfiniteQuery({
    queryKey: ['admin-mail-intents', status],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      listOutboundMailIntents({
        cursor: pageParam,
        ...(status ? { status } : {}),
      }),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    retry: false,
  })
  const detailQuery = useQuery({
    queryKey: ['admin-mail-intent', selectedIntentId],
    queryFn: () => getOutboundMailIntent(selectedIntentId!),
    enabled: selectedIntentId !== null,
    retry: false,
  })
  const retryMutation = useMutation({
    mutationFn: ({ intentId, reason }: { intentId: string; reason: string }) =>
      retryOutboundMailIntent(intentId, reason),
    onSuccess: async () => {
      setRetryReason('')
      setRetryOpen(false)
      setValidationError(null)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['admin-mail-summary'] }),
        queryClient.invalidateQueries({ queryKey: ['admin-mail-intents'] }),
        queryClient.invalidateQueries({ queryKey: ['admin-mail-intent'] }),
      ])
    },
  })

  const items = useMemo(
    () => intentsQuery.data?.pages.flatMap((page) => page.items) ?? [],
    [intentsQuery.data],
  )
  const selectedIntent = detailQuery.data ?? null
  const loadError = summaryQuery.error ?? intentsQuery.error

  if (summaryQuery.isPending || intentsQuery.isPending) {
    return (
      <AdminMailScreenState
        kind="loading"
        title="메일 운영 상태를 불러오는 중"
      />
    )
  }
  if (loadError) {
    const denied = loadError instanceof ApiError && loadError.status === 403
    return (
      <AdminMailScreenState
        action={
          denied ? undefined : (
            <RetryButton
              onClick={() => {
                void summaryQuery.refetch()
                void intentsQuery.refetch()
              }}
            />
          )
        }
        description={
          denied
            ? '이 화면은 ADMIN 역할의 운영자만 열 수 있습니다.'
            : '안전한 운영 projection을 다시 요청해 주세요.'
        }
        kind={denied ? 'denied' : 'error'}
        title={
          denied
            ? '메일 운영 권한이 없습니다.'
            : '메일 운영 상태를 불러오지 못했습니다.'
        }
      />
    )
  }

  const summary = summaryQuery.data!
  const submitRetry = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const reason = retryReason.trim()
    if (!reason) {
      setValidationError('재시도 사유를 입력해 주세요.')
      return
    }
    if (!selectedIntent || selectedIntent.status !== 'FAILED') return
    setValidationError(null)
    retryMutation.mutate({ intentId: selectedIntent.id, reason })
  }

  return (
    <main aria-label="메일 운영" className="admin-page">
      <header className="admin-page-header">
        <div>
          <h1>메일 운영</h1>
          <p>
            전송 본문, token, 원본 수신자와 provider 응답은 이 화면에서 조회할
            수 없습니다.
          </p>
        </div>
        <DsButton
          disabled={summaryQuery.isFetching || intentsQuery.isFetching}
          onClick={() => {
            void summaryQuery.refetch()
            void intentsQuery.refetch()
          }}
          tone="secondary"
        >
          운영 상태 새로고침
        </DsButton>
      </header>

      <section aria-labelledby="mail-summary-heading" className="admin-surface">
        <h2 id="mail-summary-heading">전송 운영 상태</h2>
        <dl className="admin-definition-list">
          <div>
            <dt>전송 활성화</dt>
            <dd>{summary.deliveryEnabled ? '활성' : '비활성'}</dd>
          </div>
          <div>
            <dt>스케줄러</dt>
            <dd>{summary.schedulingEnabled ? '활성' : '비활성'}</dd>
          </div>
          <div>
            <dt>전송 방식</dt>
            <dd>{summary.transport}</dd>
          </div>
          <div>
            <dt>대기 / 전송 중</dt>
            <dd>{`${summary.queuedCount} / ${summary.sendingCount}`}</dd>
          </div>
          <div>
            <dt>재시도 대기 / 실패</dt>
            <dd>{`${summary.retryWaitCount} / ${summary.failedCount}`}</dd>
          </div>
          <div>
            <dt>완료</dt>
            <dd>{summary.sentCount}</dd>
          </div>
          <div>
            <dt>가장 오래된 대기</dt>
            <dd>{formatTimestamp(summary.oldestPendingAt)}</dd>
          </div>
        </dl>
      </section>

      <section aria-labelledby="mail-intents-heading" className="admin-surface">
        <div className="admin-page-header">
          <div>
            <h2 id="mail-intents-heading">전송 의도</h2>
            <p>마스킹된 수신자와 안전한 상태/시도 메타데이터만 표시합니다.</p>
          </div>
          <label className="admin-field">
            <span>상태 필터</span>
            <select
              aria-label="메일 전송 상태 필터"
              onChange={(event) => {
                setStatus(event.target.value as OutboundMailIntentStatus | '')
                setSelectedIntentId(null)
                setRetryOpen(false)
                retryMutation.reset()
              }}
              value={status}
            >
              {STATUS_OPTIONS.map((option) => (
                <option key={option.value || 'all'} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
        </div>
        {items.length === 0 ? (
          <ScreenState
            compact
            description="현재 필터에 해당하는 안전한 전송 운영 기록이 없습니다."
            kind="empty"
            title="표시할 전송 의도가 없습니다."
          />
        ) : (
          <div className="admin-table-wrap">
            <table className="admin-table">
              <caption className="sr-only">마스킹된 전송 의도 목록</caption>
              <thead>
                <tr>
                  <th scope="col">종류</th>
                  <th scope="col">수신자</th>
                  <th scope="col">상태</th>
                  <th scope="col">시도</th>
                  <th scope="col">안전 오류 코드</th>
                  <th scope="col">대기 시각</th>
                  <th scope="col">작업</th>
                </tr>
              </thead>
              <tbody>
                {items.map((intent) => (
                  <tr key={intent.id}>
                    <td>{TEMPLATE_LABEL[intent.template]}</td>
                    <td>{intent.recipientMasked}</td>
                    <td>{STATUS_LABEL[intent.status]}</td>
                    <td>{`${intent.attemptCount}/${intent.maxAttempts}`}</td>
                    <td>{intent.lastErrorCode ?? '—'}</td>
                    <td>{formatTimestamp(intent.queuedAt)}</td>
                    <td>
                      <DsButton
                        aria-expanded={selectedIntentId === intent.id}
                        onClick={() => {
                          setSelectedIntentId(intent.id)
                          setRetryOpen(false)
                          setRetryReason('')
                          setValidationError(null)
                          retryMutation.reset()
                        }}
                        tone="secondary"
                      >
                        운영 상세 보기
                      </DsButton>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {intentsQuery.hasNextPage ? (
          <div className="admin-inline-actions">
            <DsButton
              disabled={intentsQuery.isFetchingNextPage}
              onClick={() => void intentsQuery.fetchNextPage()}
              tone="secondary"
            >
              {intentsQuery.isFetchingNextPage
                ? '더 불러오는 중…'
                : '전송 의도 더 보기'}
            </DsButton>
          </div>
        ) : null}
      </section>

      {selectedIntentId ? (
        <section
          aria-labelledby="mail-intent-detail-heading"
          className="admin-surface"
        >
          {detailQuery.isPending ? (
            <ScreenState
              compact
              kind="loading"
              title="전송 운영 상세를 불러오는 중"
            />
          ) : detailQuery.isError ? (
            <Notification
              title="전송 운영 상세를 불러오지 못했습니다."
              tone="danger"
            >
              <p>안전한 상태 정보를 다시 요청해 주세요.</p>
            </Notification>
          ) : selectedIntent ? (
            <>
              <div className="admin-page-header">
                <div>
                  <h2 id="mail-intent-detail-heading">전송 운영 상세</h2>
                  <p>{`${TEMPLATE_LABEL[selectedIntent.template]} · ${STATUS_LABEL[selectedIntent.status]}`}</p>
                </div>
                {selectedIntent.status === 'FAILED' ? (
                  <DsButton
                    aria-expanded={retryOpen}
                    onClick={() => {
                      retryMutation.reset()
                      setRetryOpen((open) => !open)
                    }}
                    tone="primary"
                  >
                    실패 메일 재시도
                  </DsButton>
                ) : null}
              </div>
              {retryMutation.isSuccess ? (
                <Notification
                  title="메일 재시도 요청을 등록했습니다."
                  tone="success"
                >
                  <p>서버가 같은 전송 의도를 대기 상태로 전환했습니다.</p>
                </Notification>
              ) : null}
              <dl className="admin-definition-list">
                <div>
                  <dt>마스킹된 수신자</dt>
                  <dd>{selectedIntent.recipientMasked}</dd>
                </div>
                <div>
                  <dt>템플릿 버전</dt>
                  <dd>{selectedIntent.templateVersion}</dd>
                </div>
                <div>
                  <dt>재시도 cycle</dt>
                  <dd>{selectedIntent.retryCycle}</dd>
                </div>
                <div>
                  <dt>수동 재시도 횟수</dt>
                  <dd>{selectedIntent.manualRetryCount}</dd>
                </div>
                <div>
                  <dt>다음 시도</dt>
                  <dd>{formatTimestamp(selectedIntent.nextAttemptAt)}</dd>
                </div>
                <div>
                  <dt>최종 안전 오류 코드</dt>
                  <dd>{selectedIntent.lastErrorCode ?? '—'}</dd>
                </div>
              </dl>
              <MailAttempts intent={selectedIntent} />
              {retryOpen && selectedIntent.status === 'FAILED' ? (
                <form className="admin-form" onSubmit={submitRetry}>
                  <label className="admin-field" htmlFor="mail-retry-reason">
                    <span>재시도 사유</span>
                    <textarea
                      id="mail-retry-reason"
                      maxLength={500}
                      onChange={(event) => setRetryReason(event.target.value)}
                      required
                      rows={3}
                      value={retryReason}
                    />
                  </label>
                  <p className="admin-muted">
                    재시도는 같은 전송 의도를 다시 대기 상태로 전환하며, 새 메일
                    의도를 만들지 않습니다.
                  </p>
                  {validationError ? (
                    <Notification title={validationError} tone="warning" />
                  ) : null}
                  {retryMutation.isError ? (
                    <Notification
                      title={
                        retryMutation.error instanceof ApiError &&
                        retryMutation.error.status === 409
                          ? '다른 운영자가 이미 이 전송 의도를 변경했습니다.'
                          : '메일 재시도 요청을 처리하지 못했습니다.'
                      }
                      tone={
                        retryMutation.error instanceof ApiError &&
                        retryMutation.error.status === 409
                          ? 'conflict'
                          : 'danger'
                      }
                    >
                      <p>
                        {retryMutation.error instanceof ApiError &&
                        retryMutation.error.status === 409
                          ? '입력한 사유는 유지됩니다. 최신 상태를 확인한 뒤 다시 결정해 주세요.'
                          : '입력한 사유는 유지됩니다. 잠시 후 다시 시도해 주세요.'}
                      </p>
                    </Notification>
                  ) : null}
                  <div className="admin-form-actions">
                    <DsButton
                      disabled={retryMutation.isPending}
                      tone="primary"
                      type="submit"
                    >
                      {retryMutation.isPending
                        ? '재시도 요청 중…'
                        : '사유와 함께 재시도'}
                    </DsButton>
                    <DsButton
                      disabled={retryMutation.isPending}
                      onClick={() => {
                        setRetryOpen(false)
                        setValidationError(null)
                      }}
                      tone="secondary"
                      type="button"
                    >
                      취소
                    </DsButton>
                  </div>
                </form>
              ) : null}
            </>
          ) : null}
        </section>
      ) : null}
    </main>
  )
}

function MailAttempts({ intent }: { intent: OutboundMailIntent }) {
  return (
    <section aria-labelledby="mail-attempts-heading" className="admin-surface">
      <h3 id="mail-attempts-heading">시도 이력</h3>
      {intent.attempts.length === 0 ? (
        <p className="admin-muted">표시할 안전한 시도 이력이 없습니다.</p>
      ) : (
        <div className="admin-table-wrap">
          <table className="admin-table">
            <caption className="sr-only">전송 시도 이력</caption>
            <thead>
              <tr>
                <th scope="col">시도</th>
                <th scope="col">cycle</th>
                <th scope="col">상태</th>
                <th scope="col">안전 오류 코드</th>
                <th scope="col">시작</th>
                <th scope="col">종료</th>
              </tr>
            </thead>
            <tbody>
              {intent.attempts.map((attempt) => (
                <tr key={attempt.attemptNumber}>
                  <td>{attempt.attemptNumber}</td>
                  <td>{attempt.retryCycle}</td>
                  <td>{attempt.status}</td>
                  <td>{attempt.failureCode ?? '—'}</td>
                  <td>{formatTimestamp(attempt.startedAt)}</td>
                  <td>{formatTimestamp(attempt.finishedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function AdminMailScreenState({
  action,
  description,
  kind,
  title,
}: {
  action?: ReactNode
  description?: string
  kind: 'denied' | 'error' | 'loading'
  title: string
}) {
  return (
    <main className="admin-page">
      <ScreenState
        action={action}
        description={description}
        kind={kind}
        title={title}
      />
    </main>
  )
}

function formatTimestamp(value: string | null) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
