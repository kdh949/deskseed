export type PresenceState =
  'VIEWING' | 'EDITING_PUBLIC' | 'EDITING_INTERNAL' | 'AWAY'

export type CollaborationMember = {
  staffId: string
  displayName: string
  state: PresenceState
  lastSeenAt: string
}

export type CollaborationView = {
  connection: 'connecting' | 'connected' | 'unavailable'
  members: readonly CollaborationMember[]
  ticketUpdate: {
    ticketVersion: number
    changedFields: readonly string[]
  } | null
}

export type CollaborationSocket = {
  readonly readyState: number
  close: () => void
  send: (data: string) => void
  onclose: ((event: CloseEvent) => void) | null
  onerror: ((event: Event) => void) | null
  onmessage: ((event: MessageEvent<string>) => void) | null
  onopen: ((event: Event) => void) | null
}

export type CollaborationSocketFactory = (url: string) => CollaborationSocket

const SOCKET_OPEN = 1
const HEARTBEAT_MS = 20_000
const RECONNECT_MS = 3_000
const MAX_INBOUND_MESSAGE_BYTES = 4_096

const emptyView: CollaborationView = {
  connection: 'connecting',
  members: [],
  ticketUpdate: null,
}

/**
 * Browser-side advisory channel only. Ticket refresh and optimistic command conflict handling
 * remain owned by the existing workspace; this client never accepts or applies ticket bodies.
 */
export class TicketCollaborationRealtime {
  private socket: CollaborationSocket | null = null
  private heartbeatTimer: number | null = null
  private reconnectTimer: number | null = null
  private running = false
  private view: CollaborationView = emptyView
  private readonly listeners = new Set<(view: CollaborationView) => void>()

  constructor(
    private readonly ticketNumber: number,
    private readonly socketFactory: CollaborationSocketFactory = defaultSocketFactory,
  ) {}

  observe(listener: (view: CollaborationView) => void): () => void {
    this.listeners.add(listener)
    listener(this.view)
    return () => this.listeners.delete(listener)
  }

  start() {
    if (this.running) return
    this.running = true
    this.connect()
  }

  stop() {
    this.running = false
    if (this.heartbeatTimer !== null) window.clearInterval(this.heartbeatTimer)
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer)
    this.heartbeatTimer = null
    this.reconnectTimer = null
    const socket = this.socket
    this.socket = null
    if (socket) socket.close()
  }

  reportComposerMode(mode: 'public' | 'internal' | null) {
    this.send({
      version: 1,
      type: 'presence.state',
      ticketNumber: this.ticketNumber,
      state:
        mode === 'public'
          ? 'EDITING_PUBLIC'
          : mode === 'internal'
            ? 'EDITING_INTERNAL'
            : 'VIEWING',
    })
  }

  private connect() {
    if (
      !this.running ||
      (this.socketFactory === defaultSocketFactory &&
        typeof WebSocket === 'undefined')
    ) {
      this.replaceView({ ...this.view, connection: 'unavailable' })
      return
    }
    this.replaceView({ ...this.view, connection: 'connecting' })
    try {
      const socket = this.socketFactory(webSocketUrl())
      this.socket = socket
      socket.onopen = () => {
        if (this.socket !== socket || !this.running) return
        this.replaceView({ ...this.view, connection: 'connected' })
        this.send({
          version: 1,
          type: 'subscribe',
          ticketNumber: this.ticketNumber,
        })
        this.heartbeatTimer = window.setInterval(
          () => this.send({ version: 1, type: 'heartbeat' }),
          HEARTBEAT_MS,
        )
      }
      socket.onmessage = (event) => this.handleMessage(event.data)
      socket.onerror = () => {
        if (this.socket === socket) {
          this.replaceView({ ...this.view, connection: 'unavailable' })
        }
      }
      socket.onclose = () => {
        if (this.socket !== socket) return
        this.socket = null
        if (this.heartbeatTimer !== null)
          window.clearInterval(this.heartbeatTimer)
        this.heartbeatTimer = null
        this.replaceView({ ...this.view, connection: 'unavailable' })
        if (this.running) {
          this.reconnectTimer = window.setTimeout(
            () => this.connect(),
            RECONNECT_MS,
          )
        }
      }
    } catch {
      this.replaceView({ ...this.view, connection: 'unavailable' })
      if (this.running) {
        this.reconnectTimer = window.setTimeout(
          () => this.connect(),
          RECONNECT_MS,
        )
      }
    }
  }

  private handleMessage(raw: string) {
    if (raw.length > MAX_INBOUND_MESSAGE_BYTES) return
    const message = parseServerMessage(raw)
    if (!message || message.ticketNumber !== this.ticketNumber) return

    if (message.type === 'presence.snapshot') {
      this.replaceView({ ...this.view, members: message.members })
      return
    }
    if (message.type === 'presence.delta') {
      const members = new Map(
        this.view.members.map((member) => [member.staffId, member]),
      )
      if (message.action === 'LEFT' || message.action === 'EXPIRED') {
        members.delete(message.member.staffId)
      } else {
        members.set(message.member.staffId, message.member)
      }
      this.replaceView({
        ...this.view,
        members: [...members.values()].sort((left, right) =>
          left.displayName.localeCompare(right.displayName),
        ),
      })
      return
    }
    if (message.type === 'ticket.updated') {
      this.replaceView({
        ...this.view,
        ticketUpdate: {
          ticketVersion: message.ticketVersion,
          changedFields: message.changedFields,
        },
      })
    }
  }

  private send(message: Record<string, unknown>) {
    if (this.socket?.readyState !== SOCKET_OPEN) return
    this.socket.send(JSON.stringify(message))
  }

  private replaceView(next: CollaborationView) {
    this.view = next
    this.listeners.forEach((listener) => listener(next))
  }
}

