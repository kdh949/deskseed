import { act, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  clearAgentTicketDraft,
  getAgentTicketDraft,
  saveAgentTicketDraft,
} from '../../api/client'
import type * as ApiClientModule from '../../api/client'
import type {
  CommentContent,
  TicketDraft,
  TicketVisibility,
} from '../../api/types'
import {
  readLocalTicketDraft,
  removeLocalTicketDraft,
  writeLocalTicketDraft,
  type LocalTicketDraft,
} from './draftRecovery'
import type * as DraftRecoveryModule from './draftRecovery'
import { useTicketDraftSync } from './useTicketDraftSync'

vi.mock('../../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiClientModule>()
  return {
    ...actual,
    clearAgentTicketDraft: vi.fn(),
    getAgentTicketDraft: vi.fn(),
    saveAgentTicketDraft: vi.fn(),
  }
})

vi.mock('./draftRecovery', async (importOriginal) => {
  const actual = await importOriginal<typeof DraftRecoveryModule>()
  return {
    ...actual,
    readLocalTicketDraft: vi.fn(),
    removeLocalTicketDraft: vi.fn(),
    writeLocalTicketDraft: vi.fn(),
  }
})

const staffId = '11111111-1111-4111-8111-111111111111'
const ticketNumber = 8101
const baseTicketVersion = 12

