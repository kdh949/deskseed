import { afterEach, describe, expect, it, vi } from 'vitest'
import { setConfirmedStaffActor } from '../../api/client'
import {
  createAiJob,
  decodeAiJobReceipt,
  getAiJob,
  recordAiFeedback,
} from './api'

const receipt = {
  jobId: '11111111-1111-4111-8111-111111111111',
  feature: 'ticket.reply_draft',
  status: 'SUCCEEDED',
  phase: 'COMPLETE',
  requestRevision: 7,
  createdAt: '2026-09-17T00:00:00Z',
  deadlineAt: '2026-09-17T00:05:00Z',
  completedAt: '2026-09-17T00:00:05Z',
  resultExpiresAt: '2026-09-24T00:00:05Z',
  pollAfterMs: 1000,
  cancelRequested: false,
  contextRevision: 'a'.repeat(64),
  contextPolicyVersion: 'public-comments-v1',
  inputScope: 'PUBLIC_ONLY',
  stale: false,
  canInsert: true,
  errorCode: null,
  result: {
    type: 'ticket.reply_draft',
    answer: '공개 답변 초안',
    citations: [
      {
        articleId: '22222222-2222-4222-8222-222222222222',
        revisionId: '33333333-3333-4333-8333-333333333333',
        chunkId: '44444444-4444-4444-8444-444444444444',
        title: '공개 문서',
        url: '/help/articles/public-article',
      },
    ],
  },
  provenance: null,
  costMicrousd: 12,
}

afterEach(() => {
  setConfirmedStaffActor(null)
  vi.unstubAllGlobals()
})

describe('AI assistance API', () => {
  it('rejects an unsafe citation URL at the browser boundary', () => {
    expect(
      decodeAiJobReceipt({
        ...receipt,
        result: {
          ...receipt.result,
          citations: [
            { ...receipt.result.citations[0], url: 'https://evil.test' },
          ],
        },
      }),
    ).toBeUndefined()
  })

  it('creates a PUBLIC-only reply job with CSRF, actor and idempotency headers', async () => {
    setConfirmedStaffActor('55555555-5555-4555-8555-555555555555')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: 'csrf-ai', headerName: 'X-CSRF-TOKEN' }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(receipt), {
          status: 202,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
    vi.stubGlobal('fetch', fetchMock)

    await createAiJob(3001, 7, 'ticket.reply_draft')

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/agent/tickets/3001/ai/jobs',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-CSRF-TOKEN': 'csrf-ai',
          'X-Deskseed-Expected-Staff-Id':
            '55555555-5555-4555-8555-555555555555',
          'Idempotency-Key': expect.stringMatching(/^[0-9a-f-]{36}$/),
        }),
      }),
    )
    const request = fetchMock.mock.calls[1]?.[1] as RequestInit
    expect(JSON.parse(String(request.body))).toEqual({
      feature: 'ticket.reply_draft',
      expectedTicketVersion: 7,
      options: { language: 'ko', tone: 'calm' },
    })
  })

  it('uses explicit result revalidation and feedback idempotency', async () => {
    const feedbackReceipt = {
      jobId: receipt.jobId,
      type: 'inserted',
      sourceRevision: receipt.contextRevision,
      replayed: false,
      recordedAt: '2026-09-17T00:00:06Z',
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(receipt), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            token: 'csrf-feedback',
            headerName: 'X-CSRF-TOKEN',
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(feedbackReceipt), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
    vi.stubGlobal('fetch', fetchMock)

    await getAiJob(3001, receipt.jobId, true)
    await recordAiFeedback(3001, receipt.jobId, 'inserted')

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      `/api/v1/agent/tickets/3001/ai/jobs/${receipt.jobId}?includeResult=true`,
      expect.objectContaining({
        cache: 'no-store',
        credentials: 'include',
      }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      `/api/v1/agent/tickets/3001/ai/jobs/${receipt.jobId}/feedback`,
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': expect.any(String),
        }),
      }),
    )
  })
})