const sharedConnections = new Map<number, SharedConnection>()

type SharedConnection = {
  client: TicketCollaborationRealtime
  references: number
}

export function retainTicketCollaboration(ticketNumber: number) {
  const current = sharedConnections.get(ticketNumber)
  if (current) {
    current.references += 1
    return current.client
  }
  const client = new TicketCollaborationRealtime(ticketNumber)
  sharedConnections.set(ticketNumber, { client, references: 1 })
  client.start()
  return client
}

export function releaseTicketCollaboration(ticketNumber: number) {
  const current = sharedConnections.get(ticketNumber)
  if (!current) return
  current.references -= 1
  if (current.references > 0) return
  sharedConnections.delete(ticketNumber)
  current.client.stop()
}

type ServerMessage =
  | {
      type: 'presence.snapshot'
      ticketNumber: number
      members: CollaborationMember[]
    }
  | {
      type: 'presence.delta'
      ticketNumber: number
      action: 'JOINED' | 'UPDATED' | 'LEFT' | 'EXPIRED'
      member: CollaborationMember
    }
  | {
      type: 'ticket.updated'
      ticketNumber: number
      ticketVersion: number
      changedFields: string[]
    }

function parseServerMessage(raw: string): ServerMessage | null {
  try {
    const value: unknown = JSON.parse(raw)
    if (
      !isRecord(value) ||
      value.version !== 1 ||
      !isPositiveTicket(value.ticketNumber)
    ) {
      return null
    }
    if (value.type === 'presence.snapshot' && Array.isArray(value.members)) {
      const members = value.members.map(parseMember)
      return members.every((member) => member !== null)
        ? { type: value.type, ticketNumber: value.ticketNumber, members }
        : null
    }
    const member = parseMember(value.member)
    if (
      value.type === 'presence.delta' &&
      isPresenceAction(value.action) &&
      member
    ) {
      return {
        type: value.type,
        ticketNumber: value.ticketNumber,
        action: value.action,
        member,
      }
    }
    if (
      value.type === 'ticket.updated' &&
      isPositiveTicket(value.ticketVersion) &&
      Array.isArray(value.changedFields) &&
      value.changedFields.length <= 32 &&
      value.changedFields.every(isSafeFieldName)
    ) {
      return {
        type: value.type,
        ticketNumber: value.ticketNumber,
        ticketVersion: value.ticketVersion,
        changedFields: value.changedFields,
      }
    }
    return null
  } catch {
    return null
  }
}

function parseMember(value: unknown): CollaborationMember | null {
  if (!isRecord(value)) return null
  if (
    typeof value.staffId !== 'string' ||
    value.staffId.length < 1 ||
    value.staffId.length > 64 ||
    typeof value.displayName !== 'string' ||
    value.displayName.length < 1 ||
    value.displayName.length > 120 ||
    !isPresenceState(value.state) ||
    typeof value.lastSeenAt !== 'string' ||
    Number.isNaN(Date.parse(value.lastSeenAt))
  ) {
    return null
  }
  return value as CollaborationMember
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isPositiveTicket(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isPresenceAction(
  value: unknown,
): value is 'JOINED' | 'UPDATED' | 'LEFT' | 'EXPIRED' {
  return (
    value === 'JOINED' ||
    value === 'UPDATED' ||
    value === 'LEFT' ||
    value === 'EXPIRED'
  )
}

function isPresenceState(value: unknown): value is PresenceState {
  return (
    value === 'VIEWING' ||
    value === 'EDITING_PUBLIC' ||
    value === 'EDITING_INTERNAL' ||
    value === 'AWAY'
  )
}

function isSafeFieldName(value: unknown): value is string {
  return typeof value === 'string' && /^[a-z][a-zA-Z0-9]{0,63}$/.test(value)
}

function defaultSocketFactory(url: string): CollaborationSocket {
  return new WebSocket(url)
}

function webSocketUrl(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${scheme}//${window.location.host}/ws/agent/collaboration`
}
