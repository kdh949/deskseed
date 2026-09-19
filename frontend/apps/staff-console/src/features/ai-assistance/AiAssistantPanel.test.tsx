import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { plainTextDocument } from '../../api/types'
import {
  AiAssistantPanel,
  type AiAssistantClient,
  type AiPublicDraftSnapshot,
} from './AiAssistantPanel'
import type { AiFeature, AiJobReceipt, AiResult } from './api'

const emptyDraft: AiPublicDraftSnapshot = {
  body: '',
  document: plainTextDocument(''),
  attachmentIds: [],
}

const summary: AiResult = {
  type: 'ticket.summary',
  problem: '결제 상태를 확인하고 있습니다.',
  attemptedActions: [],
  unresolvedItems: [],
  nextChecks: [],
}

const reply: AiResult = {
  type: 'ticket.reply_draft',
  answer: '확인 후 안내드리겠습니다.',
  citations: [],
}

const rewrite: AiResult = {
  type: 'ticket.reply_rewrite',
  answer: '확인 후 정식으로 안내드리겠습니다.',
  citations: [
    {
      articleId: '55555555-5555-4555-8555-555555555555',
      revisionId: '66666666-6666-4666-8666-666666666666',
      chunkId: '77777777-7777-4777-8777-777777777777',
      title: '공개 안내',
      url: '/help/articles/public-guide',
    },
  ],
  language: 'ko',
  tone: 'formal',
  length: 'concise',
}

function receipt(
  feature: AiFeature,
  overrides: Partial<AiJobReceipt> = {},
): AiJobReceipt {
  return {
    jobId:
      feature === 'ticket.reply_draft'
        ? '33333333-3333-4333-8333-333333333333'
        : feature === 'ticket.reply_rewrite'
          ? '88888888-8888-4888-8888-888888888888'
          : '11111111-1111-4111-8111-111111111111',
    feature,
    status: 'SUCCEEDED',
    phase: 'COMPLETE',
    requestRevision: 7,
    createdAt: '2026-09-17T00:00:00Z',
    deadlineAt: '2026-09-17T00:05:00Z',
    completedAt: '2026-09-17T00:00:04Z',
    resultExpiresAt: '2026-09-24T00:00:04Z',
    pollAfterMs: 100,
    cancelRequested: false,
    contextRevision: 'a'.repeat(64),
    contextPolicyVersion: 'public-comments-v1',
    inputScope:
      feature === 'ticket.reply_rewrite' ? 'PUBLIC_DRAFT_ONLY' : 'PUBLIC_ONLY',
    stale: false,
    canInsert:
      feature === 'ticket.reply_draft' || feature === 'ticket.reply_rewrite',
    errorCode: null,
    result:
      feature === 'ticket.reply_draft'
        ? reply
        : feature === 'ticket.reply_rewrite'
          ? rewrite
          : summary,
    ...(feature === 'ticket.reply_draft' || feature === 'ticket.reply_rewrite'
      ? { candidateId: '44444444-4444-4444-8444-444444444444' }
      : {}),
    ...(feature === 'ticket.reply_rewrite'
      ? {
          sourceJobId: '33333333-3333-4333-8333-333333333333',
        }
      : {}),
    ...overrides,
  }
}

function client(overrides: Partial<AiAssistantClient> = {}): AiAssistantClient {
  return {
    list: vi.fn(async () => ({ items: [] })),
    get: vi.fn(async () => receipt('ticket.summary')),
    create: vi.fn(async (_ticketNumber, _version, feature) => receipt(feature)),
    cancel: vi.fn(async () =>
      receipt('ticket.summary', { status: 'CANCELLED' }),
    ),
    feedback: vi.fn(async (_ticketNumber, jobId, type) => ({
      jobId,
      type,
      sourceRevision: 'a'.repeat(64),
      replayed: false,
      recordedAt: '2026-09-17T00:00:05Z',
    })),
    ...overrides,
  }
}

function panel(
  aiClient: AiAssistantClient,
  publicDraft: AiPublicDraftSnapshot = emptyDraft,
  onInsertReply = vi.fn(),
  ticketNumber = 3001,
) {
  return (
    <AiAssistantPanel
      client={aiClient}
      composerMode="PUBLIC"
      onInsertReply={onInsertReply}
      publicDraft={publicDraft}
      ticketNumber={ticketNumber}
      ticketVersion={7}
    />
  )
}

afterEach(() => {
  vi.useRealTimers()
})

