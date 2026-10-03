import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAgentNotifications } from './useAgentNotifications'
import {
  listAgentNotifications,
  markAgentNotificationRead,
} from '../../api/client'
vi.mock('../../api/client', () => ({
  listAgentNotifications: vi.fn(),
  markAgentNotificationRead: vi.fn(),
}))
class Socket {
  static instances: Socket[] = []
  onopen: (() => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  close = vi.fn()
  constructor() {
    Socket.instances.push(this)
  }
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('WebSocket', Socket)
  Socket.instances = []
  vi.mocked(listAgentNotifications).mockResolvedValue({
    items: [],
    unreadCount: 0,
    nextCursor: null,
  })
  vi.mocked(markAgentNotificationRead).mockResolvedValue(undefined)
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.resetAllMocks()
})
it('reconnects and refreshes from REST after close, visibility recovery and polling', async () => {
  const { unmount } = renderHook(() => useAgentNotifications('agent-1'))
  await act(async () => {})
  expect(listAgentNotifications).toHaveBeenCalledTimes(1)
  await act(async () => {
    Socket.instances[0]!.onclose?.()
    await vi.advanceTimersByTimeAsync(1000)
  })
  expect(Socket.instances).toHaveLength(2)
  await act(async () => {
    Socket.instances[1]!.onopen?.()
    window.dispatchEvent(new Event('focus'))
  })
  const count = vi.mocked(listAgentNotifications).mock.calls.length
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30000)
  })
  expect(vi.mocked(listAgentNotifications).mock.calls.length).toBeGreaterThan(
    count,
  )
  unmount()
  const stopped = vi.mocked(listAgentNotifications).mock.calls.length
  await act(async () => {
    await vi.advanceTimersByTimeAsync(60000)
    window.dispatchEvent(new Event('focus'))
  })
  expect(listAgentNotifications).toHaveBeenCalledTimes(stopped)
  expect(Socket.instances.at(-1)?.close).toHaveBeenCalled()
})
it('uses REST polling even without WebSocket and never loads for an auditor', async () => {
  vi.stubGlobal('WebSocket', undefined)
  const { rerender } = renderHook(({ owner }) => useAgentNotifications(owner), {
    initialProps: { owner: null as string | null },
  })
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30000)
  })
  expect(listAgentNotifications).not.toHaveBeenCalled()
  rerender({ owner: 'agent-1' })
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30000)
  })
  expect(listAgentNotifications).toHaveBeenCalledTimes(2)
})
it('does not expose an old actor response after the session changes', async () => {
  let finish!: (
    page: Awaited<ReturnType<typeof listAgentNotifications>>,
  ) => void
  vi.mocked(listAgentNotifications).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const { result, rerender } = renderHook(
    ({ owner }) => useAgentNotifications(owner),
    { initialProps: { owner: 'agent-1' as string | null } },
  )
  rerender({ owner: null })
  await act(async () => {
    finish({ items: [], unreadCount: 12, nextCursor: null })
  })
  expect(result.current.unreadCount).toBe(0)
})
