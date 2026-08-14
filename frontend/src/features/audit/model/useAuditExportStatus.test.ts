import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import { createElement, type ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuditExportStatus } from './useAuditExportStatus'

const jobId = '11111111-1111-4111-8111-111111111111'

const job = {
  id: jobId,
  status: 'REQUESTED',
  createdAt: '2026-08-14T09:00:00Z',
  format: 'CSV',
  fields: ['occurredAt'],
  artifact: { state: 'NOT_CREATED', generationAvailable: false },
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type':
        status >= 400 ? 'application/problem+json' : 'application/json',
    },
  })
}

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return createElement(QueryClientProvider, { client: queryClient }, children)
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('useAuditExportStatus', () => {
  it('stops auto-polling after 5 attempts on repeated success', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(jsonResponse(job)))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useAuditExportStatus(jobId), {
      wrapper,
    })

    await act(() => vi.advanceTimersByTimeAsync(0))
    for (let attempt = 1; attempt < 5; attempt += 1) {
      await act(() => vi.advanceTimersByTimeAsync(3000))
    }

    expect(result.current.pollingExhausted).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(5)

    // Further time passing must not trigger additional automatic fetches.
    await act(() => vi.advanceTimersByTimeAsync(3000))
    await act(() => vi.advanceTimersByTimeAsync(3000))
    expect(fetchMock).toHaveBeenCalledTimes(5)
  })

  it('stops auto-polling immediately after a failed fetch', async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve(jsonResponse({ title: 'unavailable', status: 503 }, 503)),
    )
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useAuditExportStatus(jobId), {
      wrapper,
    })

    await act(() => vi.advanceTimersByTimeAsync(0))

    expect(result.current.isError).toBe(true)
    expect(result.current.pollingExhausted).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    // Further time passing must not trigger additional automatic fetches.
    await act(() => vi.advanceTimersByTimeAsync(3000))
    await act(() => vi.advanceTimersByTimeAsync(3000))
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
