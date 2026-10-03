import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  TicketCollaborationRealtime,
  type CollaborationView,
  type CollaborationSocket,
} from './collaborationRealtime'

describe('TicketCollaborationRealtime', () => {
  it('subscribes with the staff session socket, reports composer visibility, and accepts only metadata updates', () => {
    let socket: FakeSocket | undefined
    const client = new TicketCollaborationRealtime(1042, () => {
      socket = new FakeSocket()
      return socket
    })
    const observed: CollaborationView[] = []
    const stopObserving = client.observe((view) => observed.push(view))

    client.start()
    socket!.open()
    expect(socket!.sent).toEqual([
      JSON.stringify({ version: 1, type: 'subscribe', ticketNumber: 1042 }),
    ])

    client.reportComposerMode('internal')
    expect(socket!.sent.at(-1)).toBe(
      JSON.stringify({
        version: 1,
        type: 'presence.state',
        ticketNumber: 1042,
        state: 'EDITING_INTERNAL',
      }),
    )

    socket!.message({
      version: 1,
      type: 'presence.snapshot',
      ticketNumber: 1042,
      members: [
        {
          ...member('Alice', 'VIEWING'),
          body: 'discarded',
          email: 'discarded@example.test',
        },
      ],
    })
    socket!.message({
      version: 1,
      type: 'ticket.updated',
      ticketNumber: 1042,
      ticketVersion: 8,
      changedFields: ['priority', 'comments'],
      ignoredBody: 'must not be used',
    })
    socket!.message({
      version: 1,
      type: 'ticket.updated',
      ticketNumber: 1042,
      ticketVersion: 9,
      changedFields: ['not valid'],
    })

    const latest = observed.at(-1)!
    expect(latest.connection).toBe('connected')
    expect(latest.members).toEqual([member('Alice', 'VIEWING')])
    expect(latest.ticketUpdate).toEqual({
      ticketVersion: 8,
      changedFields: ['priority', 'comments'],
    })

    stopObserving()
    client.stop()
    expect(socket!.closed).toBe(true)
  })

  it('stops reconnecting and exposes an explicit denied state for authorization failures', () => {
    vi.useFakeTimers()
    let socket: FakeSocket | undefined
    let connections = 0
    const client = new TicketCollaborationRealtime(1042, () => {
      connections += 1
      socket = new FakeSocket()
      return socket
    })
    const observed: CollaborationView[] = []
    client.observe((view) => observed.push(view))

    client.start()
    socket!.open()
    socket!.message({
      version: 1,
      type: 'error',
      code: 'FORBIDDEN',
      retryable: false,
    })

    expect(observed.at(-1)!.connection).toBe('denied')
    expect(socket!.closed).toBe(true)
    vi.advanceTimersByTime(3_000)
    expect(connections).toBe(1)

    client.stop()
  })
  it('clears stale members, restores composer state, and cancels a scheduled retry on manual recovery', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-03T01:00:00Z'))
    const sockets: FakeSocket[] = []
    const client = new TicketCollaborationRealtime(1042, () => {
      const socket = new FakeSocket()
      sockets.push(socket)
      return socket
    })
    let view!: CollaborationView
    client.observe((next) => {
      view = next
    })
    client.start()
    client.reportComposerMode('internal')
    sockets[0]!.open()
    expect(JSON.parse(sockets[0]!.sent[1]!).state).toBe('EDITING_INTERNAL')
    expect(view.snapshotReceived).toBe(false)
    sockets[0]!.message({
      version: 1,
      type: 'presence.snapshot',
      ticketNumber: 1042,
      members: [member('Alice', 'VIEWING')],
    })
    expect(view.lastConfirmedAt).toBe('2026-10-03T01:00:00.000Z')
    sockets[0]!.close()
    expect(view).toMatchObject({
      connection: 'unavailable',
      members: [],
      snapshotReceived: false,
      reconnectAt: Date.now() + 3000,
    })
    client.retry()
    expect(sockets).toHaveLength(2)
    sockets[0]!.message({
      version: 1,
      type: 'presence.snapshot',
      ticketNumber: 1042,
      members: [member('Stale', 'VIEWING')],
    })
    expect(view.members).toEqual([])
    sockets[1]!.open()
    expect(JSON.parse(sockets[1]!.sent[1]!).state).toBe('EDITING_INTERNAL')
    expect(view.reconnectAt).toBeNull()
    vi.advanceTimersByTime(3000)
    expect(sockets).toHaveLength(2)
    sockets[1]!.close()
    vi.advanceTimersByTime(3000)
    expect(sockets).toHaveLength(3)
    client.stop()
    vi.advanceTimersByTime(30000)
    expect(sockets).toHaveLength(3)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('requires explicit retry after denial and never revives the denied socket', () => {
    vi.useFakeTimers()
    const sockets: FakeSocket[] = []
    const client = new TicketCollaborationRealtime(1042, () => {
      const socket = new FakeSocket()
      sockets.push(socket)
      return socket
    })
    let view!: CollaborationView
    client.observe((next) => {
      view = next
    })
    client.start()
    sockets[0]!.open()
    sockets[0]!.message({
      version: 1,
      type: 'error',
      code: 'UNAUTHORIZED',
      retryable: false,
    })
    vi.advanceTimersByTime(30000)
    expect(sockets).toHaveLength(1)
    expect(view.reconnectAt).toBeNull()
    client.retry()
    sockets[1]!.open()
    sockets[0]!.message({
      version: 1,
      type: 'error',
      code: 'FORBIDDEN',
      retryable: false,
    })
    expect(view.connection).toBe('connected')
    client.stop()
  })

  it('reports unsupported WebSocket without scheduling retries', () => {
    vi.useFakeTimers()
    vi.stubGlobal('WebSocket', undefined)
    const client = new TicketCollaborationRealtime(1042)
    let view!: CollaborationView
    client.observe((next) => {
      view = next
    })
    client.start()
    expect(view.connection).toBe('unsupported')
    expect(view.reconnectAt).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
    client.stop()
  })

  it('retries factory failure and ignores delta until a fresh snapshot', () => {
    vi.useFakeTimers()
    const socket = new FakeSocket()
    const factory = vi
      .fn()
      .mockImplementationOnce(() => {
        throw new Error('offline')
      })
      .mockReturnValue(socket)
    const client = new TicketCollaborationRealtime(1042, factory)
    let view!: CollaborationView
    client.observe((next) => {
      view = next
    })
    client.start()
    expect(view.connection).toBe('unavailable')
    vi.advanceTimersByTime(3000)
    socket.open()
    socket.message({
      version: 1,
      type: 'presence.delta',
      ticketNumber: 1042,
      action: 'JOINED',
      member: member('Alice', 'VIEWING'),
    })
    expect(view.members).toEqual([])
    expect(view.lastConfirmedAt).toBeNull()
    client.stop()
  })
  it('honors the bounded rate-limit retry delay returned by the existing server', () => {
    vi.useFakeTimers()
    const sockets: FakeSocket[] = []
    const client = new TicketCollaborationRealtime(1042, () => {
      const socket = new FakeSocket()
      sockets.push(socket)
      return socket
    })
    let view!: CollaborationView
    client.observe((next) => {
      view = next
    })
    client.start()
    sockets[0]!.open()
    sockets[0]!.message({
      version: 1,
      type: 'error',
      code: 'RATE_LIMITED',
      retryAfterMs: 60000,
      retryable: true,
    })
    sockets[0]!.close()
    expect(view.reconnectAt).toBe(Date.now() + 60000)
    vi.advanceTimersByTime(3000)
    expect(sockets).toHaveLength(1)
    vi.advanceTimersByTime(57000)
    expect(sockets).toHaveLength(2)
    client.stop()
  })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

function member(
  displayName: string,
  state: 'VIEWING' | 'EDITING_PUBLIC' | 'EDITING_INTERNAL' | 'AWAY',
) {
  return {
    staffId: '018f7c2c-7348-7a32-a971-4c9a845b3311',
    displayName,
    state,
    lastSeenAt: '2026-08-18T00:00:00Z',
  }
}

class FakeSocket implements CollaborationSocket {
  readyState = 0
  closed = false
  readonly sent: string[] = []
  onclose: ((event: CloseEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent<string>) => void) | null = null
  onopen: ((event: Event) => void) | null = null

  close = () => {
    this.closed = true
    this.readyState = 3
    this.onclose?.({} as CloseEvent)
  }

  send = (data: string) => {
    this.sent.push(data)
  }

  open() {
    this.readyState = 1
    this.onopen?.({} as Event)
  }

  message(value: unknown) {
    this.onmessage?.({ data: JSON.stringify(value) } as MessageEvent<string>)
  }
}