describe('useTicketDraftSync', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-24T12:00:00Z'))
    vi.mocked(clearAgentTicketDraft).mockResolvedValue(undefined)
    vi.mocked(removeLocalTicketDraft).mockResolvedValue(undefined)
    vi.mocked(writeLocalTicketDraft).mockResolvedValue(undefined)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('checkpoints both channels before the debounce without claiming or starting a remote save', async () => {
    vi.mocked(readLocalTicketDraft).mockResolvedValue(null)
    vi.mocked(getAgentTicketDraft).mockRejectedValue(
      new ApiError('not found', 404),
    )
    const initialDrafts: ComposerDrafts = {
      PUBLIC: {
        body: '공개 서식 초안',
        content: {
          format: 'RICH_TEXT_V1',
          document: {
            type: 'doc',
            content: [
              {
                type: 'paragraph',
                content: [
                  {
                    type: 'text',
                    text: '공개 서식 초안',
                    marks: [{ type: 'bold' }],
                  },
                ],
              },
            ],
          },
        },
        attachmentIds: ['attachment-public'],
      },
      INTERNAL: {
        body: '내부 초안',
        content: { format: 'PLAIN_TEXT', text: '내부 초안' },
        attachmentIds: [],
      },
    }
    let sync: ReturnType<typeof useTicketDraftSync> | undefined
    render(
      <DraftSyncHarness
        initialDrafts={initialDrafts}
        onSync={(next) => (sync = next)}
      />,
    )
    await act(async () => {
      await sync?.preserveLocalForNavigation()
    })
    for (const visibility of ['PUBLIC', 'INTERNAL'] as const) {
      expect(writeLocalTicketDraft).toHaveBeenCalledWith(
        expect.objectContaining({
          staffId,
          ticketNumber,
          baseTicketVersion,
          draftVersion: null,
          channel: visibility === 'PUBLIC' ? 'PUBLIC_REPLY' : 'INTERNAL_NOTE',
          ...initialDrafts[visibility],
        }),
      )
    }
    expect(saveAgentTicketDraft).not.toHaveBeenCalled()
    expect(clearAgentTicketDraft).not.toHaveBeenCalled()
  })

  it('rejects a failed local checkpoint without attempting a ticket or remote draft write', async () => {
    vi.mocked(readLocalTicketDraft).mockResolvedValue(null)
    vi.mocked(getAgentTicketDraft).mockRejectedValue(
      new ApiError('not found', 404),
    )
    const failure = new Error('local storage blocked')
    vi.mocked(writeLocalTicketDraft).mockRejectedValue(failure)
    let sync: ReturnType<typeof useTicketDraftSync> | undefined
    render(<DraftSyncHarness onSync={(next) => (sync = next)} />)
    await act(async () => undefined)
    fireEvent.click(screen.getByRole('button', { name: '초안 변경' }))
    await act(async () => {
      await expect(sync?.preserveLocalForNavigation()).rejects.toBe(failure)
    })
    expect(saveAgentTicketDraft).not.toHaveBeenCalled()
  })

  it('persists a newer recovered local draft with the fetched server version', async () => {
    const remote = ticketDraft({
      body: 'older server draft',
      draftVersion: 7,
      updatedAt: '2026-08-24T10:00:00Z',
    })
    const local: LocalTicketDraft = {
      ...remote,
      staffId,
      body: 'newer local draft',
      content: { format: 'PLAIN_TEXT', text: 'newer local draft' },
      clientDeviceId: '22222222-2222-4222-8222-222222222222',
      draftVersion: 4,
      updatedAt: '2026-08-24T11:00:00Z',
    }
    vi.mocked(readLocalTicketDraft).mockImplementation(
      async (_staffId, _ticketNumber, channel) =>
        channel === 'PUBLIC_REPLY' ? local : null,
    )
    vi.mocked(getAgentTicketDraft).mockImplementation(
      async (_ticketNumber, channel) => {
        if (channel === 'PUBLIC_REPLY') return remote
        throw new ApiError('draft not found', 404)
      },
    )
    vi.mocked(saveAgentTicketDraft).mockResolvedValue(
      ticketDraft({
        body: local.body,
        draftVersion: 8,
        updatedAt: '2026-08-24T12:00:03Z',
      }),
    )

    render(<DraftSyncHarness />)

    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(readLocalTicketDraft).toHaveBeenCalledWith(
      staffId,
      ticketNumber,
      'PUBLIC_REPLY',
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
    })

    expect(saveAgentTicketDraft).toHaveBeenCalledWith(
      ticketNumber,
      'PUBLIC_REPLY',
      expect.objectContaining({
        content: { format: 'PLAIN_TEXT', text: 'newer local draft' },
        expectedDraftVersion: 7,
      }),
    )
    expect(clearAgentTicketDraft).not.toHaveBeenCalled()
  })

  it('does not claim local-only recovery when both local and server writes fail', async () => {
    vi.mocked(readLocalTicketDraft).mockResolvedValue(null)
    vi.mocked(getAgentTicketDraft).mockRejectedValue(
      new ApiError('not found', 404),
    )
    vi.mocked(writeLocalTicketDraft).mockRejectedValue(
      new Error('local quota exceeded'),
    )
    vi.mocked(saveAgentTicketDraft).mockRejectedValue(
      new ApiError('server unavailable', 500),
    )
    const onFailure = vi.fn()
    let sync: ReturnType<typeof useTicketDraftSync> | undefined
    render(
      <DraftSyncHarness
        onSync={(next) => (sync = next)}
        onFailure={onFailure}
      />,
    )
    await act(async () => undefined)
    fireEvent.click(screen.getByRole('button', { name: '초안 변경' }))
    await act(async () => {
      await sync?.flush()
    })
    expect(sync?.state).toBe('error')
    expect(onFailure).toHaveBeenCalledWith(
      '서버와 이 브라우저에 복구 초안을 보관하지 못했습니다. 작성 내용은 현재 화면에 유지됩니다.',
      undefined,
    )
    expect(onFailure).toHaveBeenCalledTimes(1)
  })

  it('waits for hydration before flushing input entered while draft recovery is pending', async () => {
    const localRead = deferred<LocalTicketDraft | null>()
    vi.mocked(readLocalTicketDraft).mockImplementation(() => localRead.promise)
    vi.mocked(getAgentTicketDraft).mockRejectedValue(
      new ApiError('not found', 404),
    )
    vi.mocked(saveAgentTicketDraft).mockResolvedValue(
      ticketDraft({
        body: 'first draft',
        draftVersion: 1,
        updatedAt: '2026-08-24T12:00:00Z',
      }),
    )
    let sync: ReturnType<typeof useTicketDraftSync> | undefined
    render(<DraftSyncHarness onSync={(next) => (sync = next)} />)
    fireEvent.click(screen.getByRole('button', { name: '초안 변경' }))
    let pending: Promise<void> | undefined
    act(() => {
      pending = sync?.flush()
    })
    expect(saveAgentTicketDraft).not.toHaveBeenCalled()
    await act(async () => {
      localRead.resolve(null)
      await pending
    })
    expect(saveAgentTicketDraft).toHaveBeenCalledExactlyOnceWith(
      ticketNumber,
      'PUBLIC_REPLY',
      expect.objectContaining({
        content: { format: 'PLAIN_TEXT', text: 'first draft' },
        expectedDraftVersion: 0,
      }),
    )
  })

  it('serializes an in-flight autosave with a manual flush and cancels its pending timer', async () => {
    vi.mocked(readLocalTicketDraft).mockResolvedValue(null)
    vi.mocked(getAgentTicketDraft).mockRejectedValue(
      new ApiError('draft not found', 404),
    )
    const firstSave = deferred<TicketDraft>()
    const secondSave = deferred<TicketDraft>()
    vi.mocked(saveAgentTicketDraft)
      .mockImplementationOnce(() => firstSave.promise)
      .mockImplementationOnce(() => secondSave.promise)
    let sync: { flush: () => Promise<void> } | undefined

    render(<DraftSyncHarness onSync={(next) => (sync = next)} />)
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })

    fireEvent.click(screen.getByRole('button', { name: '초안 변경' }))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
    })
    expect(saveAgentTicketDraft).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: '초안 변경' }))
    let flushPromise: Promise<void> | undefined
    act(() => {
      flushPromise = sync?.flush()
    })
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(saveAgentTicketDraft).toHaveBeenCalledTimes(1)

    await act(async () => {
      firstSave.resolve(
        ticketDraft({
          body: 'first draft',
          draftVersion: 1,
          updatedAt: '2026-08-24T12:00:03Z',
        }),
      )
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(saveAgentTicketDraft).toHaveBeenCalledTimes(2)
    expect(saveAgentTicketDraft).toHaveBeenLastCalledWith(
      ticketNumber,
      'PUBLIC_REPLY',
      expect.objectContaining({
        content: { format: 'PLAIN_TEXT', text: 'second draft' },
        expectedDraftVersion: 1,
      }),
    )

    secondSave.resolve(
      ticketDraft({
        body: 'second draft',
        draftVersion: 2,
        updatedAt: '2026-08-24T12:00:04Z',
      }),
    )
    await act(async () => {
      await flushPromise
      await vi.advanceTimersByTimeAsync(3_000)
    })
    expect(saveAgentTicketDraft).toHaveBeenCalledTimes(2)
  })

  it('clears only the submitted channel immediately after an in-flight autosave, without waiting for debounce', async () => {
    vi.mocked(readLocalTicketDraft).mockResolvedValue(null)
    vi.mocked(getAgentTicketDraft).mockRejectedValue(
      new ApiError('draft not found', 404),
    )
    const saving = deferred<TicketDraft>()
    vi.mocked(saveAgentTicketDraft).mockImplementationOnce(() => saving.promise)
    let sync: ReturnType<typeof useTicketDraftSync> | undefined
    render(<DraftSyncHarness onSync={(next) => (sync = next)} />)
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    fireEvent.click(screen.getByRole('button', { name: '초안 변경' }))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
    })
    let cleanup: Promise<void> | undefined
    act(() => {
      cleanup = sync?.clearSubmitted('PUBLIC')
    })
    expect(clearAgentTicketDraft).not.toHaveBeenCalled()
    await act(async () => {
      saving.resolve(
        ticketDraft({
          body: 'first draft',
          draftVersion: 1,
          updatedAt: '2026-08-24T12:00:03Z',
        }),
      )
      await cleanup
    })
    expect(clearAgentTicketDraft).toHaveBeenCalledExactlyOnceWith(
      ticketNumber,
      'PUBLIC_REPLY',
      1,
    )
    expect(removeLocalTicketDraft).toHaveBeenLastCalledWith(
      staffId,
      ticketNumber,
      'PUBLIC_REPLY',
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
    })
    expect(saveAgentTicketDraft).toHaveBeenCalledTimes(1)
  })

  it.each([
    {
      status: 409,
      state: 'conflict',
      message:
        '티켓 저장은 완료됐습니다. 다른 브라우저의 새 복구 초안은 삭제하지 않았습니다.',
    },
    {
      status: 503,
      state: 'local-only',
      message: '티켓 저장은 완료됐지만 서버 복구 초안을 정리하지 못했습니다.',
    },
  ])(
    'reports cleanup $status separately from ticket success without retrying a clear',
    async ({ status, state, message }) => {
      vi.mocked(readLocalTicketDraft).mockResolvedValue(null)
      vi.mocked(getAgentTicketDraft).mockImplementation(
        async (_ticket, channel) => {
          if (channel === 'PUBLIC_REPLY')
            return ticketDraft({
              body: 'submitted reply',
              draftVersion: 7,
              updatedAt: '2026-08-24T11:00:00Z',
            })
          throw new ApiError('draft not found', 404)
        },
      )
      vi.mocked(clearAgentTicketDraft).mockRejectedValue(
        new ApiError('draft cleanup failed', status),
      )
      const onFailure = vi.fn()
      let sync: ReturnType<typeof useTicketDraftSync> | undefined
      render(
        <DraftSyncHarness
          onSync={(next) => (sync = next)}
          onFailure={onFailure}
        />,
      )
      await act(async () => {
        await Promise.resolve()
        await Promise.resolve()
      })
      await act(async () => {
        await sync?.clearSubmitted('PUBLIC')
      })
      expect(clearAgentTicketDraft).toHaveBeenCalledExactlyOnceWith(
        ticketNumber,
        'PUBLIC_REPLY',
        7,
      )
      expect(onFailure).toHaveBeenCalledWith(
        expect.stringContaining(message),
        undefined,
      )
      expect(sync?.state).toBe(state)
      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000)
        await sync?.flush()
      })
      expect(clearAgentTicketDraft).toHaveBeenCalledTimes(1)
      expect(saveAgentTicketDraft).not.toHaveBeenCalled()
    },
  )

  it('does not delete a remote version that was unknown when the confirmed reply was submitted', async () => {
    vi.mocked(readLocalTicketDraft).mockResolvedValue(null)
    const loading = deferred<TicketDraft>()
    vi.mocked(getAgentTicketDraft).mockImplementation(
      async (_ticket, channel) => {
        if (channel === 'PUBLIC_REPLY') return loading.promise
        throw new ApiError('draft not found', 404)
      },
    )
    let sync: ReturnType<typeof useTicketDraftSync> | undefined
    const onFailure = vi.fn()
    render(
      <DraftSyncHarness
        onSync={(next) => (sync = next)}
        onFailure={onFailure}
      />,
    )
    let cleanup: Promise<void> | undefined
    act(() => {
      cleanup = sync?.clearSubmitted('PUBLIC')
    })
    await act(async () => {
      loading.resolve(
        ticketDraft({
          body: 'unconfirmed other draft',
          draftVersion: 8,
          updatedAt: '2026-08-24T11:00:00Z',
        }),
      )
      await cleanup
    })
    expect(clearAgentTicketDraft).not.toHaveBeenCalled()
    expect(onFailure).toHaveBeenCalledWith(
      expect.stringContaining(
        '저장 전에 확인하지 못한 서버 복구 초안은 삭제하지 않았습니다.',
      ),
    )
  })
})

