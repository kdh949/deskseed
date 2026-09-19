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
  generationMode: 'REUSE_OR_CREATE',
  candidateId: '55555555-5555-4555-8555-555555555555',
  reuseKind: 'CACHE_HIT',
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
  it('accepts a server candidate ID and rejects a malformed one', () => {
    expect(decodeAiJobReceipt(receipt)?.candidateId).toBe(
      '55555555-5555-4555-8555-555555555555',
    )
    expect(
      decodeAiJobReceipt({ ...receipt, candidateId: 'browser-created' }),
    ).toBeUndefined()
  })

  it('strictly decodes generation intent and reuse metadata', () => {
    expect(decodeAiJobReceipt(receipt)).toMatchObject({
      generationMode: 'REUSE_OR_CREATE',
      reuseKind: 'CACHE_HIT',
    })
    expect(
      decodeAiJobReceipt({
        ...receipt,
        generationMode: 'NEW_CANDIDATE',
        candidateSequence: 2,
        reuseKind: 'GENERATED',
      }),
    ).toMatchObject({
      generationMode: 'NEW_CANDIDATE',
      candidateSequence: 2,
      reuseKind: 'GENERATED',
    })
    expect(
      decodeAiJobReceipt({ ...receipt, generationMode: 'AUTOMATIC' }),
    ).toBeUndefined()
    expect(
      decodeAiJobReceipt({ ...receipt, generationMode: 'NEW_CANDIDATE' }),
    ).toBeUndefined()
    expect(
      decodeAiJobReceipt({ ...receipt, candidateSequence: 1 }),
    ).toBeUndefined()
    expect(
      decodeAiJobReceipt({
        ...receipt,
        generationMode: undefined,
        reuseKind: 'CACHE_HIT',
      }),
    ).toBeUndefined()
  })

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

    await createAiJob(
      3001,
      7,
      'ticket.reply_draft',
      'REUSE_OR_CREATE',
      '66666666-6666-4666-8666-666666666666',
    )

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/agent/tickets/3001/ai/jobs',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-CSRF-TOKEN': 'csrf-ai',
          'X-Deskseed-Expected-Staff-Id':
            '55555555-5555-4555-8555-555555555555',
          'Idempotency-Key': '66666666-6666-4666-8666-666666666666',
        }),
      }),
    )
    const request = fetchMock.mock.calls[1]?.[1] as RequestInit
    expect(JSON.parse(String(request.body))).toEqual({
      feature: 'ticket.reply_draft',
      expectedTicketVersion: 7,
      generationMode: 'REUSE_OR_CREATE',
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
