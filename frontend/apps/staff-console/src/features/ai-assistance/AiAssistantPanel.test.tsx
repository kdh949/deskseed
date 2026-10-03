import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
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

function receipt(
  feature: AiFeature,
  overrides: Partial<AiJobReceipt> = {},
): AiJobReceipt {
  return {
    jobId:
      feature === 'ticket.reply_draft'
        ? '33333333-3333-4333-8333-333333333333'
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
    inputScope: 'PUBLIC_ONLY',
    stale: false,
    canInsert: feature === 'ticket.reply_draft',
    errorCode: null,
    result: feature === 'ticket.reply_draft' ? reply : summary,
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
) {
  return (
    <AiAssistantPanel
      capabilities={['AI_SUMMARY', 'AI_TRIAGE', 'AI_REPLY_DRAFT']}
      onRefreshAvailability={vi.fn().mockResolvedValue(undefined)}
      client={aiClient}
      composerMode="PUBLIC"
      onInsertReply={onInsertReply}
      publicDraft={publicDraft}
      ticketNumber={3001}
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
        name: '생성하기',
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
      screen.getAllByRole('button', { name: '생성하기' })[0],
    ).toBeDisabled()
    await act(async () => resolveList?.({ items: [] }))
    await waitFor(() =>
      expect(
        screen.getAllByRole('button', { name: '생성하기' })[0],
      ).toBeEnabled(),
    )
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
})

describe('AI generation availability', () => {
  const props = {
    composerMode: 'PUBLIC' as const,
    publicDraft: emptyDraft,
    ticketNumber: 3001,
    ticketVersion: 7,
    onInsertReply: vi.fn(),
  }
  it('keeps existing jobs readable with no generation capability and ignores unknown capabilities', async () => {
    const aiClient = client({
      list: vi.fn(async () => ({ items: [receipt('ticket.summary')] })),
    })
    render(
      <AiAssistantPanel
        {...props}
        client={aiClient}
        capabilities={['READ', 'UPDATE', 'AI_UNKNOWN']}
        onRefreshAvailability={vi.fn()}
      />,
    )
    expect(
      await screen.findByText('결제 상태를 확인하고 있습니다.'),
    ).toBeVisible()
    for (const button of screen.getAllByRole('button', {
      name: /생성하기|새로 생성/,
    })) {
      expect(button).toBeDisabled()
      fireEvent.click(button)
    }
    expect(
      screen.getByText('현재 이 티켓에서는 대화 요약을 사용할 수 없습니다.'),
    ).toBeVisible()
    expect(aiClient.list).toHaveBeenCalledTimes(1)
    expect(aiClient.create).not.toHaveBeenCalled()
  })
  it('allows only the granted feature and does not generate automatically after capability changes', async () => {
    const aiClient = client()
    const view = render(
      <AiAssistantPanel
        {...props}
        client={aiClient}
        capabilities={['AI_TRIAGE']}
        onRefreshAvailability={vi.fn()}
      />,
    )
    await waitFor(() =>
      expect(
        screen.queryByText('최근 AI 작업을 불러오는 중…'),
      ).not.toBeInTheDocument(),
    )
    const buttons = screen.getAllByRole('button', { name: '생성하기' })
    expect(buttons[0]).toBeDisabled()
    expect(buttons[1]).toBeEnabled()
    expect(buttons[2]).toBeDisabled()
    fireEvent.click(buttons[1]!)
    await waitFor(() =>
      expect(aiClient.create).toHaveBeenCalledWith(3001, 7, 'ticket.triage'),
    )
    view.rerender(
      <AiAssistantPanel
        {...props}
        client={aiClient}
        capabilities={['AI_SUMMARY']}
        onRefreshAvailability={vi.fn()}
      />,
    )
    expect(aiClient.create).toHaveBeenCalledTimes(1)
  })
  it('refreshes availability after a denied create and keeps current jobs instead of retrying creation', async () => {
    const aiClient = client({
      create: vi.fn().mockRejectedValue(new ApiError('denied', 403)),
    })
    const refresh = vi.fn().mockResolvedValue(undefined)
    const view = render(
      <AiAssistantPanel
        {...props}
        client={aiClient}
        capabilities={['AI_SUMMARY']}
        onRefreshAvailability={refresh}
      />,
    )
    const card = screen
      .getByRole('heading', { name: '대화 요약' })
      .closest('article')!
    const button = within(card).getByRole('button', { name: '생성하기' })
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
    view.rerender(
      <AiAssistantPanel
        {...props}
        client={aiClient}
        capabilities={[]}
        onRefreshAvailability={refresh}
      />,
    )
    expect(button).toBeDisabled()
    expect(
      screen.getByText('현재 이 티켓에서는 이 AI 기능을 사용할 수 없습니다.'),
    ).toBeVisible()
    expect(aiClient.create).toHaveBeenCalledTimes(1)
    expect(aiClient.list).toHaveBeenCalledTimes(1)
  })
  it('keeps generation locked after failed policy refresh until an explicit successful check', async () => {
    const aiClient = client({
      create: vi.fn().mockRejectedValue(new ApiError('denied', 403)),
    })
    const refresh = vi
      .fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue(undefined)
    render(
      <AiAssistantPanel
        {...props}
        client={aiClient}
        capabilities={['AI_SUMMARY']}
        onRefreshAvailability={refresh}
      />,
    )
    const button = screen.getAllByRole('button', { name: '생성하기' })[0]!
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
    expect(
      await screen.findByText('AI 사용 가능 상태를 확인하지 못했습니다'),
    ).toBeVisible()
    expect(button).toBeDisabled()
    fireEvent.click(
      screen.getByRole('button', { name: '사용 가능 상태 다시 확인' }),
    )
    await waitFor(() => expect(button).toBeEnabled())
    expect(refresh).toHaveBeenCalledTimes(2)
    expect(aiClient.create).toHaveBeenCalledTimes(1)
  })
})