function DraftSyncHarness({
  onSync,
  onFailure = () => undefined,
  initialDrafts,
}: {
  onSync?: (sync: ReturnType<typeof useTicketDraftSync>) => void
  onFailure?: (message: string, requestId?: string) => void
  initialDrafts?: ComposerDrafts
}) {
  const [drafts, setDrafts] = useState<ComposerDrafts>(
    initialDrafts ?? {
      PUBLIC: {
        body: '',
        content: { format: 'PLAIN_TEXT', text: '' },
        attachmentIds: [],
      },
      INTERNAL: {
        body: '',
        content: { format: 'PLAIN_TEXT', text: '' },
        attachmentIds: [],
      },
    },
  )
  const sync = useTicketDraftSync({
    staffId,
    ticketNumber,
    baseTicketVersion,
    drafts,
    migrateLegacy: false,
    onRecover: (recovered) =>
      setDrafts((current) => ({ ...current, ...recovered })),
    onFailure,
  })
  onSync?.(sync)
  return (
    <button
      onClick={() =>
        setDrafts((current) => {
          const body =
            current.PUBLIC.body === '' ? 'first draft' : 'second draft'
          return {
            ...current,
            PUBLIC: {
              ...current.PUBLIC,
              body,
              content: { format: 'PLAIN_TEXT', text: body },
            },
          }
        })
      }
      type="button"
    >
      초안 변경
    </button>
  )
}

type ComposerDrafts = Record<
  TicketVisibility,
  { body: string; content: CommentContent; attachmentIds: string[] }
>

function ticketDraft({
  body,
  draftVersion,
  updatedAt,
}: {
  body: string
  draftVersion: number
  updatedAt: string
}): TicketDraft {
  return {
    ticketNumber,
    channel: 'PUBLIC_REPLY',
    body,
    content: { format: 'PLAIN_TEXT', text: body },
    attachmentIds: [],
    clientDeviceId: '33333333-3333-4333-8333-333333333333',
    baseTicketVersion,
    draftVersion,
    updatedAt,
    expiresAt: '2026-08-31T12:00:00Z',
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((next) => {
    resolve = next
  })
  return { promise, resolve }
}
