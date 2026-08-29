import { act, render } from '@testing-library/react'
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
})

function DraftSyncHarness() {
  const [drafts, setDrafts] = useState<ComposerDrafts>({
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
  })
  useTicketDraftSync({
    staffId,
    ticketNumber,
    baseTicketVersion,
    drafts,
    migrateLegacy: false,
    onRecover: (recovered) =>
      setDrafts((current) => ({ ...current, ...recovered })),
    onFailure: () => undefined,
  })
  return null
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
