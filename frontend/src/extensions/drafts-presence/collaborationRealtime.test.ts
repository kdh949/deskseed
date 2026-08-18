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
      members: [member('Alice', 'VIEWING')],
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
})

afterEach(() => {
  vi.useRealTimers()
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