describe('AiAssistantPanel', () => {
  it('keeps polling after more than three transient failures', async () => {
    vi.useFakeTimers()
    const running = receipt('ticket.summary', {
      status: 'RUNNING',
      phase: 'GENERATE',
      completedAt: null,
      resultExpiresAt: null,
      result: null,
    })
    const get = vi
      .fn()
      .mockRejectedValueOnce(new Error('temporary'))
      .mockRejectedValueOnce(new Error('temporary'))
      .mockRejectedValueOnce(new Error('temporary'))
      .mockRejectedValueOnce(new Error('temporary'))
      .mockResolvedValue(receipt('ticket.summary'))
    render(
      panel(
        client({
          list: vi.fn(async () => ({ items: [running] })),
          get,
        }),
      ),
    )

    await act(async () => await Promise.resolve())
    expect(screen.getByText('AI 결과 생성 중…')).toBeVisible()
    for (const delay of [100, 200, 400, 800, 1_600]) {
      await act(async () => await vi.advanceTimersByTimeAsync(delay))
    }

    expect(get).toHaveBeenCalledTimes(5)
    expect(screen.getByText('결제 상태를 확인하고 있습니다.')).toBeVisible()
  })

  it('allows only one create request per feature while it is in flight', async () => {
    let resolveCreate: ((job: AiJobReceipt) => void) | undefined
    const create = vi.fn(
      () =>
        new Promise<AiJobReceipt>((resolve) => {
          resolveCreate = resolve
        }),
    )
    render(panel(client({ create })))
    const button = (
      await screen.findAllByRole('button', {
        name: '결과 확인/생성',
      })
    )[0]!
    await waitFor(() => expect(button).toBeEnabled())

    fireEvent.click(button)
    fireEvent.click(button)

    expect(create).toHaveBeenCalledTimes(1)
    await act(async () => resolveCreate?.(receipt('ticket.summary')))
  })

  it('disables generation until the initial list is fully loaded', async () => {
    let resolveList: ((page: { items: AiJobReceipt[] }) => void) | undefined
    const list = vi.fn(
      () =>
        new Promise<{ items: AiJobReceipt[] }>((resolve) => {
          resolveList = resolve
        }),
    )
    render(panel(client({ list })))

    expect(
      screen.getAllByRole('button', { name: '결과 확인/생성' })[0],
    ).toBeDisabled()
    await act(async () => resolveList?.({ items: [] }))
    await waitFor(() =>
      expect(
        screen.getAllByRole('button', { name: '결과 확인/생성' })[0],
      ).toBeEnabled(),
    )
  })

  it('uses explicit reuse intent for the primary action', async () => {
    const create = vi.fn(
      async (_ticketNumber: number, _version: number, feature: AiFeature) =>
        receipt(feature),
    )
    render(panel(client({ create })))

    fireEvent.click(
      (
        await screen.findAllByRole('button', {
          name: '결과 확인/생성',
        })
      )[0]!,
    )

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create).toHaveBeenCalledWith(
      3001,
      7,
      'ticket.summary',
      'REUSE_OR_CREATE',
      expect.stringMatching(/^[0-9a-f-]{36}$/),
    )
  })

  it('creates a distinct candidate only from the secondary action', async () => {
    const ready = receipt('ticket.summary')
    const create = vi.fn(
      async (_ticketNumber: number, _version: number, feature: AiFeature) =>
        receipt(feature, {
          generationMode: 'NEW_CANDIDATE',
          candidateSequence: 1,
          reuseKind: 'GENERATED',
        }),
    )
    render(
      panel(
        client({
          list: vi.fn(async () => ({ items: [ready] })),
          create,
        }),
      ),
    )

    fireEvent.click(
      await screen.findByRole('button', { name: '다른 초안 생성' }),
    )

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create).toHaveBeenCalledWith(
      3001,
      7,
      'ticket.summary',
      'NEW_CANDIDATE',
      expect.stringMatching(/^[0-9a-f-]{36}$/),
    )
    expect(
      await screen.findByText('다른 초안을 새 후보로 생성했습니다.'),
    ).toBeVisible()
  })

  it('retries an ambiguous create with the exact command identity', async () => {
    const create = vi
      .fn()
      .mockRejectedValueOnce(new Error('network lost'))
      .mockResolvedValueOnce(
        receipt('ticket.summary', {
          generationMode: 'REUSE_OR_CREATE',
          reuseKind: 'GENERATED',
        }),
      )
    render(panel(client({ create })))

    fireEvent.click(
      (
        await screen.findAllByRole('button', {
          name: '결과 확인/생성',
        })
      )[0]!,
    )
    fireEvent.click(
      await screen.findByRole('button', { name: '같은 요청 다시 시도' }),
    )

    await waitFor(() => expect(create).toHaveBeenCalledTimes(2))
    expect(create.mock.calls[1]).toEqual(create.mock.calls[0])
    expect(
      await screen.findByText('현재 입력에 맞는 새 결과를 생성했습니다.'),
    ).toBeVisible()
  })

  it('keeps the current result and shows bounded retry guidance on 429', async () => {
    const ready = receipt('ticket.summary')
    const create = vi.fn(async () => {
      throw new ApiError('rate limited', 429, undefined, undefined, '3600')
    })
    render(
      panel(
        client({
          list: vi.fn(async () => ({ items: [ready] })),
          create,
        }),
      ),
    )

    fireEvent.click(
      await screen.findByRole('button', { name: '다른 초안 생성' }),
    )

    expect(
      await screen.findByText(
        '다른 초안 요청 한도 또는 AI 사용 한도에 도달했습니다. 약 1시간 후 다시 시도해 주세요.',
      ),
    ).toBeVisible()
    expect(screen.getByText('결제 상태를 확인하고 있습니다.')).toBeVisible()
    expect(
      screen.queryByRole('button', { name: '같은 요청 다시 시도' }),
    ).not.toBeInTheDocument()
  })

  it('creates a source-bound rewrite with the selected closed options', async () => {
    const source = receipt('ticket.reply_draft')
    const create = vi.fn(
      async (
        _ticketNumber: number,
        _version: number,
        feature: AiFeature,
        _generationMode: string,
        _idempotencyKey: string,
        request?: {
          sourceJobId: string
          options: {
            language: 'ko'
            tone: 'calm' | 'formal'
            length: 'concise' | 'standard'
          }
        },
      ) =>
        receipt(feature, {
          sourceJobId: request?.sourceJobId,
          result: { ...rewrite, ...request?.options },
          generationMode: 'REUSE_OR_CREATE',
          reuseKind: 'GENERATED',
        }),
    )
    render(
      panel(
        client({
          list: vi.fn(async () => ({ items: [source] })),
          create,
        }),
      ),
    )

    fireEvent.change(
      await screen.findByRole('combobox', { name: '재작성 문체' }),
      { target: { value: 'formal' } },
    )
    fireEvent.change(screen.getByRole('combobox', { name: '재작성 길이' }), {
      target: { value: 'concise' },
    })
    fireEvent.click(screen.getByRole('button', { name: '문체·길이 재작성' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create).toHaveBeenCalledWith(
      3001,
      7,
      'ticket.reply_rewrite',
      'REUSE_OR_CREATE',
      expect.stringMatching(/^[0-9a-f-]{36}$/),
      {
        sourceJobId: source.jobId,
        options: { language: 'ko', tone: 'formal', length: 'concise' },
      },
    )
    expect(await screen.findByText('재작성 결과')).toBeVisible()
    expect(screen.getByText('격식 있게 · 간결하게')).toBeVisible()
    expect(screen.getByText('확인 후 안내드리겠습니다.')).toBeVisible()
  })

  it('keeps the original reply and exact rewrite command for ambiguous retry', async () => {
    const source = receipt('ticket.reply_draft')
    const create = vi
      .fn()
      .mockRejectedValueOnce(new Error('network lost'))
      .mockResolvedValueOnce(
        receipt('ticket.reply_rewrite', {
          generationMode: 'REUSE_OR_CREATE',
          reuseKind: 'GENERATED',
        }),
      )
    render(
      panel(
        client({
          list: vi.fn(async () => ({ items: [source] })),
          create,
        }),
      ),
    )

    fireEvent.click(
      await screen.findByRole('button', { name: '문체·길이 재작성' }),
    )
    fireEvent.click(
      await screen.findByRole('button', {
        name: '같은 재작성 요청 다시 시도',
      }),
    )

    await waitFor(() => expect(create).toHaveBeenCalledTimes(2))
    expect(create.mock.calls[1]).toEqual(create.mock.calls[0])
    expect(screen.getByText('확인 후 안내드리겠습니다.')).toBeVisible()
    expect(await screen.findByText('재작성 결과')).toBeVisible()
  })

  it('inserts a validated rewrite through the existing PUBLIC lineage path', async () => {
    const source = receipt('ticket.reply_draft')
    const rewritten = receipt('ticket.reply_rewrite')
    const onInsertReply = vi.fn()
    render(
      panel(
        client({
          list: vi.fn(async () => ({ items: [source, rewritten] })),
          get: vi.fn(async (_ticketNumber, jobId) =>
            jobId === rewritten.jobId ? rewritten : source,
          ),
        }),
        emptyDraft,
        onInsertReply,
      ),
    )

    fireEvent.click(
      await screen.findByRole('button', {
        name: '재작성 결과를 PUBLIC 작성기에 사용',
      }),
    )

    await waitFor(() => expect(onInsertReply).toHaveBeenCalledTimes(1))
    expect(onInsertReply).toHaveBeenCalledWith(
      '확인 후 정식으로 안내드리겠습니다.',
      'replace',
      emptyDraft,
      {
        jobId: rewritten.jobId,
        candidateId: rewritten.candidateId,
        originalAnswer: '확인 후 정식으로 안내드리겠습니다.',
      },
    )
  })

  it('does not offer rewrite controls for an unusable source reply', async () => {
    render(
      panel(
        client({
          list: vi.fn(async () => ({
            items: [
              receipt('ticket.reply_draft', {
                stale: true,
                canInsert: false,
              }),
            ],
          })),
        }),
      ),
    )

    await screen.findByText('확인 후 안내드리겠습니다.')
    expect(
      screen.queryByRole('button', { name: '문체·길이 재작성' }),
    ).not.toBeInTheDocument()
  })

  it('clears source and rewrite state before loading another ticket', async () => {
    let resolveFirstList:
      ((page: { items: AiJobReceipt[] }) => void) | undefined
    let resolveSecondList:
      ((page: { items: AiJobReceipt[] }) => void) | undefined
    const list = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<{ items: AiJobReceipt[] }>((resolve) => {
            resolveFirstList = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<{ items: AiJobReceipt[] }>((resolve) => {
            resolveSecondList = resolve
          }),
      )
    const aiClient = client({ list })
    const view = render(panel(aiClient))

    await waitFor(() => expect(list).toHaveBeenCalledTimes(1))
    view.rerender(panel(aiClient, emptyDraft, vi.fn(), 3002))
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))

    expect(
      screen.queryByRole('button', { name: '문체·길이 재작성' }),
    ).not.toBeInTheDocument()
    expect(screen.getByText('최근 AI 작업을 불러오는 중…')).toBeVisible()
    await act(async () =>
      resolveFirstList?.({ items: [receipt('ticket.reply_draft')] }),
    )
    expect(
      screen.queryByRole('button', { name: '문체·길이 재작성' }),
    ).not.toBeInTheDocument()
    expect(screen.getByText('최근 AI 작업을 불러오는 중…')).toBeVisible()
    await act(async () => resolveSecondList?.({ items: [] }))
  })

  it('rejects insertion when rich content changes during revalidation', async () => {
    const ready = receipt('ticket.reply_draft')
    let resolveGet: ((job: AiJobReceipt) => void) | undefined
    const get = vi.fn(
      () =>
        new Promise<AiJobReceipt>((resolve) => {
          resolveGet = resolve
        }),
    )
    const aiClient = client({
      list: vi.fn(async () => ({ items: [ready] })),
      get,
    })
    const onInsertReply = vi.fn()
    const view = render(panel(aiClient, emptyDraft, onInsertReply))
    const insert = await screen.findByRole('button', {
      name: 'PUBLIC 작성기에 사용',
    })

    fireEvent.click(insert)
    view.rerender(
      panel(
        aiClient,
        {
          body: '',
          document: {
            type: 'doc',
            content: [
              {
                type: 'attachmentImage',
                attrs: {
                  attachmentId: '77777777-7777-4777-8777-777777777777',
                },
              },
            ],
          },
          attachmentIds: ['77777777-7777-4777-8777-777777777777'],
        },
        onInsertReply,
      ),
    )
    await act(async () => resolveGet?.(ready))

    expect(onInsertReply).not.toHaveBeenCalled()
    expect(
      screen.getByText(
        '검증 중 티켓이나 작성기가 변경되었습니다. 다시 시도해 주세요.',
      ),
    ).toBeVisible()
  })

  it('rejects a legacy reply that has no server-owned candidate identity', async () => {
    const legacy = receipt('ticket.reply_draft', { candidateId: undefined })
    const onInsertReply = vi.fn()
    render(
      panel(
        client({
          list: vi.fn(async () => ({ items: [legacy] })),
          get: vi.fn(async () => legacy),
        }),
        emptyDraft,
        onInsertReply,
      ),
    )

    fireEvent.click(
      await screen.findByRole('button', { name: 'PUBLIC 작성기에 사용' }),
    )

    expect(
      await screen.findByText(
        '현재 티켓과 일치하는 사용 가능한 초안이 아닙니다. 새로 생성해 주세요.',
      ),
    ).toBeVisible()
    expect(onInsertReply).not.toHaveBeenCalled()
  })
})
